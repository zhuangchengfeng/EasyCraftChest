/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  javax.annotation.Nullable
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Direction
 *  net.minecraft.core.HolderLookup$Provider
 *  net.minecraft.core.component.DataComponents
 *  net.minecraft.nbt.CompoundTag
 *  net.minecraft.nbt.ListTag
 *  net.minecraft.nbt.StringTag
 *  net.minecraft.nbt.Tag
 *  net.minecraft.network.chat.Component
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.InteractionHand
 *  net.minecraft.world.ItemInteractionResult
 *  net.minecraft.world.MenuProvider
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.player.Inventory
 *  net.minecraft.world.entity.player.Player
 *  net.minecraft.world.inventory.AbstractContainerMenu
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.component.CustomData
 *  net.minecraft.world.level.BlockGetter
 *  net.minecraft.world.level.ItemLike
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.level.block.Block
 *  net.minecraft.world.level.block.SoundType
 *  net.minecraft.world.level.block.state.BlockBehaviour$Properties
 *  net.minecraft.world.level.block.state.BlockState
 *  net.minecraft.world.level.material.MapColor
 *  net.minecraft.world.level.material.PushReaction
 *  net.minecraft.world.level.storage.loot.LootParams$Builder
 *  net.minecraft.world.phys.BlockHitResult
 *  net.neoforged.neoforge.items.IItemHandler
 */
package com.stroeud.block;

import com.stroeud.block.CustomStorageItemHandler;
import com.stroeud.container.CustomStorageContainer;
import com.stroeud.server.storage.CustomStorageManager;
import com.stroeud.storage.CustomStorageData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.items.IItemHandler;

