/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.logging.LogUtils
 *  java.lang.MatchException
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.HolderLookup$Provider
 *  net.minecraft.core.registries.BuiltInRegistries
 *  net.minecraft.nbt.CompoundTag
 *  net.minecraft.network.RegistryFriendlyByteBuf
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.chat.MutableComponent
 *  net.minecraft.network.codec.StreamCodec
 *  net.minecraft.network.protocol.common.custom.CustomPacketPayload
 *  net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.entity.player.Player
 *  net.minecraft.world.item.Item
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.Items
 *  net.minecraft.world.level.ItemLike
 *  net.minecraft.world.level.Level
 *  net.neoforged.neoforge.network.handling.IPayloadContext
 *  org.slf4j.Logger
 */
package com.stroeud.network.packet;

import com.mojang.logging.LogUtils;
import com.stroeud.block.CustomStorageBlock;
import com.stroeud.config.ModConfigs;
import com.stroeud.network.NetworkManager;
import com.stroeud.server.recipe.CraftingStep;
import com.stroeud.server.recipe.RecipeResolutionResult;
import com.stroeud.server.recipe.RecipeResolver;
import com.stroeud.server.storage.CustomStorageManager;
import com.stroeud.storage.CustomStorageData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

public record TrySynthesisPacket(ItemStack targetItem, BlockPos storagePos, int synthesisCount) implements CustomPacketPayload
{
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final CustomPacketPayload.Type<TrySynthesisPacket> TYPE = new CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath((String)"storageandoneclicksynthesis", (String)"try_synthesis"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrySynthesisPacket> STREAM_CODEC = StreamCodec.ofMember(TrySynthesisPacket::write, TrySynthesisPacket::new);

    public TrySynthesisPacket(RegistryFriendlyByteBuf buf) {
        this(ItemStack.STREAM_CODEC.decode(buf), buf.readBlockPos(), buf.readInt());
    }

    public void write(RegistryFriendlyByteBuf buf) {
        ItemStack.STREAM_CODEC.encode(buf, this.targetItem);
        buf.writeBlockPos(this.storagePos);
        buf.writeInt(this.synthesisCount);
    }

    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            Player patt0$temp = context.player();
            if (patt0$temp instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer)patt0$temp;
                try {
                    boolean isOpenThisStorage;
                    if (this.synthesisCount <= 0 || this.synthesisCount > 999) {
                        return;
                    }
                    if (this.targetItem == null || this.targetItem.isEmpty() || this.targetItem.getItem() == Items.AIR) {
                        return;
                    }
                    if (this.storagePos == null) {
                        return;
                    }
                    CustomStorageManager storageManager = CustomStorageManager.get(serverPlayer.serverLevel());
                    BlockPos openPos = storageManager.getPlayerOpenStorage(serverPlayer.getUUID());
                    boolean bl = isOpenThisStorage = openPos != null && openPos.equals((Object)this.storagePos);
                    if (!serverPlayer.serverLevel().isLoaded(this.storagePos)) {
                        TrySynthesisPacket.sendPlayerMessage(serverPlayer, (Component)Component.literal((String)"\u4e00\u952e\u5408\u6210\u5931\u8d25\uff1a\u5b58\u50a8\u65b9\u5757\u533a\u5757\u672a\u52a0\u8f7d"));
                        return;
                    }
                    if (!isOpenThisStorage) {
                        double d;
                        double dy;
                        if (!(serverPlayer.serverLevel().getBlockState(this.storagePos).getBlock() instanceof CustomStorageBlock)) {
                            TrySynthesisPacket.sendPlayerMessage(serverPlayer, (Component)Component.literal((String)"\u4e00\u952e\u5408\u6210\u5931\u8d25\uff1a\u76ee\u6807\u4f4d\u7f6e\u4e0d\u662f\u5b58\u50a8\u65b9\u5757"));
                            return;
                        }
                        double dx = serverPlayer.getX() - ((double)this.storagePos.getX() + 0.5);
                        double distSq = dx * dx + (dy = serverPlayer.getY() - ((double)this.storagePos.getY() + 0.5)) * dy + (d = serverPlayer.getZ() - ((double)this.storagePos.getZ() + 0.5)) * d;
                        if (distSq > 64.0) {
                            TrySynthesisPacket.sendPlayerMessage(serverPlayer, (Component)Component.literal((String)"\u4e00\u952e\u5408\u6210\u5931\u8d25\uff1a\u8bf7\u9760\u8fd1\u5b58\u50a8\u65b9\u5757\uff088\u683c\u5185\uff09\u6216\u5148\u6253\u5f00\u5b58\u50a8\u754c\u9762"));
                            return;
                        }
                    }
                    CustomStorageData storageData = storageManager.getOrCreateStorage(this.storagePos, serverPlayer.serverLevel());
                    storageData.setRegistryAccess((HolderLookup.Provider)serverPlayer.serverLevel().registryAccess());
                    RecipeResolver recipeResolver = new RecipeResolver((Level)serverPlayer.serverLevel());
                    HashMap<Item, Integer> availableItems = new HashMap<Item, Integer>();
                    LOGGER.debug("\u5b58\u50a8\u5bb9\u5668\u4e2d\u7684\u6240\u6709\u7269\u54c1:");
                    for (Map.Entry<String, Long> entry : storageData.getAllItems().entrySet()) {
                        String itemKey = entry.getKey();
                        long count = entry.getValue();
                        LOGGER.debug("  - " + itemKey + ": " + count);
                        try {
                            ResourceLocation itemId;
                            Item item;
                            String baseItemId = itemKey;
                            if (baseItemId.contains("#")) {
                                baseItemId = baseItemId.split("#")[0];
                            }
                            if (baseItemId.contains("@")) {
                                baseItemId = baseItemId.split("@")[0];
                            }
                            if ((item = (Item)BuiltInRegistries.ITEM.get(itemId = ResourceLocation.parse((String)baseItemId))) == null || item == Items.AIR) continue;
                            int currentCount = availableItems.getOrDefault(item, 0);
                            int newCount = (int)Math.min((long)currentCount + count, Integer.MAX_VALUE);
                            availableItems.put(item, newCount);
                            LOGGER.debug("    \u89e3\u6790\u4e3a: " + item.getDescriptionId() + " \u7d2f\u8ba1\u6570\u91cf: " + newCount);
                        }
                        catch (Exception e) {
                            LOGGER.error("\u89e3\u6790\u7269\u54c1\u952e\u503c\u5931\u8d25: " + itemKey, (Throwable)e);
                        }
                    }
                    LOGGER.debug("\u6700\u7ec8\u53ef\u7528\u7269\u54c1\u6620\u5c04:");
                    for (Map.Entry<Item, Integer> entry : availableItems.entrySet()) {
                        LOGGER.debug("  - " + ((Item)entry.getKey()).getDescriptionId() + ": " + String.valueOf(entry.getValue()));
                    }
                    LOGGER.debug("\u76ee\u6807\u7269\u54c1: " + this.targetItem.getDescriptionId() + " x" + this.synthesisCount);
                    HashMap<Item, Integer> availableForPlanning = new HashMap<Item, Integer>(availableItems);
                    availableForPlanning.put(this.targetItem.getItem(), 0);
                    if (!recipeResolver.hasCraftingRecipe(this.targetItem.getItem())) {
                        TrySynthesisPacket.sendPlayerMessage(serverPlayer, (Component)Component.literal((String)"\u4e00\u952e\u5408\u6210\u5931\u8d25\uff1a\u8be5\u7269\u54c1\u65e0\u5de5\u4f5c\u53f0\u914d\u65b9"));
                        this.sendSynthesisResult(serverPlayer, false, "\u8be5\u7269\u54c1\u65e0\u5de5\u4f5c\u53f0\u914d\u65b9", null);
                        return;
                    }
                    // \u540c\u6b65\u89e3\u6790(\u5e26 2 \u79d2\u8d85\u65f6\u4fdd\u62a4)\u3002\u4e0d\u4f7f\u7528\u540e\u53f0\u7ebf\u7a0b:NeoForge \u914d\u65b9/\u6ce8\u518c\u8868\u4ece\u975e\u4e3b\u7ebf\u7a0b\u8bfb\u53d6\u53ef\u80fd\u4e0d\u53ef\u9760,
                    // \u5bfc\u81f4\u89e3\u6790\u62ff\u4e0d\u5230\u914d\u65b9\u800c\u8bef\u62a5\u5931\u8d25\u3002\u9632\u5361\u6b7b\u5df2\u7531\u5019\u9009\u4e0a\u9650/\u6df1\u5ea6/\u8282\u70b9\u9884\u7b97\u4fdd\u8bc1\u3002
                    recipeResolver.setDeadline(System.currentTimeMillis() + (long)ModConfigs.SYNTHESIS_TIMEOUT_MILLIS.get());
                    ResolutionOutcome outcome = TrySynthesisPacket.resolveOutcome(recipeResolver, this.targetItem.copy(), this.synthesisCount, availableForPlanning);
                    this.applyOutcome(serverPlayer, storageManager, storageData, outcome, recipeResolver);
                }
                catch (Exception e) {
                    LOGGER.error("\u5904\u7406\u5408\u6210\u8bf7\u6c42\u65f6\u53d1\u751f\u9519\u8bef: " + e.getMessage(), (Throwable)e);
                }
            }
        });
    }

    private static ResolutionOutcome resolveOutcome(RecipeResolver resolver, ItemStack targetStack, int count, Map<Item, Integer> availableForPlanning) {
        try {
            List<Map<Item, Integer>> missing = resolver.computeMissingAlternativesForCraftingOnly(targetStack.getItem(), count, availableForPlanning, 3);
            if (resolver.isTimedOut()) {
                return new ResolutionOutcome(null, RecipeResolutionResult.failure("合成解析超时"));
            }
            if (missing != null && !missing.isEmpty()) {
                return new ResolutionOutcome(missing, null);
            }
            resolver.resetResolutionBudget();
            RecipeResolutionResult result = resolver.resolveRecipeCraftingOnly(targetStack, count, availableForPlanning);
            LOGGER.info("合成解析完成,目标: {}, 消耗节点数: {}", targetStack.getHoverName().getString(), resolver.getResolutionNodes());
            if (resolver.isTimedOut()) {
                return new ResolutionOutcome(null, RecipeResolutionResult.failure("合成解析超时"));
            }
            return new ResolutionOutcome(null, result);
        }
        catch (Exception e) {
            LOGGER.error("合成解析异常: " + e.getMessage(), e);
            return new ResolutionOutcome(null, RecipeResolutionResult.failure("合成解析异常"));
        }
    }

    private void applyOutcome(ServerPlayer serverPlayer, CustomStorageManager storageManager, CustomStorageData storageData, ResolutionOutcome outcome, RecipeResolver recipeResolver) {
        try {
            if (outcome == null) {
                return;
            }
            if (recipeResolver.isTimedOut() || (outcome.result != null && outcome.result.getErrorMessage() != null && outcome.result.getErrorMessage().contains("超时"))) {
                TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.literal("一键合成超时：配方解析过于复杂，请减少目标数量或重试"));
                this.sendSynthesisResult(serverPlayer, false, "合成解析超时", null);
                return;
            }
            if (outcome.missing != null && !outcome.missing.isEmpty()) {
                TrySynthesisPacket.sendPlayerMessage(serverPlayer, TrySynthesisPacket.formatMissingAlternatives("一键合成失败，缺少：", outcome.missing));
                Map<Item, Integer> firstMissing = outcome.missing.get(0);
                this.sendSynthesisResult(serverPlayer, false, "缺少材料", firstMissing);
                return;
            }
            RecipeResolutionResult result = outcome.result;
            if (result == null || !result.isSuccess()) {
                Map<Item, Integer> missing = result == null ? null : result.getMissingMaterials();
                if (missing == null || missing.isEmpty()) {
                    missing = result == null ? null : result.getTotalConsumption();
                }
                if (missing != null && !missing.isEmpty()) {
                    TrySynthesisPacket.sendPlayerMessage(serverPlayer, TrySynthesisPacket.formatMissingMessage("一键合成失败，缺少：", missing));
                    this.sendSynthesisResult(serverPlayer, false, "缺少材料", missing);
                    return;
                }
                String error = result == null ? null : result.getErrorMessage();
                if (error != null && !error.isEmpty()) {
                    TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.literal("一键合成失败：" + error));
                    this.sendSynthesisResult(serverPlayer, false, error, null);
                    return;
                }
                TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.literal("一键合成失败：该物品无工作台配方"));
                this.sendSynthesisResult(serverPlayer, false, "该物品无工作台配方", null);
                return;
            }
            List<CraftingStep> steps = result.getCraftingSteps();
            if (!TrySynthesisPacket.validateSteps(steps)) {
                TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.literal("一键合成失败：配方解析异常"));
                this.sendSynthesisResult(serverPlayer, false, "配方解析异常", null);
                return;
            }
            CompoundTag backup = storageData.toNBT();
            CraftingStep lastStep = steps.get(steps.size() - 1);
            String outputKey = this.generateItemKey(lastStep.getOutputPrototype());
            long beforeOutput = storageData.getItemCount(outputKey);
            try {
                for (CraftingStep step : steps) {
                    this.executeStep(storageData, step);
                }
            }
            catch (SynthesisFailure failure) {
                storageData.fromNBT(backup);
                storageManager.setDirty();
                if (failure.missing != null && !failure.missing.isEmpty()) {
                    TrySynthesisPacket.sendPlayerMessage(serverPlayer, TrySynthesisPacket.formatMissingMessage("一键合成失败，缺少：", failure.missing));
                    this.sendSynthesisResult(serverPlayer, false, "缺少材料", failure.missing);
                } else {
                    TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.literal("一键合成失败：缺少材料"));
                    this.sendSynthesisResult(serverPlayer, false, "缺少材料", null);
                }
                storageManager.forceSync(serverPlayer);
                return;
            }
            long afterOutput = storageData.getItemCount(outputKey);
            int expectedAdded = lastStep.getOutputCount();
            if (expectedAdded <= 0 || afterOutput - beforeOutput < (long)expectedAdded) {
                storageData.fromNBT(backup);
                storageManager.setDirty();
                TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.literal("一键合成失败：缺少材料"));
                this.sendSynthesisResult(serverPlayer, false, "缺少材料", null);
                storageManager.forceSync(serverPlayer);
                return;
            }
            storageManager.forceSync(serverPlayer);
            storageManager.setDirty();
            int producedCount = steps.get(steps.size() - 1).getOutputCount();
            TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.literal("一键合成成功：").append(this.targetItem.getHoverName()).append(Component.literal(" x" + producedCount)));
            this.sendSynthesisResult(serverPlayer, true, "合成成功", null);
            LOGGER.debug("一键合成完成，已同步数据到客户端并标记为需要保存");
        }
        catch (Exception e) {
            LOGGER.error("处理合成结果时发生错误: " + e.getMessage(), e);
        }
    }

    private static final class ResolutionOutcome {
        private final List<Map<Item, Integer>> missing;
        private final RecipeResolutionResult result;

        private ResolutionOutcome(List<Map<Item, Integer>> missing, RecipeResolutionResult result) {
            this.missing = missing;
            this.result = result;
        }
    }

    /** 把合成结果发到客户端,让 GUI 在配方栏内直接显示缺失材料/错误,无需关界面看聊天栏。 */
    private void sendSynthesisResult(ServerPlayer player, boolean success, String message, Map<Item, Integer> missingItems) {
        HashMap<String, Long> missing = new HashMap<String, Long>();
        if (missingItems != null) {
            for (Map.Entry<Item, Integer> e : missingItems.entrySet()) {
                if (e.getKey() == null) continue;
                missing.put(BuiltInRegistries.ITEM.getKey(e.getKey()).toString(), (long)(e.getValue() == null ? 0 : e.getValue().intValue()));
            }
        }
        NetworkManager.sendToPlayer(player, new SynthesisResultPacket(success, message == null ? "" : message, missing));
    }

    private void executeStep(CustomStorageData storageData, CraftingStep step) {
        try {
            List<String> keys;
            int requiredCount;
            Item material;
            LOGGER.debug("\u6267\u884c\u5408\u6210\u6b65\u9aa4: " + step.getOutputItem().getDescriptionId() + " x" + step.getOutputCount());
            Map<Item, Integer> req0 = step.getRequiredMaterials();
            LOGGER.debug("\u9700\u8981\u7684\u6750\u6599\u6570\u91cf: " + (req0 == null ? 0 : req0.size()));
            if (req0 == null || req0.isEmpty()) {
                throw new SynthesisFailure(null);
            }
            for (Map.Entry<Item, Integer> entry : req0.entrySet()) {
                material = entry.getKey();
                requiredCount = entry.getValue();
                if (requiredCount <= 0) {
                    throw new SynthesisFailure(null);
                }
                keys = this.findAllMatchingItemKeys(storageData, material);
                long availableCount = this.countAvailableAcrossKeys(storageData, keys);
                if (availableCount >= (long)requiredCount) continue;
                HashMap<Item, Integer> missing = new HashMap<Item, Integer>();
                missing.put(material, (int)Math.min((long)requiredCount - availableCount, Integer.MAX_VALUE));
                throw new SynthesisFailure(missing);
            }
            for (Map.Entry<Item, Integer> entry : req0.entrySet()) {
                long consumed;
                material = entry.getKey();
                requiredCount = entry.getValue();
                keys = this.findAllMatchingItemKeys(storageData, material);
                long before = this.countAvailableAcrossKeys(storageData, keys);
                if (!this.consumeAcrossKeys(storageData, keys, requiredCount)) {
                    long currentTotal = this.countAvailableAcrossKeys(storageData, keys);
                    HashMap<Item, Integer> missing = new HashMap<Item, Integer>();
                    missing.put(material, (int)Math.min((long)requiredCount - currentTotal, Integer.MAX_VALUE));
                    throw new SynthesisFailure(missing);
                }
                long after = this.countAvailableAcrossKeys(storageData, keys);
                if (before == Long.MAX_VALUE || after == Long.MAX_VALUE || (consumed = before - after) >= (long)requiredCount) continue;
                HashMap<Item, Integer> missing = new HashMap<Item, Integer>();
                missing.put(material, (int)Math.min((long)requiredCount - consumed, Integer.MAX_VALUE));
                throw new SynthesisFailure(missing);
            }
            int outputCount = step.getOutputCount();
            ItemStack outputPrototype = step.getOutputPrototype();
            this.addOutputToStorage(storageData, outputPrototype, outputCount);
            LOGGER.debug("\u751f\u6210\u7269\u54c1: " + step.getOutputItem().getDescriptionId() + " x" + outputCount);
        }
        catch (SynthesisFailure failure) {
            throw failure;
        }
        catch (Exception e) {
            LOGGER.error("\u6267\u884c\u5408\u6210\u6b65\u9aa4\u5931\u8d25: " + e.getMessage(), (Throwable)e);
            throw new SynthesisFailure(null);
        }
    }

    private static void sendPlayerMessage(ServerPlayer player, Component message) {
        if (player == null || message == null) {
            return;
        }
        player.sendSystemMessage(message);
    }

    private static Component formatMissingAlternatives(String prefix, List<Map<Item, Integer>> alternatives) {
        MutableComponent msg = Component.literal((String)(prefix == null ? "" : prefix));
        if (alternatives == null || alternatives.isEmpty()) {
            return msg;
        }
        int shown = 0;
        for (Map<Item, Integer> missing : alternatives) {
            if (missing == null || missing.isEmpty()) continue;
            if (shown > 0) {
                msg.append((Component)Component.literal((String)"\n"));
            }
            msg.append((Component)Component.literal((String)("\u8def\u5f84" + (shown + 1) + "\uff1a")));
            msg.append(TrySynthesisPacket.formatMissingList(missing));
            ++shown;
        }
        return msg;
    }

    private static Component formatMissingMessage(String prefix, Map<Item, Integer> missing) {
        MutableComponent msg = Component.literal((String)(prefix == null ? "" : prefix));
        msg.append(TrySynthesisPacket.formatMissingList(missing));
        return msg;
    }

    private static Component formatMissingList(Map<Item, Integer> missing) {
        if (missing == null || missing.isEmpty()) {
            return Component.literal((String)"");
        }
        MutableComponent msg = Component.literal((String)"");
        ArrayList<Map.Entry<Item, Integer>> entries = new ArrayList<Map.Entry<Item, Integer>>(missing.entrySet());
        entries.removeIf(e -> e.getKey() == null || e.getValue() == null || (Integer)e.getValue() <= 0);
        entries.sort((a, b) -> {
            int cmp = Integer.compare((Integer)b.getValue(), (Integer)a.getValue());
            if (cmp != 0) {
                return cmp;
            }
            return ((Item)a.getKey()).getDescriptionId().compareTo(((Item)b.getKey()).getDescriptionId());
        });
        boolean first = true;
        for (Map.Entry entry : entries) {
            if (!first) {
                msg.append((Component)Component.literal((String)"\uff0c"));
            }
            msg.append((Component)Component.translatable((String)((Item)entry.getKey()).getDescriptionId())).append((Component)Component.literal((String)(" x" + String.valueOf(entry.getValue()))));
            first = false;
        }
        return msg;
    }

    private static boolean keyMatchesBaseItem(String storedKey, String baseItemId) {
        return storedKey.equals(baseItemId) || storedKey.startsWith(baseItemId + "#");
    }

    private List<String> findAllMatchingItemKeys(CustomStorageData storageData, Item targetItem) {
        String baseItemId = BuiltInRegistries.ITEM.getKey(targetItem).toString();
        ArrayList<String> keys = new ArrayList<String>();
        String exactKey = this.generateItemKey(new ItemStack((ItemLike)targetItem));
        if (storageData.getItemCount(exactKey) > 0L) {
            keys.add(exactKey);
        }
        for (String storedKey : storageData.getItemOrder()) {
            if (storedKey.equals(exactKey) || !TrySynthesisPacket.keyMatchesBaseItem(storedKey, baseItemId) || storageData.getItemCount(storedKey) <= 0L) continue;
            keys.add(storedKey);
        }
        for (String storedKey : storageData.getAllItems().keySet()) {
            if (storedKey.equals(exactKey) || !TrySynthesisPacket.keyMatchesBaseItem(storedKey, baseItemId) || storageData.getItemCount(storedKey) <= 0L || keys.contains(storedKey)) continue;
            keys.add(storedKey);
        }
        return keys;
    }

    private long countAvailableAcrossKeys(CustomStorageData storageData, List<String> keys) {
        long total = 0L;
        for (String key : keys) {
            long v = storageData.getItemCount(key);
            if (v <= 0L) continue;
            if (Long.MAX_VALUE - total < v) {
                return Long.MAX_VALUE;
            }
            total += v;
        }
        return total;
    }

    private boolean consumeAcrossKeys(CustomStorageData storageData, List<String> keys, long required) {
        if (required < 0L) {
            return false;
        }
        long remaining = required;
        for (String key : keys) {
            if (remaining <= 0L) break;
            long have = storageData.getItemCount(key);
            if (have <= 0L) continue;
            long take = Math.min(have, remaining);
            storageData.removeItem(key, take);
            remaining -= take;
        }
        return remaining <= 0L;
    }

    private void addOutputToStorage(CustomStorageData storageData, ItemStack outputPrototype, int outputCount) {
        if (storageData == null || outputPrototype == null || outputPrototype.isEmpty() || outputCount <= 0) {
            return;
        }
        String key = this.generateItemKey(outputPrototype);
        int maxStackSize = Math.max(1, outputPrototype.getMaxStackSize());
        int firstCount = Math.min(outputCount, maxStackSize);
        ItemStack first = outputPrototype.copy();
        first.setCount(firstCount);
        storageData.addItem(first);
        int remaining = outputCount - firstCount;
        if (remaining > 0) {
            storageData.addItem(key, remaining);
        }
    }

    private String generateItemKey(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return "";
        }
        return CustomStorageData.getItemKey(itemStack);
    }

    private static boolean validateSteps(List<CraftingStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return false;
        }
        for (CraftingStep step : steps) {
            if (step == null) {
                return false;
            }
            ItemStack proto = step.getOutputPrototype();
            if (proto == null || proto.isEmpty()) {
                return false;
            }
            int out = step.getOutputCount();
            if (out <= 0) {
                return false;
            }
            if (out > 10000000) {
                return false;
            }
            Map<Item, Integer> req = step.getRequiredMaterials();
            if (req == null || req.isEmpty()) {
                return false;
            }
            for (Map.Entry<Item, Integer> e : req.entrySet()) {
                if (e == null) {
                    return false;
                }
                if (e.getKey() == null) {
                    return false;
                }
                Integer c = e.getValue();
                if (c != null && c > 0) continue;
                return false;
            }
        }
        return true;
    }

    private static CraftingStep.StepType findFirstUnsupportedStepType(List<CraftingStep> steps) {
        if (steps == null) {
            return null;
        }
        for (CraftingStep step : steps) {
            CraftingStep.StepType t;
            if (step == null || (t = step.getStepType()) == CraftingStep.StepType.CRAFTING) continue;
            return t;
        }
        return null;
    }

    private static String stepTypeToChinese(CraftingStep.StepType type) {
        if (type == null) {
            return "\u672a\u77e5";
        }
        return switch (type) {
            default -> throw new MatchException(null, null);
            case CraftingStep.StepType.CRAFTING -> "\u5de5\u4f5c\u53f0\u5408\u6210";
            case CraftingStep.StepType.SMELTING -> "\u7194\u7089";
            case CraftingStep.StepType.BLASTING -> "\u9ad8\u7089";
            case CraftingStep.StepType.SMOKING -> "\u70df\u718f\u7089";
            case CraftingStep.StepType.STONECUTTING -> "\u5207\u77f3\u673a";
            case CraftingStep.StepType.SMITHING -> "\u953b\u9020\u53f0";
            case CraftingStep.StepType.DIRECT_USE -> "\u76f4\u63a5\u4f7f\u7528\u73b0\u6709\u7269\u54c1";
        };
    }

    private static final class SynthesisFailure
    extends RuntimeException {
        private final Map<Item, Integer> missing;

        private SynthesisFailure(Map<Item, Integer> missing) {
            this.missing = missing;
        }
    }
}

