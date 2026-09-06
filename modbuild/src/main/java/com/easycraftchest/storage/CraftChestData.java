/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.logging.LogUtils
 *  com.mojang.serialization.DataResult
 *  com.mojang.serialization.DynamicOps
 *  net.minecraft.core.HolderLookup$Provider
 *  net.minecraft.core.RegistryAccess
 *  net.minecraft.core.RegistryAccess$Frozen
 *  net.minecraft.core.component.DataComponentMap
 *  net.minecraft.core.component.DataComponentPatch
 *  net.minecraft.core.component.DataComponents
 *  net.minecraft.core.registries.BuiltInRegistries
 *  net.minecraft.nbt.CompoundTag
 *  net.minecraft.nbt.ListTag
 *  net.minecraft.nbt.NbtOps
 *  net.minecraft.nbt.StringTag
 *  net.minecraft.nbt.Tag
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.server.MinecraftServer
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.Items
 *  net.minecraft.world.level.ItemLike
 *  net.neoforged.neoforge.server.ServerLifecycleHooks
 *  org.slf4j.Logger
 */
package com.easycraftchest.storage;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

public class CraftChestData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<String, Long> storedItems = new HashMap<String, Long>();
    private final List<String> itemOrder = new ArrayList<String>();
    /** 每个物品种类最近一次"增/减"时间(毫秒),用于客户端按最后修改排序。 */
    private final Map<String, Long> lastModified = new HashMap<String, Long>();
    private final Map<String, Tag> fullItemDataCache = new ConcurrentHashMap<String, Tag>();
    private HolderLookup.Provider registryAccess = null;
    private String searchFilter = "";
    private int currentPage = 0;
    private static final int ITEMS_PER_PAGE = 54;
    private boolean changed = false;
    private final Map<String, String> itemNameCache = new ConcurrentHashMap<String, String>();
    private final Map<String, List<Map.Entry<String, Long>>> filteredCache = new ConcurrentHashMap<String, List<Map.Entry<String, Long>>>();
    private String lastSearchFilter = "";
    private long cachedTotalItemCount = -1L;
    private boolean cacheValid = false;
    private volatile boolean hasChangedFlag = true;
    private long changeCounter = 0L;
    /** 合成历史上限(一页 6×9=54)。按"每个物品一条(去重)"维护,超限删最旧。 */
    public static final int MAX_SYNTH_HISTORY = 54;
    /** 按方块记录的合成历史:itemKey → 最近一次合成者信息。顺序无关,取历史时按 timeMs 倒序。 */
    private final Map<String, SynthesisHistoryEntry> synthesisHistory = new HashMap<String, SynthesisHistoryEntry>();

    public long addItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0L;
        }
        String itemKey = this.getItemKeyInstance(stack);
        this.cacheItemData(itemKey, stack);
        long currentCount = this.storedItems.getOrDefault(itemKey, 0L);
        long newCount = currentCount + (long)stack.getCount();
        if (newCount < 0L) {
            newCount = Long.MAX_VALUE;
        }
        this.storedItems.put(itemKey, newCount);
        if (currentCount == 0L) {
            this.itemOrder.add(itemKey);
        }
        this.touch(itemKey);
        this.setChanged();
        return stack.getCount();
    }

    public long addItem(String itemKey, long amount) {
        if (itemKey == null || itemKey.isEmpty() || amount <= 0L) {
            return 0L;
        }
        long currentCount = this.storedItems.getOrDefault(itemKey, 0L);
        long newCount = Long.MAX_VALUE - currentCount < amount ? Long.MAX_VALUE : currentCount + amount;
        this.storedItems.put(itemKey, newCount);
        if (currentCount == 0L) {
            this.itemOrder.add(itemKey);
        }
        this.touch(itemKey);
        this.setChanged();
        return amount;
    }

    public ItemStack removeItem(String itemKey, long amount) {
        long currentCount = this.storedItems.getOrDefault(itemKey, 0L);
        if (currentCount <= 0L || amount <= 0L) {
            return ItemStack.EMPTY;
        }
        long removeAmount = Math.min(amount, currentCount);
        ItemStack result = this.createItemStackFromKey(itemKey);
        if (result.isEmpty()) {
            return ItemStack.EMPTY;
        }
        result.setCount((int)Math.min(removeAmount, Integer.MAX_VALUE));
        long newCount = currentCount - removeAmount;
        if (newCount <= 0L) {
            this.storedItems.remove(itemKey);
            this.itemOrder.remove(itemKey);
        } else {
            this.storedItems.put(itemKey, newCount);
        }
        this.touch(itemKey);
        this.setChanged();
        return result;
    }

    public long getItemCount(String itemKey) {
        return this.storedItems.getOrDefault(itemKey, 0L);
    }

    public boolean hasItem(String itemKey) {
        return this.storedItems.containsKey(itemKey) && this.storedItems.get(itemKey) > 0L;
    }

    public Map<String, Long> getAllItems() {
        return new HashMap<String, Long>(this.storedItems);
    }

    public List<Map.Entry<String, Long>> getFilteredItems() {
        return this.getFilteredItems("");
    }

    public List<Map.Entry<String, Long>> getFilteredItems(String filter) {
        String normalizedFilter = filter == null ? "" : filter.toLowerCase();
        List<Map.Entry<String, Long>> cached = this.filteredCache.get(normalizedFilter);
        if (cached != null) {
            return new ArrayList<Map.Entry<String, Long>>(cached);
        }
        ArrayList<Map.Entry<String, Long>> result = new ArrayList<Map.Entry<String, Long>>();
        for (String key : this.itemOrder) {
            Long count = this.storedItems.get(key);
            if (count == null || count <= 0L || !normalizedFilter.isEmpty() && !this.matchesSearchCached(key, normalizedFilter)) continue;
            result.add(new AbstractMap.SimpleEntry<String, Long>(key, count));
        }
        this.filteredCache.put(normalizedFilter, new ArrayList(result));
        return result;
    }

    public List<Map.Entry<String, Long>> getCurrentPageItems() {
        List<Map.Entry<String, Long>> filteredItems = this.getFilteredItems();
        int startIndex = this.currentPage * 105;
        int endIndex = Math.min(startIndex + 105, filteredItems.size());
        if (startIndex >= filteredItems.size()) {
            return new ArrayList<Map.Entry<String, Long>>();
        }
        return filteredItems.subList(startIndex, endIndex);
    }

    public void setSearchFilter(String filter) {
        String newFilter = filter.toLowerCase();
        LOGGER.info("setSearchFilter() \u8c03\u7528: \u65e7\u8fc7\u6ee4\u5668='{}', \u65b0\u8fc7\u6ee4\u5668='{}', \u5f53\u524d\u9875\u9762={}", new Object[]{this.searchFilter, newFilter, this.currentPage});
        if (!this.searchFilter.equals(newFilter)) {
            LOGGER.info("\u641c\u7d22\u8fc7\u6ee4\u5668\u6539\u53d8\uff0c\u91cd\u7f6e\u9875\u9762: {} -> 0", (Object)this.currentPage);
            this.searchFilter = newFilter;
            this.currentPage = 0;
        } else {
            LOGGER.info("\u641c\u7d22\u8fc7\u6ee4\u5668\u672a\u6539\u53d8\uff0c\u4fdd\u6301\u9875\u9762: {}", (Object)this.currentPage);
        }
    }

    public String getSearchFilter() {
        return this.searchFilter;
    }

    public int getCurrentPage() {
        return this.currentPage;
    }

    public int getMaxPage() {
        List<Map.Entry<String, Long>> filteredItems = this.getFilteredItems();
        if (filteredItems.isEmpty()) {
            return 0;
        }
        return (filteredItems.size() - 1) / 105;
    }

    public void nextPage() {
        if (this.currentPage < this.getMaxPage()) {
            ++this.currentPage;
        }
    }

    public void previousPage() {
        if (this.currentPage > 0) {
            --this.currentPage;
        }
    }

    public void setPage(int page) {
        LOGGER.info("setPage() \u8c03\u7528: \u8bf7\u6c42\u9875\u9762={}, \u5f53\u524d\u9875\u9762={}", (Object)page, (Object)this.currentPage);
        List<Map.Entry<String, Long>> filteredItems = this.getFilteredItems();
        int maxPage = Math.max(0, (filteredItems.size() - 1) / 105);
        LOGGER.info("\u9875\u9762\u8303\u56f4\u68c0\u67e5: \u8fc7\u6ee4\u7269\u54c1\u6570={}, \u6700\u5927\u9875={}, \u8bf7\u6c42\u9875={}", new Object[]{filteredItems.size(), maxPage, page});
        int oldPage = this.currentPage;
        if (page < 0) {
            this.currentPage = 0;
            LOGGER.info("\u9875\u9762\u5c0f\u4e8e0\uff0c\u8bbe\u7f6e\u4e3a0: {} -> {}", (Object)page, (Object)this.currentPage);
        } else if (page > maxPage) {
            this.currentPage = maxPage;
            LOGGER.info("\u9875\u9762\u8d85\u8fc7\u6700\u5927\u503c\uff0c\u8bbe\u7f6e\u4e3a\u6700\u5927\u9875: {} -> {}", (Object)page, (Object)this.currentPage);
        } else {
            this.currentPage = page;
            LOGGER.info("\u9875\u9762\u8bbe\u7f6e\u6210\u529f: {} -> {}", (Object)oldPage, (Object)this.currentPage);
        }
        if (this.currentPage != oldPage) {
            this.cacheValid = false;
            LOGGER.info("\u9875\u9762\u6539\u53d8\uff0c\u6e05\u9664\u7f13\u5b58");
        }
    }

    public void clear() {
        this.storedItems.clear();
        this.itemOrder.clear();
        this.lastModified.clear();
        this.searchFilter = "";
        this.currentPage = 0;
        this.setChanged();
        this.validateDataIntegrity();
    }

    /** 该存储键是否仍指向一个"真实存在"的物品(排除已卸载模组后解析成空气/屏障的孤儿)。 */
    private static boolean isItemStillPresent(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        try {
            String base = key.indexOf('#') >= 0 ? key.substring(0, key.indexOf('#')) : key;
            ResourceLocation loc = ResourceLocation.parse(base);
            net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(loc);
            return item != null && item != Items.AIR && item != Items.BARRIER;
        }
        catch (Exception e) {
            return false;
        }
    }

    /** 清理孤儿条目(物品已不存在的键),避免出现"屏障且拿不出来"。 */
    private void pruneOrphaned() {
        this.itemOrder.removeIf(k -> !this.storedItems.containsKey(k));
        this.fullItemDataCache.keySet().removeIf(k -> !this.storedItems.containsKey(k));
        this.lastModified.keySet().removeIf(k -> !this.storedItems.containsKey(k));
    }

    /** 记录某物品种类"最后被改动"的时间。 */
    private void touch(String itemKey) {
        if (itemKey != null) {
            this.lastModified.put(itemKey, System.currentTimeMillis());
        }
    }

    /** 现存每种物品的最后修改时间快照(按种类→毫秒;无记录的记为 0)。 */
    public Map<String, Long> getLastModifiedMap() {
        HashMap<String, Long> out = new HashMap<String, Long>();
        for (String key : this.storedItems.keySet()) {
            Long t = this.lastModified.get(key);
            out.put(key, t == null ? 0L : t);
        }
        return out;
    }

    private void validateDataIntegrity() {
        try {
            this.itemOrder.removeIf(key -> !this.storedItems.containsKey(key));
            this.fullItemDataCache.entrySet().removeIf(entry -> {
                String key = (String)entry.getKey();
                return !this.storedItems.containsKey(key);
            });
            for (String key2 : this.storedItems.keySet()) {
                if (this.itemOrder.contains(key2)) continue;
                this.itemOrder.add(key2);
            }
        }
        catch (Exception e) {
            System.err.println("Error during data integrity validation: " + e.getMessage());
        }
    }

    public void setChanged() {
        this.changed = true;
        this.hasChangedFlag = true;
        ++this.changeCounter;
        this.cacheValid = false;
        this.cachedTotalItemCount = -1L;
        this.filteredCache.clear();
    }

    public long getChangeCounter() {
        return this.changeCounter;
    }

    public boolean hasChanged() {
        return this.hasChangedFlag;
    }

    public void clearChanged() {
        this.hasChangedFlag = false;
        this.changed = false;
    }

    /** 记录一次成功合成:该物品若已有历史则刷新(移动为最新),超过上限删掉最旧一条。合成者用服务端时钟。 */
    public void recordSynthesis(String itemKey, String playerName, String playerUuid, int count) {
        this.insertSynthesisHistory(itemKey, playerName, playerUuid, System.currentTimeMillis(), count);
    }

    /** 插入/刷新一条历史,保留传入时间戳;超过上限删最旧。加载存档与实时记录共用。 */
    private void insertSynthesisHistory(String itemKey, String playerName, String playerUuid, long timeMs, int count) {
        if (itemKey == null || itemKey.isEmpty()) {
            return;
        }
        SynthesisHistoryEntry entry = new SynthesisHistoryEntry(itemKey, playerName == null ? "" : playerName, playerUuid == null ? "" : playerUuid, timeMs, count);
        this.synthesisHistory.put(itemKey, entry);
        if (this.synthesisHistory.size() > CraftChestData.MAX_SYNTH_HISTORY) {
            this.evictOldestSynthesis();
        }
    }

    private void evictOldestSynthesis() {
        if (this.synthesisHistory.size() <= CraftChestData.MAX_SYNTH_HISTORY) {
            return;
        }
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, SynthesisHistoryEntry> e : this.synthesisHistory.entrySet()) {
            long t = e.getValue() == null ? 0L : e.getValue().timeMs;
            if (t >= oldest) continue;
            oldest = t;
            oldestKey = e.getKey();
        }
        if (oldestKey != null) {
            this.synthesisHistory.remove(oldestKey);
        }
    }

    /** 合成历史(最新在前,最多 MAX_SYNTH_HISTORY 条)。 */
    public List<SynthesisHistoryEntry> getSynthesisHistory() {
        ArrayList<SynthesisHistoryEntry> list = new ArrayList<SynthesisHistoryEntry>(this.synthesisHistory.values());
        list.sort(Comparator.comparingLong((SynthesisHistoryEntry e) -> e.timeMs).reversed());
        if (list.size() > CraftChestData.MAX_SYNTH_HISTORY) {
            return new ArrayList<SynthesisHistoryEntry>(list.subList(0, CraftChestData.MAX_SYNTH_HISTORY));
        }
        return list;
    }

    public static class SynthesisHistoryEntry {
        public final String itemKey;
        public final String playerName;
        public final String playerUuid;
        public final long timeMs;
        /** 那次合成的次数(合成次数输入框的值)。 */
        public final int count;

        public SynthesisHistoryEntry(String itemKey, String playerName, String playerUuid, long timeMs, int count) {
            this.itemKey = itemKey;
            this.playerName = playerName;
            this.playerUuid = playerUuid;
            this.timeMs = timeMs;
            this.count = count;
        }
    }

    /*
     * Enabled force condition propagation
     * Lifted jumps to return sites
     */
    public void optimizeCache() {
        try {
            this.fullItemDataCache.entrySet().removeIf(entry -> {
                String key = (String)entry.getKey();
                return !this.storedItems.containsKey(key) || this.storedItems.get(key) <= 0L;
            });
            if (this.fullItemDataCache.size() <= 1000) return;
        }
        catch (Exception e) {
            System.err.println("Error during cache optimization: " + e.getMessage());
        }
    }

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", 2);
        CompoundTag itemsTag = new CompoundTag();
        for (Map.Entry<String, Long> entry : this.storedItems.entrySet()) {
            itemsTag.putLong(entry.getKey(), entry.getValue().longValue());
        }
        tag.put("StoredItems", (Tag)itemsTag);
        ListTag orderTag = new ListTag();
        for (String key : this.itemOrder) {
            orderTag.add(StringTag.valueOf(key));
        }
        tag.put("ItemOrder", (Tag)orderTag);
        CompoundTag compoundTag = new CompoundTag();
        for (Map.Entry<String, Tag> entry : this.fullItemDataCache.entrySet()) {
            try {
                if (!(entry.getValue() instanceof CompoundTag)) continue;
                compoundTag.put(entry.getKey(), (Tag)((CompoundTag)entry.getValue()));
            }
            catch (Exception e) {
                System.err.println("Failed to save cached item data for key: " + entry.getKey() + ", error: " + e.getMessage());
            }
        }
        tag.put("FullDataCache", (Tag)compoundTag);
        if (!this.lastModified.isEmpty()) {
            CompoundTag lmTag = new CompoundTag();
            for (Map.Entry<String, Long> e : this.lastModified.entrySet()) {
                if (e.getValue() == null) continue;
                lmTag.putLong(e.getKey(), e.getValue().longValue());
            }
            tag.put("LastModified", (Tag)lmTag);
        }
        tag.putString("SearchFilter", this.searchFilter);
        tag.putInt("CurrentPage", this.currentPage);
        // 合成历史(每物品一条,记录最近一次合成者+服务端时间)
        if (!this.synthesisHistory.isEmpty()) {
            ListTag historyTag = new ListTag();
            for (SynthesisHistoryEntry e : this.getSynthesisHistory()) {
                CompoundTag h = new CompoundTag();
                h.putString("I", e.itemKey);
                h.putString("N", e.playerName == null ? "" : e.playerName);
                h.putString("U", e.playerUuid == null ? "" : e.playerUuid);
                h.putLong("T", e.timeMs);
                h.putInt("C", e.count);
                historyTag.add((Tag)h);
            }
            tag.put("SynthHistory", (Tag)historyTag);
        }
        return tag;
    }

    public void fromNBT(CompoundTag tag) {
        this.storedItems.clear();
        this.itemOrder.clear();
        this.fullItemDataCache.clear();
        this.lastModified.clear();
        this.synthesisHistory.clear();
        int version = tag.getInt("version");
        if (version > 2) {
            System.err.println("Warning: Loading data from newer version (" + version + "), some data may be lost.");
        }
        if (tag.contains("StoredItems")) {
            CompoundTag itemsTag = tag.getCompound("StoredItems");
            for (String key : itemsTag.getAllKeys()) {
                try {
                    // 跳过"物品已不存在的孤儿"(如它所属的模组被移除),不加载成屏障
                    if (!CraftChestData.isItemStillPresent(key)) {
                        continue;
                    }
                    this.storedItems.put(key, itemsTag.getLong(key));
                }
                catch (Exception e) {
                    System.err.println("Failed to load item data for key: " + key + ", error: " + e.getMessage());
                }
            }
        }
        if (tag.contains("ItemOrder")) {
            ListTag orderTag = tag.getList("ItemOrder", 8);
            for (int i = 0; i < orderTag.size(); ++i) {
                try {
                    this.itemOrder.add(orderTag.getString(i));
                    continue;
                }
                catch (Exception e) {
                    LOGGER.error("Failed to load item order at index: " + i + ", error: " + e.getMessage());
                }
            }
        }
        if (tag.contains("FullDataCache")) {
            CompoundTag fullDataCacheTag = tag.getCompound("FullDataCache");
            for (String key : fullDataCacheTag.getAllKeys()) {
                try {
                    this.fullItemDataCache.put(key, (Tag)fullDataCacheTag.getCompound(key));
                }
                catch (Exception e) {
                    LOGGER.error("Failed to load cached item data for key: " + key + ", error: " + e.getMessage());
                }
            }
        }
        if (tag.contains("LastModified")) {
            CompoundTag lmTag = tag.getCompound("LastModified");
            for (String key : lmTag.getAllKeys()) {
                try {
                    this.lastModified.put(key, lmTag.getLong(key));
                }
                catch (Exception e) {
                    // ignore single corrupt entry
                }
            }
        }
        this.searchFilter = tag.getString("SearchFilter");
        this.currentPage = tag.getInt("CurrentPage");
        if (tag.contains("SynthHistory")) {
            ListTag historyTag = tag.getList("SynthHistory", 10);
            for (int i = 0; i < historyTag.size(); ++i) {
                try {
                    CompoundTag h = historyTag.getCompound(i);
                    String itemKey = h.getString("I");
                    if (itemKey.isEmpty()) continue;
                    int count = h.contains("C") ? h.getInt("C") : 1;
                    this.insertSynthesisHistory(itemKey, h.getString("N"), h.getString("U"), h.getLong("T"), count);
                }
                catch (Exception e) {
                    LOGGER.error("Failed to load synthesis history entry at index: " + i + ", error: " + e.getMessage());
                }
            }
        }
        this.pruneOrphaned();
        this.setChanged();
    }

    public void setRegistryAccess(HolderLookup.Provider registryAccess) {
        this.registryAccess = registryAccess;
    }

    private void cacheItemData(String itemKey, ItemStack stack) {
        if (CraftChestData.hasSpecialComponents(stack)) {
            try {
                HolderLookup.Provider lookup = this.registryAccess != null ? this.registryAccess : RegistryAccess.EMPTY;
                DataResult result = ItemStack.CODEC.encodeStart(lookup.createSerializationContext(NbtOps.INSTANCE), stack);
                if (result.isSuccess()) {
                    Tag fullData = (Tag)result.getOrThrow();
                    this.fullItemDataCache.put(itemKey, fullData);
                } else {
                    CompoundTag fallbackData = new CompoundTag();
                    stack.save((HolderLookup.Provider)lookup, (Tag)fallbackData);
                    this.fullItemDataCache.put(itemKey, (Tag)fallbackData);
                }
            }
            catch (Exception e) {
                LOGGER.error("Failed to cache item data for key: " + itemKey + ", error: " + e.getMessage());
                try {
                    HolderLookup.Provider lookup = this.registryAccess;
                    if (lookup == null) {
                        try {
                            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                            lookup = server != null ? server.registryAccess() : RegistryAccess.EMPTY;
                        }
                        catch (Exception serverException) {
                            lookup = RegistryAccess.EMPTY;
                        }
                    }
                    CompoundTag fallbackData = new CompoundTag();
                    stack.save(lookup, (Tag)fallbackData);
                    this.fullItemDataCache.put(itemKey, (Tag)fallbackData);
                }
                catch (Exception exception) {
                    // empty catch block
                }
            }
        }
    }

    public static String getItemKey(ItemStack stack) {
        return CraftChestData.getItemKey(stack, null);
    }

    public static String getItemKey(ItemStack stack, HolderLookup.Provider registryAccess) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!CraftChestData.hasSpecialComponents(stack)) {
            return itemId.toString();
        }
        try {
            DataComponentPatch patch = stack.getComponentsPatch();
            HolderLookup.Provider lookup = registryAccess != null ? registryAccess : RegistryAccess.EMPTY;
            DataResult result = DataComponentPatch.CODEC.encodeStart(lookup.createSerializationContext(NbtOps.INSTANCE), patch);
            if (result.isSuccess()) {
                Tag patchNbt = (Tag)result.getOrThrow();
                String specialKey = itemId.toString() + "#patch:" + Math.abs(patchNbt.toString().hashCode());
                return specialKey;
            }
            String fallbackKey = itemId.toString() + "#fallback:" + Math.abs(stack.hashCode());
            return fallbackKey;
        }
        catch (Exception e) {
            String fallbackKey = itemId.toString() + "#fallback:" + Math.abs(stack.hashCode());
            return fallbackKey;
        }
    }

    private String getItemKeyInstance(ItemStack stack) {
        return CraftChestData.getItemKey(stack, this.registryAccess);
    }

    public static boolean hasSpecialComponents(ItemStack stack) {
        DataComponentMap currentComponents;
        if (stack.isEmpty()) {
            return false;
        }
        if (!stack.getComponentsPatch().isEmpty()) {
            return true;
        }
        DataComponentMap defaultComponents = stack.getItem().components();
        if (!defaultComponents.equals((Object)(currentComponents = stack.getComponents()))) {
            return true;
        }
        boolean hasEnchantments = stack.has(DataComponents.ENCHANTMENTS);
        boolean hasSpecial = hasEnchantments || stack.has(DataComponents.CUSTOM_DATA) || stack.has(DataComponents.CUSTOM_NAME) || stack.has(DataComponents.LORE) || stack.has(DataComponents.DAMAGE) || stack.has(DataComponents.UNBREAKABLE) || stack.has(DataComponents.REPAIR_COST) || stack.has(DataComponents.POTION_CONTENTS) || stack.has(DataComponents.SUSPICIOUS_STEW_EFFECTS) || stack.has(DataComponents.STORED_ENCHANTMENTS) || stack.has(DataComponents.DYED_COLOR) || stack.has(DataComponents.FIREWORK_EXPLOSION) || stack.has(DataComponents.FIREWORKS) || stack.has(DataComponents.WRITTEN_BOOK_CONTENT) || stack.has(DataComponents.WRITABLE_BOOK_CONTENT) || stack.has(DataComponents.MAP_COLOR) || stack.has(DataComponents.MAP_ID) || stack.has(DataComponents.BUNDLE_CONTENTS) || stack.has(DataComponents.CHARGED_PROJECTILES) || stack.has(DataComponents.INTANGIBLE_PROJECTILE) || stack.has(DataComponents.FOOD) || stack.has(DataComponents.TOOL);
        return hasSpecial;
    }

    public ItemStack createItemStackFromKey(String key) {
        try {
            String[] parts;
            ResourceLocation itemId;
            ItemStack baseStack;
            Tag fullData;
            if ((key.contains("#patch:") || key.contains("#fallback:") || key.contains("#special:")) && this.fullItemDataCache.containsKey(key) && (fullData = this.fullItemDataCache.get(key)) != null) {
                HolderLookup.Provider lookup = this.registryAccess;
                if (lookup == null) {
                    try {
                        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                        lookup = server != null ? server.registryAccess() : RegistryAccess.EMPTY;
                    }
                    catch (Exception serverException) {
                        lookup = RegistryAccess.EMPTY;
                    }
                }
                try {
                    ItemStack restored;
                    DataResult result = ItemStack.CODEC.parse((DynamicOps)lookup.createSerializationContext((DynamicOps)NbtOps.INSTANCE), (Object)fullData);
                    if (result.isSuccess() && !(restored = (ItemStack)result.getOrThrow()).isEmpty()) {
                        return restored;
                    }
                }
                catch (Exception codecException) {
                    try {
                        CompoundTag compoundTag;
                        ItemStack restoredStack;
                        if (fullData instanceof CompoundTag && !(restoredStack = ItemStack.parseOptional((HolderLookup.Provider)lookup, (CompoundTag)(compoundTag = (CompoundTag)fullData))).isEmpty()) {
                            return restoredStack;
                        }
                    }
                    catch (Exception exception) {
                        // empty catch block
                    }
                }
            }
            if ((baseStack = new ItemStack((ItemLike)BuiltInRegistries.ITEM.get(itemId = ResourceLocation.parse((String)(parts = key.split("#", 2))[0])))).getItem() == Items.AIR || baseStack.getItem() == Items.BARRIER) {
                return ItemStack.EMPTY;
            }
            return baseStack;
        }
        catch (Exception e) {
            System.err.println("Failed to create ItemStack from key: " + key + ", error: " + e.getMessage());
            return ItemStack.EMPTY;
        }
    }

    public ItemStack getItemTemplate(String itemKey) {
        return this.createItemStackFromKey(itemKey);
    }

    private boolean matchesSearchCached(String itemKey, String searchTerm) {
        try {
            String itemName = this.itemNameCache.get(itemKey);
            if (itemName == null) {
                ItemStack stack = this.createItemStackFromKey(itemKey);
                itemName = stack.getHoverName().getString().toLowerCase();
                this.itemNameCache.put(itemKey, itemName);
            }
            return itemName.contains(searchTerm);
        }
        catch (Exception e) {
            return false;
        }
    }

    private boolean matchesSearch(String itemKey, String searchTerm) {
        return this.matchesSearchCached(itemKey, searchTerm);
    }

    public int getTotalItemTypes() {
        return this.storedItems.size();
    }

    public boolean isEmpty() {
        return this.storedItems.isEmpty();
    }

    public long getTotalItemCount() {
        if (this.cachedTotalItemCount >= 0L) {
            return this.cachedTotalItemCount;
        }
        this.cachedTotalItemCount = this.storedItems.values().stream().mapToLong(Long::longValue).sum();
        return this.cachedTotalItemCount;
    }

    public Map<String, Tag> getCachedItemData() {
        return new HashMap<String, Tag>(this.fullItemDataCache);
    }

    public void setCachedItemData(Map<String, Tag> cachedData) {
        this.fullItemDataCache.clear();
        this.fullItemDataCache.putAll(cachedData);
    }

    public void restoreItemDataCache(String itemKey, Tag itemData) {
        if (itemKey != null && itemData != null) {
            this.fullItemDataCache.put(itemKey, itemData);
        }
    }

    public void restoreItemOrder(ListTag orderTag) {
        this.itemOrder.clear();
        for (int i = 0; i < orderTag.size(); ++i) {
            try {
                this.itemOrder.add(orderTag.getString(i));
                continue;
            }
            catch (Exception e) {
                System.err.println("Failed to restore item order at index: " + i + ", error: " + e.getMessage());
            }
        }
    }

    public Map<String, Tag> getFullItemDataCache() {
        return new HashMap<String, Tag>(this.fullItemDataCache);
    }

    public List<String> getItemOrder() {
        return new ArrayList<String>(this.itemOrder);
    }
}

