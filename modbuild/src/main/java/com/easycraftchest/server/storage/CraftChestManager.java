/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.logging.LogUtils
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.HolderLookup$Provider
 *  net.minecraft.nbt.CompoundTag
 *  net.minecraft.nbt.ListTag
 *  net.minecraft.nbt.Tag
 *  net.minecraft.server.MinecraftServer
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.level.saveddata.SavedData
 *  net.minecraft.world.level.saveddata.SavedData$Factory
 *  net.neoforged.neoforge.server.ServerLifecycleHooks
 *  org.slf4j.Logger
 */
package com.easycraftchest.server.storage;

import com.mojang.logging.LogUtils;
import com.easycraftchest.network.NetworkManager;
import com.easycraftchest.network.StorageNetworkHandler;
import com.easycraftchest.storage.CraftChestData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

public class CraftChestManager
extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_NAME = "craft_chest_data";
    private static final int ITEMS_PER_PAGE = 54;
    private final Map<BlockPos, CraftChestData> storageMap = new ConcurrentHashMap<BlockPos, CraftChestData>();
    private final Map<UUID, BlockPos> playerOpenStorage = new ConcurrentHashMap<UUID, BlockPos>();
    private final Map<UUID, ItemStack[]> playerInventoryCache = new ConcurrentHashMap<UUID, ItemStack[]>();
    private final Map<UUID, ItemStack> playerCarriedItemCache = new ConcurrentHashMap<UUID, ItemStack>();
    private final Map<UUID, String> playerLastSearchFilter = new ConcurrentHashMap<UUID, String>();
    private final Map<UUID, Integer> playerLastPage = new ConcurrentHashMap<UUID, Integer>();
    private final Map<UUID, Long> playerLastUpdateTime = new ConcurrentHashMap<UUID, Long>();
    private final Map<UUID, Long> playerLastSeenChangeCounter = new ConcurrentHashMap<UUID, Long>();
    private final Map<BlockPos, Long> recentlyDroppedStorageBlockItems = new ConcurrentHashMap<BlockPos, Long>();
    private static final long RECENT_DROP_TTL_MS = 10000L;
    private final Map<UUID, List<Runnable>> pendingOperations = new ConcurrentHashMap<UUID, List<Runnable>>();
    private final Map<UUID, Long> lastBatchTime = new ConcurrentHashMap<UUID, Long>();
    private static final long BATCH_DELAY_MS = 20L;

    public static CraftChestManager get(ServerLevel level) {
        return (CraftChestManager)level.getDataStorage().computeIfAbsent(new SavedData.Factory<CraftChestManager>(CraftChestManager::new, CraftChestManager::load), DATA_NAME);
    }

    public static CraftChestManager load(CompoundTag tag, HolderLookup.Provider provider) {
        CraftChestManager manager = new CraftChestManager();
        if (tag.contains("storages", 9)) {
            ListTag storageList = tag.getList("storages", 10);
            for (int i = 0; i < storageList.size(); ++i) {
                CompoundTag storageTag = storageList.getCompound(i);
                BlockPos pos = new BlockPos(storageTag.getInt("x"), storageTag.getInt("y"), storageTag.getInt("z"));
                CraftChestData data = new CraftChestData();
                data.setRegistryAccess(provider);
                data.fromNBT(storageTag.getCompound("data"));
                manager.storageMap.put(pos, data);
            }
        }
        return manager;
    }

    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag storageList = new ListTag();
        for (Map.Entry<BlockPos, CraftChestData> entry : this.storageMap.entrySet()) {
            CompoundTag storageTag = new CompoundTag();
            BlockPos pos = entry.getKey();
            storageTag.putInt("x", pos.getX());
            storageTag.putInt("y", pos.getY());
            storageTag.putInt("z", pos.getZ());
            CraftChestData storage = entry.getValue();
            storage.setRegistryAccess(provider);
            storageTag.put("data", (Tag)storage.toNBT());
            storageList.add(storageTag);
        }
        tag.put("storages", (Tag)storageList);
        return tag;
    }

    public void openStorage(ServerPlayer player, BlockPos pos) {
        UUID playerId = player.getUUID();
        this.playerOpenStorage.put(playerId, pos);
        this.cachePlayerInventory(player);
        CraftChestData storage = this.getOrCreateStorage(pos, player.serverLevel());
        this.playerLastSearchFilter.remove(playerId);
        this.playerLastPage.remove(playerId);
        this.playerLastUpdateTime.remove(playerId);
        this.playerLastSeenChangeCounter.remove(playerId);
    }

    public void closeStorage(ServerPlayer player) {
        UUID playerId = player.getUUID();
        this.playerOpenStorage.remove(playerId);
        this.playerInventoryCache.remove(playerId);
        this.playerCarriedItemCache.remove(playerId);
        this.playerLastSearchFilter.remove(playerId);
        this.playerLastPage.remove(playerId);
        this.playerLastUpdateTime.remove(playerId);
        this.playerLastSeenChangeCounter.remove(playerId);
        this.pendingOperations.remove(playerId);
        this.lastBatchTime.remove(playerId);
        this.setDirty();
    }

    private void pruneExpiredDroppedMarkers(long now) {
        this.recentlyDroppedStorageBlockItems.entrySet().removeIf(e -> now - (Long)e.getValue() > 10000L);
    }

    public void pruneDisconnectedPlayers(Set<UUID> onlinePlayerIds) {
        if (onlinePlayerIds == null) {
            return;
        }
        for (UUID playerId : new HashSet<UUID>(this.playerOpenStorage.keySet())) {
            if (onlinePlayerIds.contains(playerId)) continue;
            this.playerOpenStorage.remove(playerId);
            this.playerInventoryCache.remove(playerId);
            this.playerCarriedItemCache.remove(playerId);
            this.playerLastSearchFilter.remove(playerId);
            this.playerLastPage.remove(playerId);
            this.playerLastUpdateTime.remove(playerId);
            this.playerLastSeenChangeCounter.remove(playerId);
            this.pendingOperations.remove(playerId);
            this.lastBatchTime.remove(playerId);
        }
    }

    private void addToBatch(UUID playerId, Runnable operation) {
        long currentTime = System.currentTimeMillis();
        List operations = this.pendingOperations.computeIfAbsent(playerId, k -> new ArrayList());
        operations.add(operation);
        Long lastTime = this.lastBatchTime.get(playerId);
        if (lastTime == null || currentTime - lastTime >= 20L) {
            this.executeBatch(playerId);
        }
    }

    private void executeBatch(UUID playerId) {
        List<Runnable> operations = this.pendingOperations.remove(playerId);
        if (operations != null && !operations.isEmpty()) {
            for (Runnable operation : operations) {
                operation.run();
            }
            this.lastBatchTime.put(playerId, System.currentTimeMillis());
        }
    }

    public void flushAllBatches() {
        for (UUID playerId : new HashSet<UUID>(this.pendingOperations.keySet())) {
            this.executeBatch(playerId);
        }
    }

    public void handleItemOperation(ServerPlayer player, StorageNetworkHandler.ItemOperationPacket packet) {
        UUID playerId = player.getUUID();
        StorageNetworkHandler.OperationType operation = packet.getType();
        if (operation == StorageNetworkHandler.OperationType.CLOSE) {
            this.closeStorage(player);
            return;
        }
        BlockPos storagePos = this.playerOpenStorage.get(playerId);
        if (storagePos == null) {
            return;
        }
        CraftChestData storage = this.storageMap.get(storagePos);
        if (storage == null) {
            return;
        }
        if (storage != null) {
            storage.setRegistryAccess((HolderLookup.Provider)player.serverLevel().registryAccess());
        }
        String itemKey = packet.getItemKey();
        long amount = packet.getAmount();
        switch (operation) {
            case PUT: {
                this.handlePutOperation(player, storage, itemKey, amount, packet);
                break;
            }
            case TAKE: {
                this.handleTakeOperation(player, storage, itemKey, amount, packet);
                break;
            }
            case SEARCH: {
                this.handleSearchOperation(player, storage, packet);
                break;
            }
            case PAGE_CHANGE: {
                this.handlePageChangeOperation(player, storage, packet);
                break;
            }
            case SYNC_REQUEST: {
                this.handleSyncRequestOperation(player, storage, packet);
                break;
            }
            case DROP: {
                this.handleDropOperation(player, storage, packet);
            }
        }
        if (operation == StorageNetworkHandler.OperationType.PUT || operation == StorageNetworkHandler.OperationType.DROP) {
            this.cachePlayerInventory(player);
            this.sendPlayerInventoryToClient(player);
        } else if (operation == StorageNetworkHandler.OperationType.TAKE) {
            this.cachePlayerInventoryOnly(player);
            this.sendPlayerInventoryToClient(player);
        }
        this.setDirty();
    }

    private void handlePutOperation(ServerPlayer player, CraftChestData storage, String itemKey, long amount, StorageNetworkHandler.ItemOperationPacket packet) {
        ItemStack newCarriedItem;
        UUID playerId = player.getUUID();
        if (packet.isShiftClick()) {
            int containerSlotId = packet.getSlotId();
            int playerSlotId = this.convertContainerSlotToPlayerSlot(containerSlotId);
            if (playerSlotId < 0 || playerSlotId >= player.getInventory().getContainerSize()) {
                this.sendOperationResult(player, false, "Invalid slot");
                return;
            }
            ItemStack slotItem = player.getInventory().getItem(playerSlotId);
            if (slotItem.isEmpty()) {
                this.sendOperationResult(player, false, "No item in slot");
                return;
            }
            ItemStack toMove = slotItem.copy();
            player.getInventory().setItem(playerSlotId, ItemStack.EMPTY);
            storage.addItem(toMove);
            this.setDirty();
            this.sendStorageDataToPlayer(player, storage, packet.getTargetPage(), packet.getSearchFilter());
            this.sendOperationResult(player, true, "Successfully put " + toMove.getCount() + " items");
            return;
        }
        ItemStack serverCarried = player.containerMenu.getCarried();
        if (serverCarried.isEmpty()) {
            return;
        }
        ItemStack clientCarried = packet.getCarriedItem();
        if (!(clientCarried.isEmpty() || ItemStack.isSameItemSameComponents((ItemStack)serverCarried, (ItemStack)clientCarried) && serverCarried.getCount() == clientCarried.getCount())) {
            this.sendOperationResult(player, false, "Item mismatch");
            return;
        }
        long actualAmount = Math.min(amount, (long)serverCarried.getCount());
        if (actualAmount <= 0L) {
            return;
        }
        ItemStack itemToAdd = serverCarried.copy();
        itemToAdd.setCount((int)actualAmount);
        storage.addItem(itemToAdd);
        this.setDirty();
        int remainingCount = serverCarried.getCount() - (int)actualAmount;
        ItemStack itemStack = newCarriedItem = remainingCount <= 0 ? ItemStack.EMPTY : serverCarried.copy();
        if (!newCarriedItem.isEmpty()) {
            newCarriedItem.setCount(remainingCount);
        }
        this.playerCarriedItemCache.put(playerId, newCarriedItem);
        player.containerMenu.setCarried(newCarriedItem);
        this.sendStorageDataToPlayer(player, storage, packet.getTargetPage(), packet.getSearchFilter());
        this.sendOperationResult(player, true, "Successfully put " + actualAmount + " items");
    }

    private void handleTakeOperation(ServerPlayer player, CraftChestData storage, String itemKey, long amount, StorageNetworkHandler.ItemOperationPacket packet) {
        ItemStack newCarried;
        long availableAmount = storage.getItemCount(itemKey);
        if (availableAmount <= 0L) {
            this.sendOperationResult(player, false, "Item not found");
            return;
        }
        if (packet.isShiftClick()) {
            this.handleFillInventory(player, storage, itemKey, availableAmount, packet);
            return;
        }
        if (amount <= 0L) {
            this.sendOperationResult(player, false, "Invalid amount");
            return;
        }
        ItemStack serverCarried = player.containerMenu.getCarried();
        ItemStack clientCarried = packet.getCarriedItem();
        if (!(serverCarried.isEmpty() && clientCarried.isEmpty() || serverCarried.isEmpty() == clientCarried.isEmpty() && ItemStack.isSameItemSameComponents((ItemStack)serverCarried, (ItemStack)clientCarried) && serverCarried.getCount() == clientCarried.getCount())) {
            this.sendOperationResult(player, false, "Item mismatch");
            return;
        }
        ItemStack template = storage.createItemStackFromKey(itemKey);
        if (template.isEmpty()) {
            this.sendOperationResult(player, false, "Invalid item");
            return;
        }
        int maxStackSize = Math.max(1, template.getMaxStackSize());
        long requested = Math.min(amount, availableAmount);
        long canTake = requested = Math.min(requested, (long)maxStackSize);
        if (!serverCarried.isEmpty()) {
            if (!ItemStack.isSameItemSameComponents((ItemStack)serverCarried, (ItemStack)template)) {
                this.sendOperationResult(player, false, "Hand is full");
                return;
            }
            int space = maxStackSize - serverCarried.getCount();
            if (space <= 0) {
                this.sendOperationResult(player, false, "Hand is full");
                return;
            }
            canTake = Math.min(canTake, (long)space);
        }
        if (canTake <= 0L) {
            this.sendOperationResult(player, false, "Hand is full");
            return;
        }
        ItemStack taken = storage.removeItem(itemKey, canTake);
        if (taken.isEmpty()) {
            this.sendOperationResult(player, false, "Invalid item");
            return;
        }
        if (serverCarried.isEmpty()) {
            newCarried = taken;
        } else {
            newCarried = serverCarried.copy();
            newCarried.setCount(serverCarried.getCount() + (int)canTake);
        }
        this.playerCarriedItemCache.put(player.getUUID(), newCarried.copy());
        player.containerMenu.setCarried(newCarried);
        this.setDirty();
        this.sendStorageDataToPlayer(player, storage, packet.getTargetPage(), packet.getSearchFilter());
        this.sendOperationResult(player, true, "Took " + canTake + " items");
    }

    private void handleFillInventory(ServerPlayer player, CraftChestData storage, String itemKey, long availableAmount, StorageNetworkHandler.ItemOperationPacket packet) {
        ItemStack templateStack = storage.createItemStackFromKey(itemKey);
        if (templateStack.isEmpty()) {
            this.sendOperationResult(player, false, "Invalid item");
            return;
        }
        int maxStackSize = templateStack.getMaxStackSize();
        long totalTaken = 0L;
        for (int i = 0; i < 36; ++i) {
            int currentCount;
            int canAdd;
            ItemStack slotStack = player.getInventory().getItem(i);
            if (slotStack.isEmpty()) {
                long takeAmount = Math.min(availableAmount - totalTaken, (long)maxStackSize);
                if (takeAmount <= 0L) break;
                ItemStack newStack = templateStack.copy();
                newStack.setCount((int)takeAmount);
                player.getInventory().setItem(i, newStack);
                totalTaken += takeAmount;
            } else if (ItemStack.isSameItemSameComponents((ItemStack)slotStack, (ItemStack)templateStack) && (canAdd = maxStackSize - (currentCount = slotStack.getCount())) > 0) {
                long addAmount = Math.min((long)canAdd, availableAmount - totalTaken);
                if (addAmount <= 0L) break;
                slotStack.setCount(currentCount + (int)addAmount);
                totalTaken += addAmount;
            }
            if (totalTaken >= availableAmount) break;
        }
        if (totalTaken > 0L) {
            storage.removeItem(itemKey, totalTaken);
            this.setDirty();
            this.sendStorageDataToPlayer(player, storage, packet.getTargetPage(), packet.getSearchFilter());
            this.sendOperationResult(player, true, "Filled inventory with " + totalTaken + " items");
        } else {
            this.sendOperationResult(player, false, "No space in inventory");
        }
    }

    private void handleSearchOperation(ServerPlayer player, CraftChestData storage, StorageNetworkHandler.ItemOperationPacket packet) {
        this.sendStorageDataToPlayer(player, storage, packet.getTargetPage(), packet.getSearchFilter());
    }

    private void handlePageChangeOperation(ServerPlayer player, CraftChestData storage, StorageNetworkHandler.ItemOperationPacket packet) {
        this.sendStorageDataToPlayer(player, storage, packet.getTargetPage(), packet.getSearchFilter());
    }

    private void handleSyncRequestOperation(ServerPlayer player, CraftChestData storage, StorageNetworkHandler.ItemOperationPacket packet) {
        // LOGGER.debug("handleSyncRequestOperation() \u5f00\u59cb: \u73a9\u5bb6={}", (Object)player.getName().getString());
        // LOGGER.debug("\u5ba2\u6237\u7aef\u8bf7\u6c42\u9875\u7801={}, \u5ba2\u6237\u7aef\u641c\u7d22\u8fc7\u6ee4\u5668='{}'", (Object)packet.getTargetPage(), (Object)packet.getSearchFilter());
        this.sendStorageDataToPlayer(player, storage, packet.getTargetPage(), packet.getSearchFilter());
        this.sendPlayerInventoryToClient(player);
    }

    private void handleDropOperation(ServerPlayer player, CraftChestData storage, StorageNetworkHandler.ItemOperationPacket packet) {
        ItemStack serverCarried = player.containerMenu.getCarried();
        if (serverCarried.isEmpty()) {
            this.sendOperationResult(player, false, "No item to drop", StorageNetworkHandler.OperationType.DROP);
            return;
        }
        ItemStack clientCarried = packet.getCarriedItem();
        if (!(clientCarried.isEmpty() && serverCarried.isEmpty() || clientCarried.isEmpty() == serverCarried.isEmpty() && ItemStack.isSameItemSameComponents((ItemStack)clientCarried, (ItemStack)serverCarried) && clientCarried.getCount() == serverCarried.getCount())) {
            this.sendOperationResult(player, false, "Item mismatch", StorageNetworkHandler.OperationType.DROP);
            return;
        }
        ItemStack toDrop = serverCarried.copy();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        this.playerCarriedItemCache.put(player.getUUID(), ItemStack.EMPTY);
        player.drop(toDrop, false);
        this.sendOperationResult(player, true, "Dropped " + toDrop.getCount() + " items", StorageNetworkHandler.OperationType.DROP);
    }

    public void sendStorageDataToPlayer(ServerPlayer player, CraftChestData storage, int currentPage, String searchFilter) {
        boolean needsUpdate;
        UUID playerId = player.getUUID();
        long currentTime = System.currentTimeMillis();
        String normalizedFilter = searchFilter == null ? "" : searchFilter.toLowerCase();
        String lastSearchFilter = this.playerLastSearchFilter.get(playerId);
        Integer lastPage = this.playerLastPage.get(playerId);
        Long lastUpdateTime = this.playerLastUpdateTime.get(playerId);
        Long lastSeenChangeCounter = this.playerLastSeenChangeCounter.get(playerId);
        long currentChangeCounter = storage.getChangeCounter();
        boolean bl = needsUpdate = lastSearchFilter == null || !lastSearchFilter.equals(normalizedFilter) || lastPage == null || !lastPage.equals(currentPage) || lastSeenChangeCounter == null || !lastSeenChangeCounter.equals(currentChangeCounter);
        if (!needsUpdate) {
            return;
        }
        List<Map.Entry<String, Long>> allItems = storage.getFilteredItems("");
        int totalCount = allItems.size();
        LinkedHashMap<String, Long> pageItems = new LinkedHashMap<String, Long>();
        for (Map.Entry<String, Long> entry : allItems) {
            pageItems.put(entry.getKey(), entry.getValue());
        }
        Map<String, Tag> rawCache = storage.getFullItemDataCache();
        LinkedHashMap<String, CompoundTag> pageCachedData = new LinkedHashMap<String, CompoundTag>();
        for (String key : pageItems.keySet()) {
            Tag tag = rawCache.get(key);
            if (!(tag instanceof CompoundTag)) continue;
            CompoundTag compoundTag = (CompoundTag)tag;
            pageCachedData.put(key, compoundTag);
        }
        long totalItemCount = storage.getTotalItemCount();
        int totalTypes = storage.getTotalItemTypes();
        int maxPage = Math.max(0, (totalCount - 1) / 54);
        int actualCurrentPage = Math.max(0, Math.min(currentPage, maxPage));
        StorageNetworkHandler.StorageDataPacket packet = new StorageNetworkHandler.StorageDataPacket(pageItems, pageCachedData, actualCurrentPage, maxPage, normalizedFilter, totalItemCount, totalTypes, storage.getLastModifiedMap());
        NetworkManager.sendToPlayer(player, packet);
        this.playerLastSearchFilter.put(playerId, normalizedFilter);
        this.playerLastPage.put(playerId, actualCurrentPage);
        this.playerLastUpdateTime.put(playerId, currentTime);
        this.playerLastSeenChangeCounter.put(playerId, currentChangeCounter);
    }

    public void sendPlayerInventoryToClient(ServerPlayer player) {
        UUID playerId = player.getUUID();
        ItemStack[] inventory = this.playerInventoryCache.get(playerId);
        ItemStack carriedItem = player.containerMenu.getCarried();
        if (inventory != null) {
            StorageNetworkHandler.PlayerInventoryPacket packet = new StorageNetworkHandler.PlayerInventoryPacket(inventory, carriedItem);
            NetworkManager.sendToPlayer(player, packet);
        }
    }

    private void sendOperationResult(ServerPlayer player, boolean success, String message) {
        this.sendOperationResult(player, success, message, StorageNetworkHandler.OperationType.SYNC_REQUEST);
    }

    private void sendOperationResult(ServerPlayer player, boolean success, String message, StorageNetworkHandler.OperationType operationType) {
        StorageNetworkHandler.OperationResultPacket packet = new StorageNetworkHandler.OperationResultPacket(success, message, operationType);
        NetworkManager.sendToPlayer(player, packet);
    }

    public void cachePlayerInventory(ServerPlayer player) {
        UUID playerId = player.getUUID();
        ItemStack[] inventory = new ItemStack[36];
        for (int i = 0; i < 36; ++i) {
            inventory[i] = player.getInventory().getItem(i).copy();
        }
        ItemStack carriedItem = player.containerMenu.getCarried().copy();
        this.playerInventoryCache.put(playerId, inventory);
        this.playerCarriedItemCache.put(playerId, carriedItem);
    }

    public void cachePlayerInventoryOnly(ServerPlayer player) {
        UUID playerId = player.getUUID();
        ItemStack[] inventory = new ItemStack[36];
        for (int i = 0; i < 36; ++i) {
            inventory[i] = player.getInventory().getItem(i).copy();
        }
        this.playerInventoryCache.put(playerId, inventory);
    }

    public CraftChestData getOrCreateStorage(BlockPos pos) {
        return this.storageMap.computeIfAbsent(pos, k -> {
            CraftChestData storage = new CraftChestData();
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    storage.setRegistryAccess((HolderLookup.Provider)server.registryAccess());
                }
            }
            catch (Exception exception) {
                // empty catch block
            }
            return storage;
        });
    }

    public CraftChestData getOrCreateStorage(BlockPos pos, ServerLevel serverLevel) {
        return this.storageMap.computeIfAbsent(pos, k -> {
            CraftChestData storage = new CraftChestData();
            if (serverLevel != null) {
                storage.setRegistryAccess((HolderLookup.Provider)serverLevel.registryAccess());
            }
            return storage;
        });
    }

    public void removeStorage(BlockPos pos) {
        this.storageMap.remove(pos);
        this.setDirty();
    }

    public void markStorageBlockItemDropped(BlockPos pos) {
        if (pos == null) {
            return;
        }
        long now = System.currentTimeMillis();
        this.pruneExpiredDroppedMarkers(now);
        this.recentlyDroppedStorageBlockItems.put(pos.immutable(), now);
    }

    public boolean consumeStorageBlockItemDropped(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        this.pruneExpiredDroppedMarkers(System.currentTimeMillis());
        return this.recentlyDroppedStorageBlockItems.remove(pos.immutable()) != null;
    }

    public CraftChestData getStorage(BlockPos pos) {
        return this.storageMap.get(pos);
    }

    public boolean hasStorage(BlockPos pos) {
        return this.storageMap.containsKey(pos);
    }

    public Set<BlockPos> getAllStoragePositions() {
        return new HashSet<BlockPos>(this.storageMap.keySet());
    }

    public BlockPos getPlayerOpenStorage(UUID playerId) {
        return this.playerOpenStorage.get(playerId);
    }

    public boolean isPlayerStorageOpen(UUID playerId) {
        return this.playerOpenStorage.containsKey(playerId);
    }

    public void forceSync(ServerPlayer player) {
        CraftChestData storage;
        UUID playerId = player.getUUID();
        BlockPos storagePos = this.playerOpenStorage.get(playerId);
        // LOGGER.debug("forceSync() \u8c03\u7528: \u73a9\u5bb6={}", (Object)player.getName().getString());
        if (storagePos != null && (storage = this.storageMap.get(storagePos)) != null) {
            this.cachePlayerInventory(player);
            int currentPage = this.playerLastPage.getOrDefault(playerId, 0);
            String searchFilter = this.playerLastSearchFilter.getOrDefault(playerId, "");
            this.sendStorageDataToPlayer(player, storage, currentPage, searchFilter);
            this.sendPlayerInventoryToClient(player);
        }
    }

    /** \u5411"\u6b63\u6253\u5f00\u8be5\u5bb9\u5668"\u7684\u6240\u6709\u73a9\u5bb6\u63a8\u9001\u6700\u65b0\u4ed3\u5e93\u6570\u636e(\u542b\u53d1\u8d77\u8005)\u3002 */
    public void forceSyncAllViewers(ServerPlayer requester) {
        BlockPos target = this.playerOpenStorage.get(requester.getUUID());
        if (target == null) {
            return;
        }
        CraftChestData storage = this.storageMap.get(target);
        if (storage == null) {
            return;
        }
        net.minecraft.server.players.PlayerList list = requester.serverLevel().getServer().getPlayerList();
        for (java.util.Map.Entry<UUID, BlockPos> e : new java.util.ArrayList<java.util.Map.Entry<UUID, BlockPos>>(this.playerOpenStorage.entrySet())) {
            if (!target.equals(e.getValue())) continue;
            ServerPlayer viewer = list.getPlayer(e.getKey());
            if (viewer == null) continue;
            int currentPage = this.playerLastPage.getOrDefault(e.getKey(), 0);
            String searchFilter = this.playerLastSearchFilter.getOrDefault(e.getKey(), "");
            this.sendStorageDataToPlayer(viewer, storage, currentPage, searchFilter);
        }
    }

    public Map<String, Object> getStorageStats(BlockPos pos) {
        CraftChestData storage = this.storageMap.get(pos);
        HashMap<String, Object> stats = new HashMap<String, Object>();
        if (storage != null) {
            stats.put("totalItems", storage.getTotalItemCount());
            stats.put("totalTypes", storage.getTotalItemTypes());
            stats.put("isEmpty", storage.isEmpty());
        } else {
            stats.put("totalItems", 0L);
            stats.put("totalTypes", 0);
            stats.put("isEmpty", true);
        }
        return stats;
    }

    public void depositAllItems(ServerPlayer player) {
        UUID playerId = player.getUUID();
        BlockPos storagePos = this.playerOpenStorage.get(playerId);
        if (storagePos == null) {
            return;
        }
        CraftChestData storage = this.storageMap.get(storagePos);
        if (storage == null) {
            return;
        }
        ItemStack[] inventory = this.playerInventoryCache.get(playerId);
        if (inventory == null) {
            return;
        }
        for (int i = 0; i < inventory.length; ++i) {
            ItemStack stack = inventory[i];
            if (stack.isEmpty()) continue;
            storage.addItem(stack);
            inventory[i] = ItemStack.EMPTY;
        }
        this.playerInventoryCache.put(playerId, inventory);
        int currentPage = this.playerLastPage.getOrDefault(playerId, 0);
        String searchFilter = this.playerLastSearchFilter.getOrDefault(playerId, "");
        this.sendStorageDataToPlayer(player, storage, currentPage, searchFilter);
        this.sendPlayerInventoryToClient(player);
        this.sendOperationResult(player, true, "Deposited all items");
        this.setDirty();
    }

    private int convertContainerSlotToPlayerSlot(int containerSlotId) {
        if (containerSlotId >= 0 && containerSlotId <= 26) {
            return containerSlotId + 9;
        }
        if (containerSlotId >= 27 && containerSlotId <= 35) {
            return containerSlotId - 27;
        }
        return -1;
    }
}

