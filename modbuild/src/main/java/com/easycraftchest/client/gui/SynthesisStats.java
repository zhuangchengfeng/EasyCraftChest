package com.easycraftchest.client.gui;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 合成历史统计 + 手动置顶(客户端,全局,存 config 文件夹)。
 * - counts:每个物品成功合成次数。
 * - lastSynth:每个物品最近一次成功合成的时间戳(用于"合成历史"按最新倒序)。
 * - pins:手动置顶的物品 ID 列表(顺序 = 置顶先后,越早置顶越靠前)。
 * 数据全局一份,不随存档分开。JSON:config/easycraftchest_synthesis_stats.json
 */
public final class SynthesisStats {
    /** 合成历史最多保留的条数(一页 6×9=54)。达到上限后,再合成新物品会删掉最旧一条。 */
    public static final int MAX_HISTORY = 54;
    private static final Map<String, Integer> COUNTS = new HashMap<String, Integer>();
    private static final Map<String, Long> LAST_SYNTH = new HashMap<String, Long>();
    private static final List<String> PIN_ORDER = new ArrayList<String>();
    private static final Gson GSON = new Gson();
    private static final File FILE = FMLPaths.CONFIGDIR.get().resolve("easycraftchest_synthesis_stats.json").toFile();
    private static long lastSaveTime = 0L;

    private SynthesisStats() {
    }

    static {
        SynthesisStats.load();
    }

    public static int getCount(Item item) {
        if (item == null) {
            return 0;
        }
        return SynthesisStats.getCount(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    public static int getCount(String itemId) {
        return itemId == null ? 0 : COUNTS.getOrDefault(itemId, 0);
    }

    public static void recordSynthesis(Item item) {
        if (item == null) {
            return;
        }
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        COUNTS.merge(id, 1, Integer::sum);
        // 新合成视为"最近一次",放最前(时间戳为最新)。若已达历史上限,删掉最旧的一条。
        LAST_SYNTH.put(id, System.currentTimeMillis());
        SynthesisStats.trimHistory();
        // 节流落盘(至少间隔 2 秒),避免高频合成时每一下都写文件;切屏/关屏时再 flush 一次。
        long now = System.currentTimeMillis();
        if (now - SynthesisStats.lastSaveTime >= 2000L) {
            SynthesisStats.save();
        }
    }

    /** 合成历史只保留最新 MAX_HISTORY 条:超出则删掉时间戳最旧(最早)的一条。 */
    private static void trimHistory() {
        while (LAST_SYNTH.size() > SynthesisStats.MAX_HISTORY) {
            String oldestId = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, Long> e : LAST_SYNTH.entrySet()) {
                long t = e.getValue() == null ? 0L : e.getValue();
                if (t < oldestTime) {
                    oldestTime = t;
                    oldestId = e.getKey();
                }
            }
            if (oldestId == null) {
                break;
            }
            LAST_SYNTH.remove(oldestId);
        }
    }

    /** 强制写盘(打开/关闭存储界面、切到"合成历史"面板时调用,保证跨会话保留)。 */
    public static void flush() {
        SynthesisStats.save();
    }

    /** 合成历史:返回按最近一次成功合成时间倒序(最新在前)的物品 ID,最多 MAX_HISTORY 条;未合成过则空。 */
    public static List<String> getRecentSynthesisIds() {
        ArrayList<String> ids = new ArrayList<String>(LAST_SYNTH.keySet());
        ids.sort((a, b) -> {
            long ta = SynthesisStats.lastTimeOrZero(a);
            long tb = SynthesisStats.lastTimeOrZero(b);
            if (ta != tb) {
                return Long.compare(tb, ta);
            }
            return a.compareTo(b);
        });
        if (ids.size() > SynthesisStats.MAX_HISTORY) {
            return new ArrayList<String>(ids.subList(0, SynthesisStats.MAX_HISTORY));
        }
        return ids;
    }

    private static long lastTimeOrZero(String itemId) {
        Long v = itemId == null ? null : LAST_SYNTH.get(itemId);
        return v == null ? 0L : v;
    }

    public static boolean isPinned(Item item) {
        return item != null && SynthesisStats.isPinned(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    public static boolean isPinned(String itemId) {
        return itemId != null && PIN_ORDER.contains(itemId);
    }

    /** 置顶顺序索引(0=最早置顶);未置顶返回 -1。 */
    public static int getPinIndex(String itemId) {
        return itemId == null ? -1 : PIN_ORDER.indexOf(itemId);
    }

    public static void togglePin(Item item) {
        if (item == null) {
            return;
        }
        SynthesisStats.togglePin(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    public static void togglePin(String itemId) {
        if (itemId == null) {
            return;
        }
        if (PIN_ORDER.contains(itemId)) {
            PIN_ORDER.remove(itemId);
        } else {
            PIN_ORDER.add(itemId); // 追加到置顶列表末尾
        }
        SynthesisStats.save();
    }

    public static List<String> getPinnedOrder() {
        return new ArrayList<String>(PIN_ORDER);
    }

    private static void load() {
        try {
            if (FILE.exists()) {
                FileReader reader = new FileReader(FILE);
                StatsData data = GSON.fromJson(reader, StatsData.class);
                reader.close();
                if (data != null) {
                    if (data.counts != null) {
                        COUNTS.putAll(data.counts);
                    }
                    if (data.lastSynth != null) {
                        LAST_SYNTH.putAll(data.lastSynth);
                    }
                    if (data.pins != null) {
                        PIN_ORDER.addAll(data.pins);
                    }
                }
            }
            // 旧版本可能存了超过 54 条,读盘后压缩到上限。
            SynthesisStats.trimHistory();
        }
        catch (Exception e) {
            // ignore corrupt/missing stats file
        }
    }

    private static void save() {
        try {
            StatsData data = new StatsData();
            data.counts = new HashMap<String, Integer>(COUNTS);
            data.lastSynth = new HashMap<String, Long>(LAST_SYNTH);
            data.pins = new ArrayList<String>(PIN_ORDER);
            File parent = FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileWriter writer = new FileWriter(FILE);
            GSON.toJson(data, writer);
            writer.close();
            SynthesisStats.lastSaveTime = System.currentTimeMillis();
        }
        catch (Exception e) {
            // ignore save errors
        }
    }

    private static final class StatsData {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        Map<String, Long> lastSynth = new HashMap<String, Long>();
        List<String> pins = new ArrayList<String>();
    }
}
