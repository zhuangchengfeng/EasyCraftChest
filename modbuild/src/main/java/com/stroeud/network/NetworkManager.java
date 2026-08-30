/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.network.protocol.common.custom.CustomPacketPayload
 *  net.minecraft.resources.ResourceKey
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.level.Level
 *  net.neoforged.bus.api.SubscribeEvent
 *  net.neoforged.fml.common.EventBusSubscriber
 *  net.neoforged.neoforge.network.PacketDistributor
 *  net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
 *  net.neoforged.neoforge.network.registration.PayloadRegistrar
 */
package com.stroeud.network;

import com.stroeud.network.StorageNetworkHandler;
import com.stroeud.network.packet.SynthesisResultPacket;
import com.stroeud.network.packet.TrySynthesisPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid="storageandoneclicksynthesis")
public class NetworkManager {
    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("storageandoneclicksynthesis");
        registrar.playToClient(StorageNetworkHandler.StorageDataPacket.TYPE, StorageNetworkHandler.StorageDataPacket.STREAM_CODEC, StorageNetworkHandler.StorageDataPacket::handle);
        registrar.playToServer(StorageNetworkHandler.ItemOperationPacket.TYPE, StorageNetworkHandler.ItemOperationPacket.STREAM_CODEC, StorageNetworkHandler.ItemOperationPacket::handle);
        registrar.playToClient(StorageNetworkHandler.PlayerInventoryPacket.TYPE, StorageNetworkHandler.PlayerInventoryPacket.STREAM_CODEC, StorageNetworkHandler.PlayerInventoryPacket::handle);
        registrar.playToClient(StorageNetworkHandler.OperationResultPacket.TYPE, StorageNetworkHandler.OperationResultPacket.STREAM_CODEC, StorageNetworkHandler.OperationResultPacket::handle);
        registrar.playToServer(StorageNetworkHandler.OpenStoragePacket.TYPE, StorageNetworkHandler.OpenStoragePacket.STREAM_CODEC, StorageNetworkHandler.OpenStoragePacket::handle);
        registrar.playToServer(TrySynthesisPacket.TYPE, TrySynthesisPacket.STREAM_CODEC, TrySynthesisPacket::handle);
        registrar.playToClient(SynthesisResultPacket.TYPE, SynthesisResultPacket.STREAM_CODEC, SynthesisResultPacket::handle);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer((CustomPacketPayload)payload, (CustomPacketPayload[])new CustomPacketPayload[0]);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer((ServerPlayer)player, (CustomPacketPayload)payload, (CustomPacketPayload[])new CustomPacketPayload[0]);
    }

    public static void sendToAllPlayers(CustomPacketPayload payload) {
        PacketDistributor.sendToAllPlayers((CustomPacketPayload)payload, (CustomPacketPayload[])new CustomPacketPayload[0]);
    }

    public static void sendToDimension(ResourceKey<Level> dimension, CustomPacketPayload payload) {
    }

    public static void sendOpenStoragePacket(ServerPlayer player, BlockPos pos) {
        StorageNetworkHandler.OpenStoragePacket packet = new StorageNetworkHandler.OpenStoragePacket(pos.getX(), pos.getY(), pos.getZ());
        NetworkManager.sendToPlayer(player, packet);
    }
}

