package com.osrsgoalgenerator;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Deterministic worker/client queues exercise races without a live account or disk writes. */
public class BridgeLifecycleTest
{
    private final List<Runnable> workers = new ArrayList<>();
    private final List<Runnable> clientTasks = new ArrayList<>();
    private final MemoryPlugin plugin = new MemoryPlugin();
    private String name = "Test One";
    private int xp = 100;
    private GameState gameState = GameState.LOGGED_IN;
    private boolean onClientThread;

    static class MemoryPlugin extends OsrsGoalGeneratorPlugin
    {
        String cache = "{}";
        final Map<String, String> files = new LinkedHashMap<>();
        @Override String readCollectionCache() { return cache; }
        @Override void writeAtomic(String file, String value) { files.put(file, value); }
    }

    @Before public void setUp() throws Exception
    {
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> method.getName().equals("getName") ? name : 100);
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) ->
            {
                assertTrue("Client API accessed outside client thread: " + method.getName(), onClientThread);
                switch (method.getName())
                {
                    case "getLocalPlayer": return player;
                    case "getGameState": return gameState;
                    case "getVarpValue": return 0;
                    case "getVarbitValue": return 0;
                    case "getSkillExperience": return xp;
                    case "getRealSkillLevel": return 1;
                    case "getTotalLevel": return 24;
                    case "getOverallExperience": return (long) xp * 24;
                    default: throw new AssertionError(method.getName());
                }
            });
        ScheduledExecutorService executor = (ScheduledExecutorService) Proxy.newProxyInstance(
            ScheduledExecutorService.class.getClassLoader(), new Class<?>[]{ScheduledExecutorService.class},
            (proxy, method, args) -> { workers.add((Runnable) args[0]); return null; });
        inject("client", client);
        inject("executor", executor);
        inject("gson", new Gson());
        inject("config", new OsrsGoalGeneratorConfig() {});
        inject("clientThread", new ClientThread()
        {
            @Override public void invokeLater(Runnable task) { clientTasks.add(task); }
        });
    }

    private void inject(String name, Object value) throws Exception
    {
        Field field = OsrsGoalGeneratorPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    private void call(String name, Class<?>[] types, Object... args) throws Exception
    {
        Method method = OsrsGoalGeneratorPlugin.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(plugin, args);
    }

    private void client(Runnable task)
    {
        onClientThread = true;
        try { task.run(); } finally { onClientThread = false; }
    }

    private void drain()
    {
        while (!workers.isEmpty() || !clientTasks.isEmpty())
        {
            while (!workers.isEmpty()) workers.remove(0).run();
            while (!clientTasks.isEmpty()) client(clientTasks.remove(0));
        }
    }

    private JsonObject snapshot()
    {
        return new Gson().fromJson(plugin.files.get("sync.json"), JsonObject.class);
    }

    private void snapshotNow() throws Exception
    {
        onClientThread = true;
        try { call("queueSnapshot", new Class<?>[]{boolean.class, boolean.class}, true, true); }
        finally { onClientThread = false; }
    }

    @Test public void startupRestoresOnlyCurrentAccount() throws Exception
    {
        plugin.cache = "{\"accounts\":{\"test one\":{\"Brutus\":{\"items\":[]}},\"test two\":{\"Vorkath\":{\"items\":[]}}}}";
        plugin.startUp();
        drain();
        assertTrue(snapshot().get("connected").getAsBoolean());
        JsonObject pages = snapshot().getAsJsonObject("collection_log").getAsJsonObject("pages");
        assertTrue(pages.has("Brutus"));
        assertFalse(pages.has("Vorkath"));
        xp = 150;
        snapshotNow();
        drain();
        assertEquals(50, snapshot().getAsJsonObject("session").getAsJsonObject("xp_gained").get("Thieving").getAsInt());
        name = "Test Two";
        snapshotNow();
        drain();
        pages = snapshot().getAsJsonObject("collection_log").getAsJsonObject("pages");
        assertFalse(pages.has("Brutus"));
        assertTrue(pages.has("Vorkath"));
        assertEquals(0, snapshot().getAsJsonObject("session").getAsJsonObject("xp_gained").get("Thieving").getAsInt());
    }

    @Test public void diariesWaitForInitializationAndClearOnLogoutAndAccountChange() throws Exception
    {
        plugin.startUp(); drain();
        assertEquals("initializing", snapshot().getAsJsonObject("diaries").get("status").getAsString());
        client(() -> plugin.onGameTick(new GameTick()));
        client(() -> plugin.onGameTick(new GameTick()));
        snapshotNow(); drain();
        assertEquals(0, snapshot().getAsJsonObject("diaries").getAsJsonObject("regions").size());
        client(() -> plugin.onGameTick(new GameTick())); drain();
        assertEquals(12, snapshot().getAsJsonObject("diaries").getAsJsonObject("regions").size());
        name = "Test Two"; snapshotNow(); drain();
        assertEquals("initializing", snapshot().getAsJsonObject("diaries").get("status").getAsString());
        gameState = GameState.LOGIN_SCREEN;
        GameStateChanged event = new GameStateChanged(); event.setGameState(gameState);
        client(() -> plugin.onGameStateChanged(event)); drain();
        assertEquals("disconnected", snapshot().getAsJsonObject("diaries").get("status").getAsString());
        assertEquals(0, snapshot().getAsJsonObject("diaries").getAsJsonObject("regions").size());
    }

    @Test public void disableDuringLoadCannotPublishConnectedSnapshot()
    {
        plugin.startUp();
        plugin.shutDown();
        drain();
        assertFalse(snapshot().get("connected").getAsBoolean());
    }

    @Test public void staleStartupAndShutdownCannotOverrideReenable()
    {
        plugin.startUp();
        plugin.shutDown();
        plugin.startUp();
        drain();
        assertTrue(snapshot().get("connected").getAsBoolean());
    }

    @Test public void shutdownReadsClientOnlyOnClientThread()
    {
        plugin.startUp();
        drain();
        plugin.shutDown();
        drain();
        assertFalse(snapshot().get("connected").getAsBoolean());
        assertEquals("shutdown", snapshot().getAsJsonObject("session").get("last_event").getAsString());
    }

    @Test public void olderSyncWorkerCannotOverwriteNewerSnapshot() throws Exception
    {
        plugin.startUp();
        drain();
        xp = 200;
        snapshotNow();
        xp = 300;
        snapshotNow();
        workers.remove(1).run();
        workers.remove(0).run();
        assertEquals(300, snapshot().getAsJsonObject("skills").getAsJsonObject("Thieving").get("xp").getAsInt());
    }

    @Test public void olderCacheWorkerCannotOverwriteNewerCache() throws Exception
    {
        plugin.startUp();
        drain();
        call("persistCollectionCache", new Class<?>[]{});
        Field accounts = OsrsGoalGeneratorPlugin.class.getDeclaredField("collectionPagesByPlayer");
        accounts.setAccessible(true);
        ((Map<?, ?>) accounts.get(plugin)).clear();
        call("persistCollectionCache", new Class<?>[]{});
        workers.remove(1).run();
        String newest = plugin.files.get("collection-cache.json");
        workers.remove(0).run();
        assertEquals(newest, plugin.files.get("collection-cache.json"));
        assertEquals("{\"accounts\":{}}", newest);
    }

    @Test public void malformedCacheDoesNotPreventStartup()
    {
        plugin.cache = "not json {";
        plugin.startUp();
        drain();
        assertTrue(snapshot().get("connected").getAsBoolean());
    }

    @Test public void bossTotalsRejectPlayerChatDeduplicateAndClearOnAccountChange() throws Exception
    {
        plugin.startUp(); drain();
        ChatMessage event = new ChatMessage();
        event.setMessage("Your Vorkath kill count is: <col=ff0000>350</col>.");
        event.setType(ChatMessageType.PUBLICCHAT);
        client(() -> plugin.onChatMessage(event)); snapshotNow(); drain();
        assertEquals(0, snapshot().getAsJsonObject("boss_counts").size());
        event.setType(ChatMessageType.GAMEMESSAGE);
        client(() -> plugin.onChatMessage(event)); drain();
        client(() -> plugin.onChatMessage(event)); drain();
        assertEquals(350, snapshot().getAsJsonObject("boss_counts").get("Vorkath").getAsInt());
        event.setMessage("Your Vorkath kill count is: 349.");
        client(() -> plugin.onChatMessage(event)); drain();
        assertEquals(350, snapshot().getAsJsonObject("boss_counts").get("Vorkath").getAsInt());
        name = "Test Two"; snapshotNow(); drain();
        assertEquals(0, snapshot().getAsJsonObject("boss_counts").size());
    }
}
