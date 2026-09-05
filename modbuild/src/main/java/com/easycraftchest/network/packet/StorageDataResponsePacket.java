/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.network.FriendlyByteBuf
 *  net.minecraft.network.codec.StreamCodec
 *  net.minecraft.network.protocol.common.custom.CustomPacketPayload
 *  net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type
 *  net.minecraft.resources.ResourceLocation
 */
package com.easycraftchest.network.packet;

import com.easycraftchest.storage.CraftChestData;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record StorageDataResponsePacket(BlockPos storagePos, Map<String, Long> storageData) implements CustomPacketPayload
{
    public static final CustomPacketPayload.Type<StorageDataResponsePacket> TYPE = new CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath((String)"easycraftchest", (String)"storage_data_response"));
    public static final StreamCodec<FriendlyByteBuf, StorageDataResponsePacket> STREAM_CODEC = StreamCodec.ofMember(StorageDataResponsePacket::write, StorageDataResponsePacket::new);

    public StorageDataResponsePacket(BlockPos storagePos, CraftChestData storageData) {
        this(storagePos, storageData.getAllItems());
    }

    public StorageDataResponsePacket(FriendlyByteBuf buf) {
        this(buf.readBlockPos(), StorageDataResponsePacket.readStorageDataFromBuffer(buf));
    }

    private static Map<String, Long> readStorageDataFromBuffer(FriendlyByteBuf buf) {
        int size = buf.readInt();
        HashMap<String, Long> data = new HashMap<String, Long>();
        for (int i = 0; i < size; ++i) {
            String itemKey = buf.readUtf();
            long count = buf.readLong();
            data.put(itemKey, count);
        }
        return data;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.storagePos);
        buf.writeInt(this.storageData.size());
        for (Map.Entry<String, Long> entry : this.storageData.entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeLong(entry.getValue().longValue());
        }
    }

    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

