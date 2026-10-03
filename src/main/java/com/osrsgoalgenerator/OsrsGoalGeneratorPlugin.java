package com.osrsgoalgenerator;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.ScriptID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.Text;

@PluginDescriptor(
    name = "OSRS Goal Generator Companion",
    description = "Local-only live account bridge for the OSRS Goal Generator desktop app",
    tags = {"goals", "progression", "collection log", "skills"},
    internalName = "osrs-goal-generator-companion"
)
public class OsrsGoalGeneratorPlugin extends Plugin
{
    private static final Logger LOG = Logger.getLogger(OsrsGoalGeneratorPlugin.class.getName());
    private static final int SCHEMA_VERSION = 1;
    private static final String PLUGIN_VERSION = "0.1.2-alpha8.2";
    private static final String SYNC_FILE = "sync.json";
    private static final String CACHE_FILE = "collection-cache.json";
    private static final int COLLECTION_HEADER_TITLE_INDEX = 0;
    private static final long MIN_WRITE_INTERVAL_MS = 750L;
    private static final Pattern COLLECTION_UNLOCK_PATTERN = Pattern.compile(
        "New item added to your collection log:\\s*(.+)", Pattern.CASE_INSENSITIVE);

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private Gson gson;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private OsrsGoalGeneratorConfig config;

    private final Map<Skill, Integer> sessionBaselineXp = new EnumMap<>(Skill.class);
    private final Map<String, Map<String, CollectionPageSnapshot>> collectionPagesByPlayer = new ConcurrentHashMap<>();
    private final Deque<String> recentCollectionUnlocks = new ArrayDeque<>();
    private final AtomicLong writeGeneration = new AtomicLong();
    private final AtomicLong cacheGeneration = new AtomicLong();
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private volatile boolean enabled;
    private final Object fileWriteLock = new Object();

    private volatile boolean collectionCacheLoaded;
    private String currentPlayerName = "";
    private String sessionStartedAt = "";
    private String lastEvent = "startup";
    private long lastSnapshotQueuedAt;
    private long lastHeartbeatAt;

