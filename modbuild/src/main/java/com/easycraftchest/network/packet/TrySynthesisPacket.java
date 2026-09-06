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
package com.easycraftchest.network.packet;

import com.mojang.logging.LogUtils;
import com.easycraftchest.block.CraftChestBlock;
import com.easycraftchest.config.ModConfigs;
import com.easycraftchest.network.NetworkManager;
import com.easycraftchest.server.recipe.CraftingStep;
import com.easycraftchest.server.recipe.RecipeResolutionResult;
import com.easycraftchest.server.recipe.RecipeResolver;
import com.easycraftchest.server.storage.CraftChestManager;
import com.easycraftchest.storage.CraftChestData;
import java.util.ArrayList;
import java.util.Collections;
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

public record TrySynthesisPacket(ItemStack targetItem, BlockPos storagePos, int synthesisCount, boolean depositToPlayer) implements CustomPacketPayload
{
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final CustomPacketPayload.Type<TrySynthesisPacket> TYPE = new CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath((String)"easycraftchest", (String)"try_synthesis"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrySynthesisPacket> STREAM_CODEC = StreamCodec.ofMember(TrySynthesisPacket::write, TrySynthesisPacket::new);

    public TrySynthesisPacket(RegistryFriendlyByteBuf buf) {
        this(ItemStack.STREAM_CODEC.decode(buf), buf.readBlockPos(), buf.readInt(), buf.readBoolean());
    }

    public void write(RegistryFriendlyByteBuf buf) {
        ItemStack.STREAM_CODEC.encode(buf, this.targetItem);
        buf.writeBlockPos(this.storagePos);
        buf.writeInt(this.synthesisCount);
        buf.writeBoolean(this.depositToPlayer);
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
                    CraftChestManager storageManager = CraftChestManager.get(serverPlayer.serverLevel());
                    BlockPos openPos = storageManager.getPlayerOpenStorage(serverPlayer.getUUID());
                    boolean bl = isOpenThisStorage = openPos != null && openPos.equals((Object)this.storagePos);
                    if (!serverPlayer.serverLevel().isLoaded(this.storagePos)) {
                        TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.translatable("message.synthesis.fail_unloaded"));
                        return;
                    }
                    if (!isOpenThisStorage) {
                        double d;
                        double dy;
                        if (!(serverPlayer.serverLevel().getBlockState(this.storagePos).getBlock() instanceof CraftChestBlock)) {
                            TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.translatable("message.synthesis.fail_not_storage"));
                            return;
                        }
                        double dx = serverPlayer.getX() - ((double)this.storagePos.getX() + 0.5);
                        double distSq = dx * dx + (dy = serverPlayer.getY() - ((double)this.storagePos.getY() + 0.5)) * dy + (d = serverPlayer.getZ() - ((double)this.storagePos.getZ() + 0.5)) * d;
                        if (distSq > 64.0) {
                            TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.translatable("message.synthesis.fail_too_far"));
                            return;
                        }
                    }
                    CraftChestData storageData = storageManager.getOrCreateStorage(this.storagePos, serverPlayer.serverLevel());
                    storageData.setRegistryAccess((HolderLookup.Provider)serverPlayer.serverLevel().registryAccess());
                    RecipeResolver recipeResolver = new RecipeResolver((Level)serverPlayer.serverLevel());
                    HashMap<Item, Integer> availableItems = new HashMap<Item, Integer>();
                    LOGGER.trace("\u5b58\u50a8\u5bb9\u5668\u4e2d\u7684\u6240\u6709\u7269\u54c1:");
                    for (Map.Entry<String, Long> entry : storageData.getAllItems().entrySet()) {
                        String itemKey = entry.getKey();
                        long count = entry.getValue();
                        LOGGER.trace("  - " + itemKey + ": " + count);
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
                            LOGGER.trace("    \u89e3\u6790\u4e3a: " + item.getDescriptionId() + " \u7d2f\u8ba1\u6570\u91cf: " + newCount);
                        }
                        catch (Exception e) {
                            LOGGER.error("\u89e3\u6790\u7269\u54c1\u952e\u503c\u5931\u8d25: " + itemKey, (Throwable)e);
                        }
                    }
                    LOGGER.trace("\u6700\u7ec8\u53ef\u7528\u7269\u54c1\u6620\u5c04:");
                    for (Map.Entry<Item, Integer> entry : availableItems.entrySet()) {
                        LOGGER.trace("  - " + ((Item)entry.getKey()).getDescriptionId() + ": " + String.valueOf(entry.getValue()));
                    }
                    LOGGER.trace("\u76ee\u6807\u7269\u54c1: " + this.targetItem.getDescriptionId() + " x" + this.synthesisCount);
                    HashMap<Item, Integer> availableForPlanning = new HashMap<Item, Integer>(availableItems);
                    availableForPlanning.put(this.targetItem.getItem(), 0);
                    if (!recipeResolver.hasCraftingRecipe(this.targetItem.getItem())) {
                        TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.translatable("message.synthesis.fail_no_recipe"));
                        this.sendSynthesisResult(serverPlayer, false, "\u8be5\u7269\u54c1\u65e0\u5de5\u4f5c\u53f0\u914d\u65b9", null);
                        return;
                    }
                    // \u540c\u6b65\u89e3\u6790(\u5e26 2 \u79d2\u8d85\u65f6\u4fdd\u62a4)\u3002\u4e0d\u4f7f\u7528\u540e\u53f0\u7ebf\u7a0b:NeoForge \u914d\u65b9/\u6ce8\u518c\u8868\u4ece\u975e\u4e3b\u7ebf\u7a0b\u8bfb\u53d6\u53ef\u80fd\u4e0d\u53ef\u9760,
                    // \u5bfc\u81f4\u89e3\u6790\u62ff\u4e0d\u5230\u914d\u65b9\u800c\u8bef\u62a5\u5931\u8d25\u3002\u9632\u5361\u6b7b\u5df2\u7531\u5019\u9009\u4e0a\u9650/\u6df1\u5ea6/\u8282\u70b9\u9884\u7b97\u4fdd\u8bc1\u3002
                    recipeResolver.setDeadline(System.currentTimeMillis() + (long)ModConfigs.SYNTHESIS_TIMEOUT_MILLIS.get());
                    ResolutionOutcome outcome = TrySynthesisPacket.resolveOutcome(recipeResolver, this.targetItem.copy(), this.synthesisCount, availableForPlanning);
                    this.applyOutcome(serverPlayer, storageManager, storageData, outcome, recipeResolver, availableItems);
                }
                catch (Exception e) {
                    LOGGER.error("\u5904\u7406\u5408\u6210\u8bf7\u6c42\u65f6\u53d1\u751f\u9519\u8bef: " + e.getMessage(), (Throwable)e);
                }
            }
        });
    }

    private static ResolutionOutcome resolveOutcome(RecipeResolver resolver, ItemStack targetStack, int count, Map<Item, Integer> availableForPlanning) {
        try {
            // 直接先尝试真实合成:解析器按"可行代表优先"找路径,通常只要几十~几百个节点。
            // 以前先跑全量缺料预检(computeMissingAlternatives)来挑选展示用路径,复杂配方
            // (house 这类内嵌 tag/多配方中间件)会把节点预算耗尽、误回退成"缺目标本身",
            // 导致明明能合却提示缺。所以把"先合成"提到最前。
            resolver.resetResolutionBudget();
            RecipeResolutionResult result = resolver.resolveRecipeCraftingOnly(targetStack, count, availableForPlanning);
            TrySynthesisPacket.logInfo("合成解析完成,目标: {}, 消耗节点数: {}, 结果: {}", targetStack.getHoverName().getString(), resolver.getResolutionNodes(), result == null ? "null" : (result.isSuccess() ? "成功" : "失败"));
            if (result == null) {
                return new ResolutionOutcome(null, RecipeResolutionResult.failure("合成解析异常"));
            }
            if (result.isSuccess()) {
                return new ResolutionOutcome(null, result);
            }
            boolean timedOut = resolver.isTimedOut() || (result.getErrorMessage() != null && result.getErrorMessage().contains("超时"));
            if (timedOut) {
                return new ResolutionOutcome(null, RecipeResolutionResult.failure("合成解析超时"));
            }
            // 合成失败且没超时 = 真的缺材料。用"正向链缺料估算"给出真正的基础物缺料:
            // 石头不足 → 缺 石头;没羊毛做床 → 稳定缺 线/白羊毛,而不是 玫瑰丛/染料 来回变。
            // 该估算只在合成失败时运行(预算封顶),成功路径不受影响。
            // 例外:"无视 tag"开启时,tag 原料已被跳过、不参与合成,也不该在缺料里出现,
            //        所以直接返回真实合成(非 tag)自带的缺失,不再跑会把 tag 链算进来的估算。
            boolean ignoreTags = ModConfigs.IGNORE_TAG_INGREDIENTS.get();
            if (!ignoreTags) {
                Map<Item, Integer> leaves = resolver.computeBaseShortage(targetStack.getItem(), count, availableForPlanning);
                if (leaves != null && !leaves.isEmpty()) {
                    TrySynthesisPacket.logInfo("正向链缺料估算: {}", TrySynthesisPacket.missingToString(leaves));
                    return new ResolutionOutcome(null, RecipeResolutionResult.failure("缺少材料", leaves));
                }
                TrySynthesisPacket.logInfo("正向链缺料估算为空(预算不足?), 退回合成缺失: {}", TrySynthesisPacket.missingToString(TrySynthesisPacket.resultMissing(result)));
            } else {
                TrySynthesisPacket.logInfo("无视tag模式: 跳过缺料估算, 直接报合成缺失: {}", TrySynthesisPacket.missingToString(TrySynthesisPacket.resultMissing(result)));
            }
            // 估算超预算时,退回合成失败自身携带的缺失作为提示。
            Map<Item, Integer> miss = result.getMissingMaterials();
            if (miss == null || miss.isEmpty()) {
                miss = result.getTotalConsumption();
            }
            return new ResolutionOutcome(null, result);
        }
        catch (Exception e) {
            LOGGER.error("合成解析异常: " + e.getMessage(), e);
            return new ResolutionOutcome(null, RecipeResolutionResult.failure("合成解析异常"));
        }
    }

    private void applyOutcome(ServerPlayer serverPlayer, CraftChestManager storageManager, CraftChestData storageData, ResolutionOutcome outcome, RecipeResolver recipeResolver, Map<Item, Integer> availableItems) {
        try {
            if (outcome == null) {
                return;
            }
            if (recipeResolver.isTimedOut() || (outcome.result != null && outcome.result.getErrorMessage() != null && outcome.result.getErrorMessage().contains("超时"))) {
                // 超时时别让玩家看到空白:用一次不递归的直接缺口估算给出"缺什么"。
                // 若估算也为空(说明原料充足、纯属链条过深),才提示超时请减量/重试。
                Map<Item, Integer> fallbackMissing = recipeResolver.estimateTopLevelMissing(this.targetItem.getItem(), this.synthesisCount, availableItems);
                if (fallbackMissing != null && !fallbackMissing.isEmpty()) {
                    TrySynthesisPacket.sendPlayerMessage(serverPlayer, TrySynthesisPacket.formatMissingMessage(Component.translatable("message.synthesis.fail_missing"), fallbackMissing));
                    this.sendSynthesisResult(serverPlayer, false, "缺少材料", fallbackMissing);
                } else {
                    TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.translatable("message.synthesis.timeout"));
                    this.sendSynthesisResult(serverPlayer, false, "合成解析超时", null);
                }
                return;
            }
            if (outcome.missing != null && !outcome.missing.isEmpty()) {
                TrySynthesisPacket.sendPlayerMessage(serverPlayer, TrySynthesisPacket.formatMissingAlternatives(Component.translatable("message.synthesis.fail_missing"), outcome.missing));
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
                    TrySynthesisPacket.sendPlayerMessage(serverPlayer, TrySynthesisPacket.formatMissingMessage(Component.translatable("message.synthesis.fail_missing"), missing));
                    this.sendSynthesisResult(serverPlayer, false, "缺少材料", missing);
                    return;
                }
                String error = result == null ? null : result.getErrorMessage();
                if (error != null && !error.isEmpty()) {
                    TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.translatable("message.synthesis.fail_prefix").append(error));
                    this.sendSynthesisResult(serverPlayer, false, error, null);
                    return;
                }
                TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.translatable("message.synthesis.fail_no_recipe"));
                this.sendSynthesisResult(serverPlayer, false, "该物品无工作台配方", null);
                return;
            }
            List<CraftingStep> steps = result.getCraftingSteps();
            if (!TrySynthesisPacket.validateSteps(steps)) {
                TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.translatable("message.synthesis.fail_parse"));
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
                    TrySynthesisPacket.sendPlayerMessage(serverPlayer, TrySynthesisPacket.formatMissingMessage(Component.translatable("message.synthesis.fail_missing"), failure.missing));
                    this.sendSynthesisResult(serverPlayer, false, "缺少材料", failure.missing);
                } else {
                    TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.translatable("message.synthesis.fail_materials"));
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
                TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.translatable("message.synthesis.fail_materials"));
                this.sendSynthesisResult(serverPlayer, false, "缺少材料", null);
                storageManager.forceSync(serverPlayer);
                return;
            }
            // O 模式:成品优先进玩家背包,装不下的部分留在仓库
            this.depositToPlayerIfRequested(serverPlayer, storageData, steps);
            // 服务端权威记录:该方块"合成过哪个物品、谁合成的、何时"(每物品一条,存方块存档数据)。
            if (this.targetItem != null && !this.targetItem.isEmpty() && this.targetItem.getItem() != net.minecraft.world.item.Items.AIR) {
                String historyKey = BuiltInRegistries.ITEM.getKey(this.targetItem.getItem()).toString();
                storageData.recordSynthesis(historyKey, serverPlayer.getName().getString(), serverPlayer.getStringUUID(), this.synthesisCount);
            }
            storageManager.forceSyncAllViewers(serverPlayer);
            // 合成历史也推给所有正打开该方块的玩家,保证多人在看时实时更新。
            storageManager.pushSynthesisHistoryToViewers(serverPlayer);
            storageManager.setDirty();
            int producedCount = steps.get(steps.size() - 1).getOutputCount();
            TrySynthesisPacket.sendPlayerMessage(serverPlayer, Component.translatable("message.synthesis.success").append(this.targetItem.getHoverName()).append(Component.literal(" x" + producedCount)));
            this.sendSynthesisResult(serverPlayer, true, "message.synthesis.success", null);
            LOGGER.trace("一键合成完成，已同步数据到客户端并标记为需要保存");
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

    /** O 模式:成品尽量塞进玩家背包(用 Inventory.add);塞不下的留在仓库(绝不凭空消失)。
        创造模式会自动忽略 O(产物进仓库),因创造下背包计数/新增表现不可靠。 */
    private void depositToPlayerIfRequested(ServerPlayer player, CraftChestData storageData, List<CraftingStep> steps) {
        if (!this.depositToPlayer || steps == null || steps.isEmpty()) {
            return;
        }
        if (player.getAbilities().instabuild) {
            // 创造模式:忽略 O,成品留在仓库
            TrySynthesisPacket.sendPlayerMessage(player, Component.translatable("message.synthesis.creative_ignore_o"));
            return;
        }
        CraftingStep last = steps.get(steps.size() - 1);
        ItemStack proto = last.getOutputPrototype();
        if (proto.isEmpty()) {
            return;
        }
        int out = last.getOutputCount();
        if (out <= 0) {
            return;
        }
        int max = Math.max(1, proto.getMaxStackSize());
        ItemStack base = proto.copy();
        base.setCount(1);

        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        int given = 0;
        int remaining = out;
        while (remaining > 0) {
            int put = Math.min(max, remaining);
            ItemStack s = base.copy();
            s.setCount(put);
            if (!inv.add(s)) {
                break; // 装不下整组 → 停止,剩余留在仓库
            }
            given += put;
            remaining -= put;
        }
        if (given > 0) {
            // 从仓库移除刚加的 given(成品),库存净增 = out - given(即回落部分)
            this.consumeBaseFromStorage(storageData, proto.getItem(), given);
        }
        if (given < out) {
            TrySynthesisPacket.sendPlayerMessage(player, Component.translatable("message.synthesis.deposit_overflow", given, out - given));
        } else {
            TrySynthesisPacket.sendPlayerMessage(player, Component.translatable("message.synthesis.deposited", given));
        }
        if (player.containerMenu != null) {
            player.containerMenu.broadcastChanges();
        }
        // 也刷主背包菜单,确保客户端一定看到数量变化(即便叠进已有大堆)
        player.inventoryMenu.broadcastChanges();
    }

    /** 从仓库按基础物品 id 移除 amount 个(跨相同基础 id 的键)。 */
    private void consumeBaseFromStorage(CraftChestData storageData, net.minecraft.world.item.Item material, int amount) {
        if (storageData == null || material == null || amount <= 0) {
            return;
        }
        List<String> keys = this.findAllMatchingItemKeys(storageData, material);
        long remaining = amount;
        for (String key : keys) {
            if (remaining <= 0) break;
            long have = storageData.getItemCount(key);
            if (have <= 0) continue;
            long take = Math.min(have, remaining);
            storageData.removeItem(key, take);
            remaining -= take;
        }
    }

    private void executeStep(CraftChestData storageData, CraftingStep step) {
        try {
            List<String> keys;
            int requiredCount;
            Item material;
            LOGGER.trace("\u6267\u884c\u5408\u6210\u6b65\u9aa4: " + step.getOutputItem().getDescriptionId() + " x" + step.getOutputCount());
            Map<Item, Integer> req0 = step.getRequiredMaterials();
            LOGGER.trace("\u9700\u8981\u7684\u6750\u6599\u6570\u91cf: " + (req0 == null ? 0 : req0.size()));
            boolean allowEmptyMats = ModConfigs.IGNORE_TAG_INGREDIENTS.get();
            if ((req0 == null || req0.isEmpty()) && !allowEmptyMats) {
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
            LOGGER.trace("\u751f\u6210\u7269\u54c1: " + step.getOutputItem().getDescriptionId() + " x" + outputCount);
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

    private static Component formatMissingAlternatives(Component prefix, List<Map<Item, Integer>> alternatives) {
        MutableComponent msg = prefix == null ? Component.literal("") : prefix.copy();
        if (alternatives == null || alternatives.isEmpty()) {
            return msg;
        }
        int shown = 0;
        for (Map<Item, Integer> missing : alternatives) {
            if (missing == null || missing.isEmpty()) continue;
            if (shown > 0) {
                msg.append(Component.literal("\n"));
            }
            msg.append(Component.translatable("message.synthesis.path", shown + 1));
            msg.append(TrySynthesisPacket.formatMissingList(missing));
            ++shown;
        }
        return msg;
    }

    private static Component formatMissingMessage(Component prefix, Map<Item, Integer> missing) {
        MutableComponent msg = prefix == null ? Component.literal("") : prefix.copy();
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
            msg.append(Component.translatable("message.synthesis.missing_item", Component.translatable(((Item)entry.getKey()).getDescriptionId()), entry.getValue()));
            first = false;
        }
        return msg;
    }

    /** 合成解析/缺料等逐次合成明细:仅在开发环境加 -Decc.trace=true 时以 TRACE 输出。
        正常游玩不加该参数 → 这些行既不打印也不写日志文件(避免刷屏/文件膨胀)。 */
    private static void logInfo(String format, Object... args) {
        if (Boolean.parseBoolean(System.getProperty("ecc.trace", "false"))) {
            LOGGER.trace(format, args);
        }
    }

    /** 从失败结果里取出缺失映射(优先 missingMaterials,否则 totalConsumption)。 */
    private static Map<Item, Integer> resultMissing(RecipeResolutionResult result) {
        if (result == null) {
            return Collections.emptyMap();
        }
        Map<Item, Integer> miss = result.getMissingMaterials();
        if (miss == null || miss.isEmpty()) {
            miss = result.getTotalConsumption();
        }
        return miss == null ? Collections.emptyMap() : miss;
    }

    /** 把缺失映射转成简短可读文本(最多列 6 项),用于诊断日志。 */
    private static String missingToString(Map<Item, Integer> missing) {
        if (missing == null || missing.isEmpty()) {
            return "(空)";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<Item, Integer> e : missing.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0) continue;
            if (shown >= 6) {
                sb.append(", …");
                break;
            }
            if (shown > 0) sb.append(", ");
            sb.append(e.getKey().getDescriptionId()).append(" x").append(e.getValue());
            ++shown;
        }
        return sb.toString();
    }

    private static boolean keyMatchesBaseItem(String storedKey, String baseItemId) {
        return storedKey.equals(baseItemId) || storedKey.startsWith(baseItemId + "#");
    }

    private List<String> findAllMatchingItemKeys(CraftChestData storageData, Item targetItem) {
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

    private long countAvailableAcrossKeys(CraftChestData storageData, List<String> keys) {
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

    private boolean consumeAcrossKeys(CraftChestData storageData, List<String> keys, long required) {
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

    private void addOutputToStorage(CraftChestData storageData, ItemStack outputPrototype, int outputCount) {
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
        return CraftChestData.getItemKey(itemStack);
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
                // 开启"无视 tag"时允许无材料的制造步(整组 tag 被跳过后会出现)。
                if (!ModConfigs.IGNORE_TAG_INGREDIENTS.get()) {
                    return false;
                }
            } else {
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