public class CustomStorageBlock
extends Block {
    public CustomStorageBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2.5f, 6.0f).sound(SoundType.METAL).pushReaction(PushReaction.BLOCK));
    }

    public ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer) {
            ServerPlayer serverPlayer = (ServerPlayer)player;
            this.openCustomStorage(serverPlayer, pos);
        }
        return ItemInteractionResult.CONSUME;
    }

    private void openCustomStorage(ServerPlayer player, final BlockPos pos) {
        Level level = player.level();
        if (level instanceof ServerLevel) {
            ServerLevel serverLevel = (ServerLevel)level;
            CustomStorageManager manager = CustomStorageManager.get(serverLevel);
            manager.openStorage(player, pos);
            player.openMenu(new MenuProvider(){

                public Component getDisplayName() {
                    return Component.translatable((String)"gui.storageandoneclicksynthesis.custom_storage");
                }

                public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
                    return new CustomStorageContainer(containerId, playerInventory, pos);
                }
            }, pos);
        }
    }

    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel) {
            CompoundTag storageTag;
            ServerLevel serverLevel = (ServerLevel)level;
            CustomData customData = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
            if (customData != null && (storageTag = customData.copyTag()).contains("StoredItems")) {
                CustomStorageManager manager = CustomStorageManager.get(serverLevel);
                CustomStorageData storage = manager.getOrCreateStorage(pos);
                storage.setRegistryAccess((HolderLookup.Provider)serverLevel.registryAccess());
                if (storageTag.contains("FullDataCache")) {
                    CompoundTag fullDataCacheTag = storageTag.getCompound("FullDataCache");
                    for (String itemKey : fullDataCacheTag.getAllKeys()) {
                        try {
                            storage.restoreItemDataCache(itemKey, (Tag)fullDataCacheTag.getCompound(itemKey));
                        }
                        catch (Exception e) {
                            System.err.println("Failed to restore cached item data for key: " + itemKey + ", error: " + e.getMessage());
                        }
                    }
                }
                CompoundTag itemsTag = storageTag.getCompound("StoredItems");
                for (String itemKey : itemsTag.getAllKeys()) {
                    long count = itemsTag.getLong(itemKey);
                    storage.addItem(itemKey, count);
                }
                if (storageTag.contains("ItemOrder")) {
                    storage.restoreItemOrder(storageTag.getList("ItemOrder", 8));
                }
                if (storageTag.contains("SearchFilter")) {
                    storage.setSearchFilter(storageTag.getString("SearchFilter"));
                }
                if (storageTag.contains("CurrentPage")) {
                    int currentPage = storageTag.getInt("CurrentPage");
                    storage.setPage(currentPage);
                }
                manager.setDirty();
            }
        }
    }

    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel) {
            ServerLevel serverLevel = (ServerLevel)level;
            CustomStorageManager manager = CustomStorageManager.get(serverLevel);
            if (!manager.consumeStorageBlockItemDropped(pos)) {
                CustomStorageData storage = manager.getStorage(pos);
                ItemStack blockItem = new ItemStack((ItemLike)this);
                CustomStorageBlock.applyStorageDataToBlockItem(storage, blockItem);
                CustomStorageBlock.popResource((Level)level, (BlockPos)pos, (ItemStack)blockItem);
            }
            manager.removeStorage(pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    private static void applyStorageDataToBlockItem(CustomStorageData storage, ItemStack blockItem) {
        String searchFilter;
        List<String> itemOrder;
        if (storage == null || storage.isEmpty() || blockItem == null || blockItem.isEmpty()) {
            return;
        }
        CompoundTag storageTag = new CompoundTag();
        CompoundTag itemsTag = new CompoundTag();
        for (Map.Entry<String, Long> entry : storage.getAllItems().entrySet()) {
            itemsTag.putLong(entry.getKey(), entry.getValue().longValue());
        }
        storageTag.put("StoredItems", (Tag)itemsTag);
        Map<String, Tag> fullDataCache = storage.getFullItemDataCache();
        if (!fullDataCache.isEmpty()) {
            CompoundTag fullDataCacheTag = new CompoundTag();
            for (Map.Entry<String, Tag> entry : fullDataCache.entrySet()) {
                Tag tag = entry.getValue();
                if (!(tag instanceof CompoundTag)) continue;
                CompoundTag compoundTag = (CompoundTag)tag;
                fullDataCacheTag.put(entry.getKey(), (Tag)compoundTag);
            }
            storageTag.put("FullDataCache", (Tag)fullDataCacheTag);
        }
        if (!(itemOrder = storage.getItemOrder()).isEmpty()) {
            ListTag orderTag = new ListTag();
            for (String itemKey : itemOrder) {
                orderTag.add(StringTag.valueOf(itemKey));
            }
            storageTag.put("ItemOrder", (Tag)orderTag);
        }
        if ((searchFilter = storage.getSearchFilter()) != null && !searchFilter.isEmpty()) {
            storageTag.putString("SearchFilter", searchFilter);
        }
        storageTag.putInt("CurrentPage", storage.getCurrentPage());
        blockItem.set(DataComponents.CUSTOM_DATA, CustomData.of((CompoundTag)storageTag));
    }

    private void dropStorageContents(Level level, BlockPos pos, CustomStorageManager manager) {
        CustomStorageData storage = manager.getStorage(pos);
        if (storage != null && !storage.isEmpty()) {
            Map<String, Long> allItems = storage.getAllItems();
            for (Map.Entry<String, Long> entry : allItems.entrySet()) {
                int dropCount;
                String itemKey = entry.getKey();
                for (long count = entry.getValue().longValue(); count > 0L; count -= (long)dropCount) {
                    dropCount = (int)Math.min(count, 64L);
                    ItemStack dropStack = storage.createItemStackFromKey(itemKey);
                    if (dropStack.isEmpty()) continue;
                    dropStack.setCount(dropCount);
                    Block.popResource((Level)level, (BlockPos)pos, (ItemStack)dropStack);
                }
            }
        }
    }

    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && level instanceof ServerLevel) {
            ServerLevel serverLevel = (ServerLevel)level;
            ItemStack blockItem = new ItemStack((ItemLike)this);
            try {
                CustomStorageManager manager = CustomStorageManager.get(serverLevel);
                CustomStorageData storage = manager.getStorage(pos);
                CustomStorageBlock.applyStorageDataToBlockItem(storage, blockItem);
                manager.markStorageBlockItemDropped(pos);
            }
            catch (Exception e) {
                System.err.println("\u4fdd\u5b58\u5b58\u50a8\u6570\u636e\u65f6\u51fa\u9519: " + e.getMessage());
            }
            CustomStorageBlock.popResource((Level)level, (BlockPos)pos, (ItemStack)blockItem);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        return new ArrayList<ItemStack>();
    }

    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        ServerLevel serverLevel;
        CustomStorageManager manager;
        CustomStorageData storage;
        if (level instanceof ServerLevel && (storage = (manager = CustomStorageManager.get(serverLevel = (ServerLevel)level)).getStorage(pos)) != null) {
            int types = storage.getTotalItemTypes();
            return Math.min(15, types / 10);
        }
        return 0;
    }

    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
    }

    public String getStorageInfo(Level level, BlockPos pos) {
        if (level instanceof ServerLevel) {
            ServerLevel serverLevel = (ServerLevel)level;
            CustomStorageManager manager = CustomStorageManager.get(serverLevel);
            Map<String, Object> stats = manager.getStorageStats(pos);
            return String.format("Items: %s, Types: %d", stats.get("totalItems"), stats.get("totalTypes"));
        }
        return "No data";
    }

    public boolean canConnectToAutomation(Level level, BlockPos pos, Direction direction) {
        return true;
    }

    public boolean tryInsertItem(Level level, BlockPos pos, ItemStack stack, Direction direction) {
        if (level instanceof ServerLevel) {
            CustomStorageManager manager;
            CustomStorageData storage;
            ServerLevel serverLevel = (ServerLevel)level;
            if (!stack.isEmpty() && (storage = (manager = CustomStorageManager.get(serverLevel)).getOrCreateStorage(pos, serverLevel)) != null) {
                storage.addItem(stack);
                manager.setDirty();
                return true;
            }
        }
        return false;
    }

    public ItemStack tryExtractItem(Level level, BlockPos pos, int maxCount, Direction direction) {
        ServerLevel serverLevel;
        CustomStorageManager manager;
        CustomStorageData storage;
        if (level instanceof ServerLevel && (storage = (manager = CustomStorageManager.get(serverLevel = (ServerLevel)level)).getStorage(pos)) != null && !storage.isEmpty()) {
            Map<String, Long> allItems = storage.getAllItems();
            for (Map.Entry<String, Long> entry : allItems.entrySet()) {
                String itemKey = entry.getKey();
                long availableCount = entry.getValue();
                if (availableCount <= 0L) continue;
                long extractCount = Math.min((long)maxCount, availableCount);
                storage.removeItem(itemKey, extractCount);
                manager.setDirty();
                ItemStack result = storage.createItemStackFromKey(itemKey);
                result.setCount((int)extractCount);
                return result;
            }
        }
        return ItemStack.EMPTY;
    }

    public static boolean isCustomStorageBlock(Block block) {
        return block instanceof CustomStorageBlock;
    }

    public String getCapacityInfo() {
        return "Unlimited Storage";
    }

    public boolean canPlayerAccess(Player player, Level level, BlockPos pos) {
        return true;
    }

    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return 0;
    }

    public IItemHandler getItemHandler(Level level, BlockPos pos, @Nullable Direction side) {
        if (level instanceof ServerLevel) {
            ServerLevel serverLevel = (ServerLevel)level;
            CustomStorageItemHandler.Mode mode = side == Direction.UP ? CustomStorageItemHandler.Mode.INSERT_ONLY : CustomStorageItemHandler.Mode.FULL;
            return new CustomStorageItemHandler(mode, serverLevel, pos);
        }
        return null;
    }
}

