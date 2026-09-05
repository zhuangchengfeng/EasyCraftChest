package com.easycraftchest.network.packet;

import com.easycraftchest.client.gui.CraftChestScreen;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 合成结果客户端数据包:告诉客户端合成是否成功,以及(失败时)缺失材料清单。
 * missing 键为物品注册表 ID(如 "minecraft:oak_log"),值为缺失数量。
 */
public record SynthesisResultPacket(boolean success, String message, Map<String, Long> missing) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SynthesisResultPacket> TYPE = new CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("easycraftchest", "synthesis_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SynthesisResultPacket> STREAM_CODEC = StreamCodec.ofMember(SynthesisResultPacket::write, SynthesisResultPacket::new);

    public SynthesisResultPacket(RegistryFriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readUtf(), SynthesisResultPacket.readMissing(buf));
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(this.success);
        buf.writeUtf(this.message == null ? "" : this.message);
        if (this.missing == null) {
            buf.writeInt(0);
            return;
        }
        buf.writeInt(this.missing.size());
        for (Map.Entry<String, Long> e : this.missing.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeLong(e.getValue() == null ? 0L : e.getValue().longValue());
        }
    }

    private static Map<String, Long> readMissing(RegistryFriendlyByteBuf buf) {
        int size = buf.readInt();
        HashMap<String, Long> map = new HashMap<String, Long>();
        for (int i = 0; i < size; i++) {
            map.put(buf.readUtf(), buf.readLong());
        }
        return map;
    }

    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SynthesisResultPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            CraftChestScreen screen = CraftChestScreen.getCurrentInstance();
            if (screen != null) {
                screen.handleSynthesisResult(packet);
            }
        });
    }
}
