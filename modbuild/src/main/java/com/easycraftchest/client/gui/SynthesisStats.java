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
 * - counts:每个物品成功合成次数(用于金色边框标记)。
 * - pins:手动置顶的物品 ID 列表(顺序 = 置顶先后,越早置顶越靠前)。
 * 数据全局一份,不随存档分开。JSON:config/easycraftchest_synthesis_stats.json
 */
public final class SynthesisStats {
    private static final Map<String, Integer> COUNTS = new HashMap<String, Integer>();
    private static final List<String> PIN_ORDER = new ArrayList<String>();
    private static final Gson GSON = new Gson();
    private static final File FILE = FMLPaths.CONFIGDIR.get().resolve("easycraftchest_synthesis_stats.json").toFile();

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
        COUNTS.merge(BuiltInRegistries.ITEM.getKey(item).toString(), 1, Integer::sum);
        // 已注释:每次合成成功都自动写盘 config/easycraftchest_synthesis_stats.json。
        // 次数仍记在内存(本次会话内金色边框有效),但不再随每次合成落盘;
        // 该 json 现在只在你手动置顶/取消置顶(A 键)时才写盘。
        // SynthesisStats.save();
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
                    if (data.pins != null) {
                        PIN_ORDER.addAll(data.pins);
                    }
                }
            }
        }
        catch (Exception e) {
            // ignore corrupt/missing stats file
        }
    }

    private static void save() {
        try {
            StatsData data = new StatsData();
            data.counts = new HashMap<String, Integer>(COUNTS);
            data.pins = new ArrayList<String>(PIN_ORDER);
            File parent = FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileWriter writer = new FileWriter(FILE);
            GSON.toJson(data, writer);
            writer.close();
        }
        catch (Exception e) {
            // ignore save errors
        }
    }

    private static final class StatsData {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        List<String> pins = new ArrayList<String>();
    }
}
