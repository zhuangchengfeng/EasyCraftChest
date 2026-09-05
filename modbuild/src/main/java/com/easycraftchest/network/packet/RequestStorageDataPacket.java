/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.logging.LogUtils
 *  net.minecraft.core.BlockPos
 *  net.minecraft.network.FriendlyByteBuf
 *  net.minecraft.network.codec.StreamCodec
 *  net.minecraft.network.protocol.common.custom.CustomPacketPayload
 *  net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.entity.player.Player
 *  net.neoforged.neoforge.network.handling.IPayloadContext
 *  org.slf4j.Logger
 */
package com.easycraftchest.network.packet;

import com.mojang.logging.LogUtils;
import com.easycraftchest.network.NetworkManager;
import com.easycraftchest.network.packet.StorageDataResponsePacket;
import com.easycraftchest.server.storage.CraftChestManager;
import com.easycraftchest.storage.CraftChestData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

public record RequestStorageDataPacket(BlockPos storagePos) implements CustomPacketPayload
{
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final CustomPacketPayload.Type<RequestStorageDataPacket> TYPE = new CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath((String)"easycraftchest", (String)"request_storage_data"));
    public static final StreamCodec<FriendlyByteBuf, RequestStorageDataPacket> STREAM_CODEC = StreamCodec.ofMember(RequestStorageDataPacket::write, RequestStorageDataPacket::new);

    public RequestStorageDataPacket(FriendlyByteBuf buf) {
        this(buf.readBlockPos());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.storagePos);
    }

    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestStorageDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player patt0$temp = context.player();
            if (patt0$temp instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer)patt0$temp;
                ServerLevel level = serverPlayer.serverLevel();
                CraftChestManager manager = CraftChestManager.get(level);
                LOGGER.info("\u8bf7\u6c42\u5b58\u50a8\u6570\u636e\uff0c\u4f4d\u7f6e: " + String.valueOf(packet.storagePos()));
                CraftChestData storageData = manager.getOrCreateStorage(packet.storagePos(), level);
                LOGGER.info("\u83b7\u53d6/\u521b\u5efa\u5b58\u50a8\u6570\u636e\uff0c\u7269\u54c1\u6570\u91cf: " + storageData.getAllItems().size());
                StorageDataResponsePacket response = new StorageDataResponsePacket(packet.storagePos(), storageData);
                NetworkManager.sendToPlayer(serverPlayer, response);
            }
        });
    }
}

