package com.osrsgoalgenerator;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class OsrsGoalGeneratorPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(OsrsGoalGeneratorPlugin.class);
        RuneLite.main(args);
    }
}
