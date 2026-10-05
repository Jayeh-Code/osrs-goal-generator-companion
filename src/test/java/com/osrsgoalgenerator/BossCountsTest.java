package com.osrsgoalgenerator;
import org.junit.Test;
import static org.junit.Assert.*;
public class BossCountsTest
{
    @Test public void explicitTotalsAndModes()
    {
        assertEquals(Integer.valueOf(1234), BossCounts.parse("Your Vorkath kill count is: 1,234.").getValue());
        assertEquals("Barrows Chests", BossCounts.parse("Your Barrows chest count is: 12.").getKey());
        assertEquals("Theatre of Blood: Hard Mode", BossCounts.parse("Your completion count for Theatre of Blood: Hard Mode is: 7.").getKey());
    }
    @Test public void unsupportedOrAmbiguousMessagesAreIgnored()
    {
        assertNull(BossCounts.parse("Your Vorkath is: 25."));
        assertNull(BossCounts.parse("Your Unknown kill count is: 25."));
        assertNull(BossCounts.parse("Someone: Your Vorkath kill count is: 25."));
        assertNull(BossCounts.parse("Your Vorkath kill count is: 99999999999999."));
        assertNull(BossCounts.parse("Your Vorkath kill count is: 1,00."));
    }
}
