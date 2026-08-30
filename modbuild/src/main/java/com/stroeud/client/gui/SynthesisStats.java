package com.stroeud.client.gui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 合成历史统计(客户端):记录每个物品总共成功合成过多少次。
 * 数据**按存档独立**保存——存到当前单机存档的文件夹下(<存档>/storageandoneclicksynthesis_synthesis_stats.json),
 * 每个世界各自一份,重启/切换存档都各自保留。
 */
public final class SynthesisStats {
    private static final Map<String, Map<String, Integer>> SAVE_CACHE = new HashMap<String, Map<String, Integer>>();
    private static final Gson GSON = new Gson();
    private static String currentSavePath = null;
    private static Map<String, Integer> currentCounts = new HashMap<String, Integer>();

    private SynthesisStats() {
    }

    public static int getCount(Item item) {
        if (item == null) {
            return 0;
        }
        return getCount(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    public static int getCount(String itemId) {
        if (itemId == null) {
            return 0;
        }
        ensureCurrentSave();
        return currentCounts.getOrDefault(itemId, 0);
    }

    public static void recordSynthesis(Item item) {
        if (item == null) {
            return;
        }
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        ensureCurrentSave();
        currentCounts.merge(id, 1, Integer::sum);
        SynthesisStats.save();
    }

    /** 确保当前存档对应的统计已加载(切换存档时自动切换)。 */
    private static void ensureCurrentSave() {
        String path = currentSavePath();
        if (path == null) {
            return; // 非单机(远程服务器)无本地存档,仅内存记录
        }
        if (!path.equals(currentSavePath)) {
            currentSavePath = path;
            currentCounts = SAVE_CACHE.computeIfAbsent(path, SynthesisStats::load);
        }
    }

    /** 当前单机存档的文件夹路径;远程服务器返回 null。 */
    private static String currentSavePath() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getSingleplayerServer() != null) {
                Path world = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
                return world.toAbsolutePath().toString();
            }
        }
        catch (Exception e) {
            // ignore
        }
        return null;
    }

    private static File statsFile(String savePath) {
        return new File(new File(savePath, "serverconfig"), "storageandoneclicksynthesis_synthesis_stats.json");
    }

    private static Map<String, Integer> load(String savePath) {
        HashMap<String, Integer> counts = new HashMap<String, Integer>();
        try {
            File file = SynthesisStats.statsFile(savePath);
            if (file.exists()) {
                FileReader reader = new FileReader(file);
                Type type = new TypeToken<Map<String, Integer>>() {
                }.getType();
                Map<String, Integer> loaded = GSON.fromJson(reader, type);
                reader.close();
                if (loaded != null) {
                    counts.putAll(loaded);
                }
            }
        }
        catch (Exception e) {
            // ignore corrupt/missing stats file
        }
        return counts;
    }

    private static void save() {
        if (currentSavePath == null) {
            return;
        }
        try {
            File file = SynthesisStats.statsFile(currentSavePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileWriter writer = new FileWriter(file);
            GSON.toJson(currentCounts, writer);
            writer.close();
        }
        catch (Exception e) {
            // ignore save errors
        }
    }
}