    @Provides
    OsrsGoalGeneratorConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(OsrsGoalGeneratorConfig.class);
    }

    @Override
    protected void startUp()
    {
        enabled = true;
        writeGeneration.incrementAndGet();
        long lifecycle = lifecycleGeneration.incrementAndGet();
        collectionCacheLoaded = false;
        executor.execute(() -> loadCollectionCache(lifecycle));
    }

    @Override
    protected void shutDown()
    {
        enabled = false;
        writeGeneration.incrementAndGet();
        long lifecycle = lifecycleGeneration.incrementAndGet();
        clientThread.invokeLater(() ->
        {
            if (lifecycle != lifecycleGeneration.get())
            {
                return;
            }
            lastEvent = "shutdown";
            String json = buildSnapshotJson(false);
            long generation = writeGeneration.incrementAndGet();
            executor.execute(() -> writeSyncSnapshot(generation, json));
        });
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            Player player = client.getLocalPlayer();
            if (player != null)
            {
                ensureSessionForPlayer(player.getName());
            }
            lastEvent = "login";
            queueSnapshot(true, true);
            return;
        }

        if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            lastEvent = "logout";
            queueSnapshot(true, false);
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (client.getGameState() != GameState.LOGGED_IN || !collectionCacheLoaded)
        {
            return;
        }

        long now = System.currentTimeMillis();
        long heartbeatMs = Math.max(2, Math.min(10, config.heartbeatSeconds())) * 1000L;
        if (now - lastHeartbeatAt >= heartbeatMs)
        {
            lastHeartbeatAt = now;
            queueSnapshot(false, true);
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        Skill skill = event.getSkill();
        if (skill != null)
        {
            sessionBaselineXp.putIfAbsent(skill, client.getSkillExperience(skill));
            lastEvent = "stat:" + skill.getName();
        }
        queueSnapshot(false, true);
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        if (!enabled || !collectionCacheLoaded || !config.syncCollectionLog()
            || event.getScriptId() != ScriptID.COLLECTION_DRAW_LIST
            || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        captureCollectionLogPage();
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        String message = Text.removeTags(event.getMessage());
        Matcher matcher = COLLECTION_UNLOCK_PATTERN.matcher(message);
        if (!matcher.find())
        {
            return;
        }

        String itemName = matcher.group(1).trim();
        if (itemName.isEmpty())
        {
            return;
        }

        recentCollectionUnlocks.remove(itemName);
        recentCollectionUnlocks.addFirst(itemName);
        while (recentCollectionUnlocks.size() > 20)
        {
            recentCollectionUnlocks.removeLast();
        }
        lastEvent = "collection_unlock:" + itemName;
        queueSnapshot(true, true);
    }

    private void captureCollectionLogPage()
    {
        Player player = client.getLocalPlayer();
        if (player == null)
        {
            return;
        }
        ensureSessionForPlayer(player.getName());

        Widget header = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
        Widget contents = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
        if (header == null || contents == null || header.getChildren() == null || contents.getChildren() == null)
        {
            return;
        }

        Widget titleWidget = header.getChild(COLLECTION_HEADER_TITLE_INDEX);
        if (titleWidget == null)
        {
            return;
        }

        String pageName = Text.removeTags(titleWidget.getText()).trim();
        if (pageName.isEmpty())
        {
            return;
        }

        List<CollectionItemSnapshot> items = new ArrayList<>();
        for (Widget child : contents.getChildren())
        {
            if (child == null || child.getItemId() <= 0)
            {
                continue;
            }

            int itemId = child.getItemId();
            ItemComposition composition = itemManager.getItemComposition(itemId);
            String itemName = composition == null || composition.getName() == null
                ? "Item " + itemId
                : composition.getName();
            items.add(new CollectionItemSnapshot(itemId, itemName, child.getOpacity() == 0));
        }

        if (items.isEmpty())
        {
            return;
        }

        CollectionPageSnapshot page = new CollectionPageSnapshot();
        page.updated_at = Instant.now().toString();
        page.items = items;
        activeCollectionPages().put(pageName, page);
        lastEvent = "collection_page:" + pageName;

        persistCollectionCache();
        queueSnapshot(true, true);
    }

    private void ensureSessionForPlayer(String playerName)
    {
        String clean = playerName == null ? "" : playerName.trim();
        if (clean.isEmpty() || clean.equalsIgnoreCase(currentPlayerName))
        {
            return;
        }

        currentPlayerName = clean;
        sessionStartedAt = Instant.now().toString();
        sessionBaselineXp.clear();
        for (Skill skill : Skill.values())
        {
            sessionBaselineXp.put(skill, client.getSkillExperience(skill));
        }
        recentCollectionUnlocks.clear();
    }

    private Map<String, CollectionPageSnapshot> activeCollectionPages()
    {
        String key = normalizePlayerKey(currentPlayerName);
        return collectionPagesByPlayer.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
    }

    private static String normalizePlayerKey(String name)
    {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private void queueSnapshot(boolean force, boolean connected)
    {
        if (!enabled || !collectionCacheLoaded)
        {
            return;
        }

        long now = System.currentTimeMillis();
        if (!force && now - lastSnapshotQueuedAt < MIN_WRITE_INTERVAL_MS)
        {
            return;
        }
        lastSnapshotQueuedAt = now;

        String json = buildSnapshotJson(connected);
        long generation = writeGeneration.incrementAndGet();
        executor.execute(() -> writeSyncSnapshot(generation, json));
    }

    private String buildSnapshotJson(boolean requestedConnected)
    {
        Player player = client.getLocalPlayer();
        boolean connected = requestedConnected
            && client.getGameState() == GameState.LOGGED_IN
            && player != null;

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schema_version", SCHEMA_VERSION);
        document.put("plugin_version", PLUGIN_VERSION);
        document.put("updated_at", Instant.now().toString());
        document.put("connected", connected);
        document.put("game_state", client.getGameState().name());

        Map<String, Object> playerBlock = new LinkedHashMap<>();
        if (connected)
        {
            ensureSessionForPlayer(player.getName());
            playerBlock.put("name", player.getName());
            playerBlock.put("account_type", "unknown");
            playerBlock.put("combat_level", player.getCombatLevel());
            playerBlock.put("total_level", client.getTotalLevel());
            playerBlock.put("total_xp", client.getOverallExperience());
        }
        else
        {
            playerBlock.put("name", currentPlayerName);
            playerBlock.put("account_type", "unknown");
            playerBlock.put("combat_level", null);
            playerBlock.put("total_level", null);
            playerBlock.put("total_xp", null);
        }
        document.put("player", playerBlock);

        Map<String, Object> skills = new LinkedHashMap<>();
        Map<String, Integer> xpGained = new LinkedHashMap<>();
        if (connected)
        {
            for (Skill skill : Skill.values())
            {
                int xp = client.getSkillExperience(skill);
                int level = client.getRealSkillLevel(skill);
                Map<String, Object> skillBlock = new LinkedHashMap<>();
                skillBlock.put("level", level);
                skillBlock.put("xp", xp);
                skills.put(skill.getName(), skillBlock);

                int baseline = sessionBaselineXp.getOrDefault(skill, xp);
                xpGained.put(skill.getName(), Math.max(0, xp - baseline));
            }
        }
        document.put("skills", skills);

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("started_at", sessionStartedAt);
        session.put("last_event", lastEvent);
        session.put("xp_gained", xpGained);
        document.put("session", session);

        Map<String, Object> collectionLog = new LinkedHashMap<>();
        Map<String, CollectionPageSnapshot> pages = config.syncCollectionLog() && !currentPlayerName.isEmpty()
            ? new LinkedHashMap<>(activeCollectionPages())
            : new LinkedHashMap<>();
        collectionLog.put("pages", pages);
        collectionLog.put("recent_unlocks", new ArrayList<>(recentCollectionUnlocks));
        document.put("collection_log", collectionLog);

        return gson.toJson(document);
    }

    private void writeSyncSnapshot(long generation, String json)
    {
        synchronized (fileWriteLock)
        {
            if (generation < writeGeneration.get())
            {
                return;
            }
            writeAtomic(SYNC_FILE, json);
        }
    }

    private void persistCollectionCache()
    {
        CollectionCache cache = new CollectionCache();
        for (Map.Entry<String, Map<String, CollectionPageSnapshot>> entry : collectionPagesByPlayer.entrySet())
        {
            cache.accounts.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        String json = gson.toJson(cache);
        long generation = cacheGeneration.incrementAndGet();
        executor.execute(() ->
        {
            synchronized (fileWriteLock)
            {
                if (generation != cacheGeneration.get())
                {
                    return;
                }
                writeAtomic(CACHE_FILE, json);
            }
        });
    }

    String readCollectionCache() throws IOException
    {
        Filepath cachePath = getPluginDirectory().join(CACHE_FILE);
        if (!cachePath.exists())
        {
            return "{}";
        }
        try (BufferedReader reader = cachePath.openBufferedReader())
        {
            StringBuilder contents = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                contents.append(line);
            }
            return contents.toString();
        }
    }

    private void loadCollectionCache(long lifecycle)
    {
        CollectionCache loaded = null;
        try
        {
            loaded = gson.fromJson(readCollectionCache(), CollectionCache.class);
        }
        catch (Exception ex)
        {
            LOG.log(Level.WARNING, "Unable to load OSRS Goal Generator collection cache", ex);
        }
        CollectionCache cache = loaded;
        clientThread.invokeLater(() ->
        {
            if (!enabled || lifecycle != lifecycleGeneration.get())
            {
                return;
            }
            if (cache != null && cache.accounts != null)
            {
                for (Map.Entry<String, Map<String, CollectionPageSnapshot>> entry : cache.accounts.entrySet())
                {
                    if (entry.getValue() != null)
                    {
                        Map<String, CollectionPageSnapshot> pages = collectionPagesByPlayer.computeIfAbsent(
                            normalizePlayerKey(entry.getKey()), ignored -> new ConcurrentHashMap<>());
                        entry.getValue().forEach((name, page) ->
                        {
                            if (name != null && page != null)
                            {
                                pages.putIfAbsent(name, page);
                            }
                        });
                    }
                }
            }
            collectionCacheLoaded = true;
            queueSnapshot(true, client.getGameState() == GameState.LOGGED_IN);
        });
    }

    void writeAtomic(String fileName, String contents)
    {
        try
        {
            Filepath directory = getPluginDirectory();
            directory.createDirectories();
            Filepath target = directory.join(fileName);
            Filepath temp = directory.join(fileName + ".tmp");
            temp.write(
                contents,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);

            try
            {
                temp.moveTo(
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException ex)
            {
                temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException ex)
        {
            LOG.log(Level.WARNING, "Unable to write OSRS Goal Generator bridge file " + fileName, ex);
        }
    }

    private static class CollectionItemSnapshot
    {
        int item_id;
        String name;
        boolean obtained;

        CollectionItemSnapshot()
        {
        }

        CollectionItemSnapshot(int itemId, String name, boolean obtained)
        {
            this.item_id = itemId;
            this.name = name;
            this.obtained = obtained;
        }
    }

    private static class CollectionPageSnapshot
    {
        String updated_at = "";
        List<CollectionItemSnapshot> items = new ArrayList<>();
    }

    private static class CollectionCache
    {
        Map<String, Map<String, CollectionPageSnapshot>> accounts = new LinkedHashMap<>();
    }
}
