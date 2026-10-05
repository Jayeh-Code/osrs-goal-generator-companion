package com.osrsgoalgenerator;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Explicit game totals only. Never derive counts from loot or NPC deaths. */
final class BossCounts
{
    private static final Pattern COUNT = Pattern.compile("^Your (?:completion count for |subdued |completed )?(.+?) (?:(?:kill|harvest|completion|success) count |count )?is: ([0-9]+(?:,[0-9]{3})*)(?:[.!].*)?$");
    private static final Map<String, String> NAMES = new HashMap<>();
    static
    {
        try (InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(BossCounts.class.getResourceAsStream("/boss-names.json")), StandardCharsets.UTF_8))
        {
            for (String name : new Gson().fromJson(reader, String[].class)) NAMES.put(name.toLowerCase(Locale.ROOT), name);
            NAMES.put("barrows chest", "Barrows Chests");
        }
        catch (Exception ex) { throw new IllegalStateException("Boss names unavailable", ex); }
    }
    static Map.Entry<String, Integer> parse(String message)
    {
        Matcher matcher = COUNT.matcher(message);
        if (!matcher.matches()) return null;
        // Require an explicit count marker; reject generic messages such as 'Your X is: N'.
        if (!message.contains("count ") && !message.startsWith("Your subdued ") && !message.startsWith("Your completed ")) return null;
        String name = NAMES.get(matcher.group(1).toLowerCase(Locale.ROOT));
        if (name == null) return null;
        try { return new AbstractMap.SimpleImmutableEntry<>(name, Integer.parseInt(matcher.group(2).replace(",", ""))); }
        catch (NumberFormatException ex) { return null; }
    }
}
