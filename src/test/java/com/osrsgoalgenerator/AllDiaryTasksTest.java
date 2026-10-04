package com.osrsgoalgenerator;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AllDiaryTasksTest
{
    @Test public void mappingsCoverTwelveRegionsAndFortyEightTiers()
    {
        Set<String> ids=new HashSet<>(),tiers=new HashSet<>();
        Map<Integer,Integer> counts=new HashMap<>();
        for(AllDiaryTasks.Task task:AllDiaryTasks.TASKS)
        {
            assertTrue(ids.add(task.id));tiers.add(task.region+task.tier);
            counts.merge(task.count_varbit,1,Integer::sum);
        }
        assertEquals(492,ids.size());assertEquals(48,tiers.size());assertEquals(48,counts.size());
        Map<String,Object> data=AllDiaryTasks.capture(id -> -1,id -> counts.getOrDefault(id,5));
        assertEquals(12,data.size());
        for(Object region:data.values())for(Object tier:((Map<?,?>)region).values())
            assertEquals("count_matched_pending_journal_check",((Map<?,?>)tier).get("mapping_status"));
    }
    @Test public void partialKaramjaCountersAreNotComplete()
    {
        int checked=0;
        for(AllDiaryTasks.Task task:AllDiaryTasks.TASKS)if(task.kind.equals("varbit") && task.threshold==4)
        {
            assertFalse(task.done(id -> 0,id -> 4));
            assertTrue(task.done(id -> 0,id -> 5));checked++;
        }
        assertEquals(3,checked);
    }
    @Test public void mismatchedTierReportsUnknown()
    {
        Map<String,Object> data=AllDiaryTasks.capture(id -> -1,id -> 0);
        Map<?,?> tier=(Map<?,?>)((Map<?,?>)data.get("Varrock")).get("easy");
        assertEquals("count_mismatch",tier.get("mapping_status"));
        for(Object task:(List<?>)tier.get("tasks"))assertEquals("unknown",((Map<?,?>)task).get("state"));
    }
}
