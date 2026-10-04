package com.osrsgoalgenerator;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.IntUnaryOperator;

// Task data attribution: ALL_DIARY_MAPPING_LICENSE.txt.
final class AllDiaryTasks
{
    static final class Task
    {
        String region, tier, title, kind, id;
        int var, bit, threshold, count_varbit;
        boolean done(IntUnaryOperator varps, IntUnaryOperator varbits)
        {
            return kind.equals("varp") ? ((varps.applyAsInt(var) >>> bit) & 1) != 0 : varbits.applyAsInt(var) > threshold;
        }
    }
    static final Task[] TASKS;
    static
    {
        try (InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(AllDiaryTasks.class.getResourceAsStream("/diary-tasks.json")), StandardCharsets.UTF_8))
        { TASKS = new Gson().fromJson(reader, Task[].class); }
        catch (Exception ex) { throw new IllegalStateException("Diary mappings unavailable", ex); }
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> capture(IntUnaryOperator varps, IntUnaryOperator varbits)
    {
        Map<String,Object> regions = new LinkedHashMap<>();
        for (Task task : TASKS)
        {
            Map<String,Object> tiers = (Map<String,Object>) regions.computeIfAbsent(task.region, k -> new LinkedHashMap<>());
            Map<String,Object> tier = (Map<String,Object>) tiers.computeIfAbsent(task.tier, k -> {
                Map<String,Object> value = new LinkedHashMap<>();
                value.put("count_raw",varbits.applyAsInt(task.count_varbit));
                value.put("tasks",new ArrayList<Map<String,Object>>());
                return value;
            });
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("id",task.id); item.put("title",task.title);
            item.put("bit_set",task.done(varps,varbits));
            ((List<Map<String,Object>>)tier.get("tasks")).add(item);
        }
        for(Object region:regions.values())
        {
            for(Object raw:((Map<?,?>)region).values())
            {
                Map<String,Object> tier=(Map<String,Object>)raw;
                List<Map<String,Object>> tasks=(List<Map<String,Object>>)tier.get("tasks");
                int count=(int)tasks.stream().filter(t -> Boolean.TRUE.equals(t.get("bit_set"))).count();
                boolean matches=count==((Number)tier.get("count_raw")).intValue();
                tier.put("decoded_count",count);tier.put("mapped_total",tasks.size());
                tier.put("mapping_status",matches?"count_matched_pending_journal_check":"count_mismatch");
                for(Map<String,Object> task:tasks) task.put("state",matches?(Boolean.TRUE.equals(task.get("bit_set"))?"complete_candidate":"incomplete_candidate"):"unknown");
            }
        }
        return regions;
    }
}
