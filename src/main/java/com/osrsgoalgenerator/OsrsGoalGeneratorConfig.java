package com.osrsgoalgenerator;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(OsrsGoalGeneratorConfig.GROUP)
public interface OsrsGoalGeneratorConfig extends Config
{
    String GROUP = "osrs-goal-generator-companion";

    @ConfigItem(
        keyName = "syncCollectionLog",
        name = "Sync Collection Log",
        description = "Capture Collection Log pages as you open them and expose them to the local desktop app"
    )
    default boolean syncCollectionLog()
    {
        return true;
    }

    @ConfigItem(
        keyName = "heartbeatSeconds",
        name = "Bridge heartbeat (seconds)",
        description = "How often the plugin refreshes the local sync file while logged in (effective range: 2-10 seconds)"
    )
    default int heartbeatSeconds()
    {
        return 5;
    }
}
