/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.logging.LogUtils
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.screens.Screen
 *  net.minecraft.core.BlockPos
 *  net.minecraft.nbt.CompoundTag
 *  net.minecraft.nbt.Tag
 *  net.minecraft.network.FriendlyByteBuf
 *  net.minecraft.network.RegistryFriendlyByteBuf
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.codec.StreamCodec
 *  net.minecraft.network.protocol.common.custom.CustomPacketPayload
 *  net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.MenuProvider
 *  net.minecraft.world.entity.player.Inventory
 *  net.minecraft.world.entity.player.Player
 *  net.minecraft.world.inventory.AbstractContainerMenu
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.level.Level
 *  net.neoforged.neoforge.network.handling.IPayloadContext
 *  org.slf4j.Logger
 */
package com.easycraftchest.network;

import com.mojang.logging.LogUtils;
import com.easycraftchest.block.CraftChestBlock;
import com.easycraftchest.client.gui.CraftChestScreen;
import com.easycraftchest.container.CraftChestContainer;
import com.easycraftchest.server.storage.CraftChestManager;
import com.easycraftchest.storage.CraftChestData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

public class StorageNetworkHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String MOD_ID = "easycraftchest";

    public static class OpenStoragePacket
    implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<OpenStoragePacket> TYPE = new CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath((String)"easycraftchest", (String)"open_storage"));
        public static final StreamCodec<FriendlyByteBuf, OpenStoragePacket> STREAM_CODEC = StreamCodec.ofMember(OpenStoragePacket::write, OpenStoragePacket::new);
        private final int x;
        private final int y;
        private final int z;

        public OpenStoragePacket(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public OpenStoragePacket(FriendlyByteBuf buf) {
            this.x = buf.readInt();
            this.y = buf.readInt();
            this.z = buf.readInt();
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeInt(this.x);
            buf.writeInt(this.y);
            buf.writeInt(this.z);
        }

        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(OpenStoragePacket packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!context.flow().isServerbound()) {
                    return;
                }
                Player patt0$temp = context.player();
                if (!(patt0$temp instanceof ServerPlayer)) {
                    return;
                }
                ServerPlayer player = (ServerPlayer)patt0$temp;
                Level patt1$temp = player.level();
                if (!(patt1$temp instanceof ServerLevel)) {
                    return;
                }
                ServerLevel serverLevel = (ServerLevel)patt1$temp;
                final BlockPos pos = new BlockPos(packet.x, packet.y, packet.z);
                if (!serverLevel.isLoaded(pos)) {
                    return;
                }
                if (!(serverLevel.getBlockState(pos).getBlock() instanceof CraftChestBlock)) {
                    return;
                }
                if (player.distanceToSqr((double)pos.getX() + 0.5, (double)pos.getY() + 0.5, (double)pos.getZ() + 0.5) > 64.0) {
                    return;
                }
                CraftChestManager manager = CraftChestManager.get(serverLevel);
                manager.openStorage(player, pos);
                player.openMenu(new MenuProvider(){

                    public Component getDisplayName() {
                        return Component.translatable((String)"gui.easycraftchest.craft_chest");
                    }

                    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player p) {
                        return new CraftChestContainer(containerId, playerInventory, pos);
                    }
                }, pos);
            });
        }

        public int getX() {
            return this.x;
        }

        public int getY() {
            return this.y;
        }

        public int getZ() {
            return this.z;
        }
    }

    public static class PlayerInventoryOperationPacket
    implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PlayerInventoryOperationPacket> TYPE = new CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath((String)"easycraftchest", (String)"player_inventory_operation"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayerInventoryOperationPacket> STREAM_CODEC = StreamCodec.ofMember(PlayerInventoryOperationPacket::write, PlayerInventoryOperationPacket::new);
        private final int slotIndex;
        private final ItemStack newStack;
        private final ItemStack newCarriedItem;

        public PlayerInventoryOperationPacket(int slotIndex, ItemStack newStack, ItemStack newCarriedItem) {
            this.slotIndex = slotIndex;
            this.newStack = newStack.copy();
            this.newCarriedItem = newCarriedItem.copy();
        }

        public PlayerInventoryOperationPacket(RegistryFriendlyByteBuf buf) {
            this.slotIndex = buf.readInt();
            boolean hasStack = buf.readBoolean();
            this.newStack = hasStack ? (ItemStack)ItemStack.STREAM_CODEC.decode(buf) : ItemStack.EMPTY;
            boolean hasCarried = buf.readBoolean();
            this.newCarriedItem = hasCarried ? (ItemStack)ItemStack.STREAM_CODEC.decode(buf) : ItemStack.EMPTY;
        }

        public void write(RegistryFriendlyByteBuf buf) {
            buf.writeInt(this.slotIndex);
            buf.writeBoolean(!this.newStack.isEmpty());
            if (!this.newStack.isEmpty()) {
                ItemStack.STREAM_CODEC.encode(buf,this.newStack);
            }
            buf.writeBoolean(!this.newCarriedItem.isEmpty());
            if (!this.newCarriedItem.isEmpty()) {
                ItemStack.STREAM_CODEC.encode(buf,this.newCarriedItem);
            }
        }

        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(PlayerInventoryOperationPacket packet, IPayloadContext context) {
            context.enqueueWork(() -> {});
        }

        public int getSlotIndex() {
            return this.slotIndex;
        }

        public ItemStack getNewStack() {
            return this.newStack.copy();
        }

        public ItemStack getNewCarriedItem() {
            return this.newCarriedItem.copy();
        }
    }

    public static class OperationResultPacket
    implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<OperationResultPacket> TYPE = new CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath((String)"easycraftchest", (String)"operation_result"));
        public static final StreamCodec<FriendlyByteBuf, OperationResultPacket> STREAM_CODEC = StreamCodec.ofMember(OperationResultPacket::write, OperationResultPacket::new);
        private final boolean success;
        private final String message;
        private final OperationType operationType;

        public OperationResultPacket(boolean success, String message, OperationType operationType) {
            this.success = success;
            this.message = message != null ? message : "";
            this.operationType = operationType;
        }

        public OperationResultPacket(FriendlyByteBuf buf) {
            this.success = buf.readBoolean();
            this.message = buf.readUtf();
            this.operationType = (OperationType)buf.readEnum(OperationType.class);
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(this.success);
            buf.writeUtf(this.message);
            buf.writeEnum((Enum)this.operationType);
        }

        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(OperationResultPacket packet, IPayloadContext context) {
            if (!context.flow().isClientbound()) {
                return;
            }
            context.enqueueWork(() -> {
                if (packet.getOperationType() == OperationType.DROP) {
                    Minecraft mc = Minecraft.getInstance();
                    Screen patt0$temp = mc.screen;
                    if (patt0$temp instanceof CraftChestScreen) {
                        CraftChestScreen screen = (CraftChestScreen)patt0$temp;
                        screen.handleDropResponse(packet.isSuccess());
                    }
                }
                if (packet.isSuccess() || !packet.getMessage().isEmpty()) {
                    // empty if block
                }
            });
        }

        public boolean isSuccess() {
            return this.success;
        }

        public String getMessage() {
            return this.message;
        }

        public OperationType getOperationType() {
            return this.operationType;
        }
    }

    public static class PlayerInventoryPacket
    implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PlayerInventoryPacket> TYPE = new CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath((String)"easycraftchest", (String)"player_inventory"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayerInventoryPacket> STREAM_CODEC = StreamCodec.ofMember(PlayerInventoryPacket::write, PlayerInventoryPacket::new);
        private final ItemStack[] items;
        private final ItemStack carriedItem;

        public PlayerInventoryPacket(ItemStack[] items, ItemStack carriedItem) {
            this.items = (ItemStack[])items.clone();
            this.carriedItem = carriedItem.copy();
        }

        public PlayerInventoryPacket(RegistryFriendlyByteBuf buf) {
            int size = buf.readInt();
            this.items = new ItemStack[size];
            for (int i = 0; i < size; ++i) {
                boolean isEmpty = buf.readBoolean();
                this.items[i] = isEmpty ? ItemStack.EMPTY : (ItemStack)ItemStack.STREAM_CODEC.decode(buf);
            }
            boolean carriedEmpty = buf.readBoolean();
            this.carriedItem = carriedEmpty ? ItemStack.EMPTY : (ItemStack)ItemStack.STREAM_CODEC.decode(buf);
        }

        public void write(RegistryFriendlyByteBuf buf) {
            buf.writeInt(this.items.length);
            for (ItemStack item : this.items) {
                boolean isEmpty = item == null || item.isEmpty();
                buf.writeBoolean(isEmpty);
                if (isEmpty) continue;
                ItemStack.STREAM_CODEC.encode(buf,item);
            }
            boolean carriedEmpty = this.carriedItem == null || this.carriedItem.isEmpty();
            buf.writeBoolean(carriedEmpty);
            if (!carriedEmpty) {
                ItemStack.STREAM_CODEC.encode(buf,this.carriedItem);
            }
        }

        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(PlayerInventoryPacket packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (context.flow().isClientbound()) {
                    Minecraft mc = Minecraft.getInstance();
                    Screen patt0$temp = mc.screen;
                    if (patt0$temp instanceof CraftChestScreen) {
                        CraftChestScreen screen = (CraftChestScreen)patt0$temp;
                        screen.updatePlayerInventory(packet);
                    }
                }
            });
        }

        public ItemStack[] getItems() {
            return (ItemStack[])this.items.clone();
        }

        public ItemStack getCarriedItem() {
            return this.carriedItem.copy();
        }
    }

    public static class ItemOperationPacket
    implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ItemOperationPacket> TYPE = new CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath((String)"easycraftchest", (String)"item_operation"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ItemOperationPacket> STREAM_CODEC = StreamCodec.ofMember(ItemOperationPacket::write, ItemOperationPacket::new);
        private final OperationType type;
        private final String itemKey;
        private final long amount;
        private final int targetPage;
        private final String searchFilter;
        private final ItemStack carriedItem;
        private final boolean isShiftClick;
        private final int slotId;

        public ItemOperationPacket(OperationType type, String itemKey, long amount, int targetPage, String searchFilter, ItemStack carriedItem) {
            this.type = type;
            this.itemKey = itemKey != null ? itemKey : "";
            this.amount = amount;
            this.targetPage = targetPage;
            this.searchFilter = searchFilter != null ? searchFilter : "";
            this.carriedItem = carriedItem != null ? carriedItem : ItemStack.EMPTY;
            this.isShiftClick = false;
            this.slotId = -1;
        }

        public ItemOperationPacket(OperationType type, String itemKey, long amount, int targetPage, String searchFilter, ItemStack carriedItem, boolean isShiftClick, int slotId) {
            this.type = type;
            this.itemKey = itemKey != null ? itemKey : "";
            this.amount = amount;
            this.targetPage = targetPage;
            this.searchFilter = searchFilter != null ? searchFilter : "";
            this.carriedItem = carriedItem != null ? carriedItem : ItemStack.EMPTY;
            this.isShiftClick = isShiftClick;
            this.slotId = slotId;
        }

        public ItemOperationPacket(RegistryFriendlyByteBuf buf) {
            this.type = (OperationType)buf.readEnum(OperationType.class);
            this.itemKey = buf.readUtf();
            this.amount = buf.readLong();
            this.targetPage = buf.readInt();
            this.searchFilter = buf.readUtf();
            boolean hasCarriedItem = buf.readBoolean();
            this.carriedItem = hasCarriedItem ? (ItemStack)ItemStack.STREAM_CODEC.decode(buf) : ItemStack.EMPTY;
            this.isShiftClick = buf.readBoolean();
            this.slotId = buf.readInt();
        }

        public void write(RegistryFriendlyByteBuf buf) {
            buf.writeEnum((Enum)this.type);
            buf.writeUtf(this.itemKey);
            buf.writeLong(this.amount);
            buf.writeInt(this.targetPage);
            buf.writeUtf(this.searchFilter);
            boolean hasCarriedItem = !this.carriedItem.isEmpty();
            buf.writeBoolean(hasCarriedItem);
            if (hasCarriedItem) {
                ItemStack.STREAM_CODEC.encode(buf,this.carriedItem);
            }
            buf.writeBoolean(this.isShiftClick);
            buf.writeInt(this.slotId);
        }

        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(ItemOperationPacket packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                Player patt0$temp = context.player();
                if (patt0$temp instanceof ServerPlayer) {
                    ServerPlayer serverPlayer = (ServerPlayer)patt0$temp;
                    ServerLevel serverLevel = serverPlayer.serverLevel();
                    CraftChestManager manager = CraftChestManager.get(serverLevel);
                    manager.handleItemOperation(serverPlayer, packet);
                }
            });
        }

        public OperationType getType() {
            return this.type;
        }

        public String getItemKey() {
            return this.itemKey;
        }

        public long getAmount() {
            return this.amount;
        }

        public int getTargetPage() {
            return this.targetPage;
        }

        public String getSearchFilter() {
            return this.searchFilter;
        }

        public boolean isShiftClick() {
            return this.isShiftClick;
        }

        public ItemStack getCarriedItem() {
            return this.carriedItem;
        }

        public int getSlotId() {
            return this.slotId;
        }
    }

    public static class StorageDataPacket
    implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<StorageDataPacket> TYPE = new CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath((String)"easycraftchest", (String)"storage_data"));
        public static final StreamCodec<FriendlyByteBuf, StorageDataPacket> STREAM_CODEC = StreamCodec.ofMember(StorageDataPacket::write, StorageDataPacket::new);
        private final Map<String, Long> items;
        private final Map<String, CompoundTag> cachedItemData;
        private final int currentPage;
        private final int maxPage;
        private final String searchFilter;
        private final long totalItems;
        private final int totalTypes;
        private final Map<String, Long> modified;

        public StorageDataPacket(Map<String, Long> items, Map<String, CompoundTag> cachedItemData, int currentPage, int maxPage, String searchFilter, long totalItems, int totalTypes, Map<String, Long> modified) {
            this.items = items != null ? new LinkedHashMap<String, Long>(items) : new LinkedHashMap();
            this.cachedItemData = cachedItemData != null ? new LinkedHashMap<String, CompoundTag>(cachedItemData) : new LinkedHashMap();
            this.currentPage = currentPage;
            this.maxPage = maxPage;
            this.searchFilter = searchFilter != null ? searchFilter : "";
            this.totalItems = totalItems;
            this.totalTypes = totalTypes;
            this.modified = modified != null ? new LinkedHashMap<String, Long>(modified) : new LinkedHashMap();
        }

        public StorageDataPacket(CraftChestData data) {
            this.items = new LinkedHashMap<String, Long>(data.getAllItems());
            this.cachedItemData = new LinkedHashMap<String, CompoundTag>();
            Map<String, Tag> rawCachedData = data.getCachedItemData();
            for (Map.Entry<String, Tag> entry : rawCachedData.entrySet()) {
                Tag tag = entry.getValue();
                if (!(tag instanceof CompoundTag)) continue;
                CompoundTag compoundTag = (CompoundTag)tag;
                this.cachedItemData.put(entry.getKey(), compoundTag);
            }
            this.currentPage = data.getCurrentPage();
            this.maxPage = data.getMaxPage();
            this.searchFilter = data.getSearchFilter();
            this.totalItems = data.getTotalItemCount();
            this.totalTypes = data.getTotalItemTypes();
            this.modified = data.getLastModifiedMap();
        }

        public StorageDataPacket(FriendlyByteBuf buf) {
            int itemCount = buf.readInt();
            this.items = new LinkedHashMap<String, Long>();
            for (int i = 0; i < itemCount; ++i) {
                String key = buf.readUtf();
                long count = buf.readLong();
                this.items.put(key, count);
            }
            int cachedDataCount = buf.readInt();
            this.cachedItemData = new LinkedHashMap<String, CompoundTag>();
            for (int i = 0; i < cachedDataCount; ++i) {
                String key = buf.readUtf();
                CompoundTag data = buf.readNbt();
                if (data == null) continue;
                this.cachedItemData.put(key, data);
            }
            this.currentPage = buf.readInt();
            this.maxPage = buf.readInt();
            this.searchFilter = buf.readUtf();
            this.totalItems = buf.readLong();
            this.totalTypes = buf.readInt();
            int modCount = buf.readInt();
            this.modified = new LinkedHashMap<String, Long>();
            for (int i = 0; i < modCount; ++i) {
                String key = buf.readUtf();
                long t = buf.readLong();
                this.modified.put(key, t);
            }
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeInt(this.items.size());
            for (Map.Entry<String, Long> entry : this.items.entrySet()) {
                buf.writeUtf(entry.getKey());
                buf.writeLong(entry.getValue().longValue());
            }
            buf.writeInt(this.cachedItemData.size());
            for (Map.Entry<String, CompoundTag> entry : this.cachedItemData.entrySet()) {
                buf.writeUtf(entry.getKey());
                buf.writeNbt((Tag)entry.getValue());
            }
            buf.writeInt(this.currentPage);
            buf.writeInt(this.maxPage);
            buf.writeUtf(this.searchFilter);
            buf.writeLong(this.totalItems);
            buf.writeInt(this.totalTypes);
            buf.writeInt(this.modified.size());
            for (Map.Entry<String, Long> e : this.modified.entrySet()) {
                buf.writeUtf(e.getKey());
                buf.writeLong(e.getValue() == null ? 0L : e.getValue().longValue());
            }
        }

        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(StorageDataPacket packet, IPayloadContext context) {
            if (!context.flow().isClientbound()) {
                return;
            }
            context.enqueueWork(() -> {
                Minecraft minecraft = Minecraft.getInstance();
                Screen patt0$temp = minecraft.screen;
                if (patt0$temp instanceof CraftChestScreen) {
                    CraftChestScreen screen = (CraftChestScreen)patt0$temp;
                    screen.updateStorageData(packet);
                }
            });
        }

        public Map<String, Long> getItems() {
            return this.items;
        }

        public Map<String, CompoundTag> getCachedItemData() {
            return this.cachedItemData;
        }

        public int getCurrentPage() {
            return this.currentPage;
        }

        public int getMaxPage() {
            return this.maxPage;
        }

        public String getSearchFilter() {
            return this.searchFilter;
        }

        public long getTotalItems() {
            return this.totalItems;
        }

        public int getTotalTypes() {
            return this.totalTypes;
        }

        public Map<String, Long> getModified() {
            return this.modified;
        }
    }

    /** 客户端→服务端:请求当前打开方块的合成历史(仅在进入历史面板/合成成功后发送一次)。 */
    public static class SynthesisHistoryRequestPacket
    implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SynthesisHistoryRequestPacket> TYPE = new CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath((String)"easycraftchest", (String)"synthesis_history_request"));
        public static final StreamCodec<FriendlyByteBuf, SynthesisHistoryRequestPacket> STREAM_CODEC = StreamCodec.ofMember(SynthesisHistoryRequestPacket::write, SynthesisHistoryRequestPacket::new);

        public SynthesisHistoryRequestPacket() {
        }

        public SynthesisHistoryRequestPacket(FriendlyByteBuf buf) {
        }

        public void write(FriendlyByteBuf buf) {
        }

        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(SynthesisHistoryRequestPacket packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!context.flow().isServerbound()) {
                    return;
                }
                Player p = context.player();
                if (!(p instanceof ServerPlayer)) {
                    return;
                }
                ServerPlayer player = (ServerPlayer)p;
                Level lvl = player.level();
                if (!(lvl instanceof ServerLevel)) {
                    return;
                }
                ServerLevel serverLevel = (ServerLevel)lvl;
                CraftChestManager manager = CraftChestManager.get(serverLevel);
                manager.sendSynthesisHistoryToPlayer(player);
            });
        }
    }

    /** 服务端→客户端:合成历史(每物品一条,最新在前),含合成者名/UUID/服务端时间戳。 */
    public static class SynthesisHistoryPacket
    implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SynthesisHistoryPacket> TYPE = new CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath((String)"easycraftchest", (String)"synthesis_history"));
        public static final StreamCodec<FriendlyByteBuf, SynthesisHistoryPacket> STREAM_CODEC = StreamCodec.ofMember(SynthesisHistoryPacket::write, SynthesisHistoryPacket::new);
        private final List<CraftChestData.SynthesisHistoryEntry> entries;

        public SynthesisHistoryPacket(List<CraftChestData.SynthesisHistoryEntry> entries) {
            this.entries = entries != null ? new ArrayList<CraftChestData.SynthesisHistoryEntry>(entries) : new ArrayList<CraftChestData.SynthesisHistoryEntry>();
        }

        public SynthesisHistoryPacket(FriendlyByteBuf buf) {
            int n = buf.readInt();
            ArrayList<CraftChestData.SynthesisHistoryEntry> list = new ArrayList<CraftChestData.SynthesisHistoryEntry>();
            for (int i = 0; i < n; ++i) {
                String itemKey = buf.readUtf();
                String name = buf.readUtf();
                String uuid = buf.readUtf();
                long t = buf.readLong();
                list.add(new CraftChestData.SynthesisHistoryEntry(itemKey, name, uuid, t));
            }
            this.entries = list;
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeInt(this.entries.size());
            for (CraftChestData.SynthesisHistoryEntry e : this.entries) {
                buf.writeUtf(e.itemKey == null ? "" : e.itemKey);
                buf.writeUtf(e.playerName == null ? "" : e.playerName);
                buf.writeUtf(e.playerUuid == null ? "" : e.playerUuid);
                buf.writeLong(e.timeMs);
            }
        }

        public List<CraftChestData.SynthesisHistoryEntry> getEntries() {
            return new ArrayList<CraftChestData.SynthesisHistoryEntry>(this.entries);
        }

        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(SynthesisHistoryPacket packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!context.flow().isClientbound()) {
                    return;
                }
                Minecraft mc = Minecraft.getInstance();
                Screen scr = mc.screen;
                if (scr instanceof CraftChestScreen) {
                    ((CraftChestScreen)scr).receiveSynthesisHistory(packet.getEntries());
                }
            });
        }
    }

    public static enum OperationType {
        TAKE,
        PUT,
        SEARCH,
        PAGE_CHANGE,
        SYNC_REQUEST,
        DROP,
        CLOSE;

    }
}

