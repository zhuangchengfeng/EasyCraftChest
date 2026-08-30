package com.stroeud.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组 TOML 配置。注册后生成到 config/storageandoneclicksynthesis.toml。
 * 相关参数可在游戏内/文件里调整并热重载(服务端类型)。
 */
public final class ModConfigs {
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue SYNTHESIS_TIMEOUT_MILLIS = BUILDER
        .comment(
            "一键合成配方解析的超时时间(毫秒)。",
            "超过后立即放弃解析并提示玩家\"解析超时\",防止复杂配方(如羊毛染色等密集图)卡住服务端主线程。",
            "默认 2000(2 秒)。调大允许更复杂的配方解析,但会增加卡顿风险。")
        .defineInRange("synthesisTimeoutMillis", 2000, 200, 30000);

    public static final ModConfigSpec.IntValue MAX_RESOLUTION_NODES = BUILDER
        .comment(
            "配方解析的节点预算上限(每个顶层配方独立)。",
            "解析时每个递归步骤/候选都会计数,超过上限立即中止该配方并跳到下一个。",
            "防止密集配方图(如染色)指数爆炸导致卡死,且不连累后续可成功的配方。",
            "默认 1000。正常配方解析通常几十~几百个节点,若你的整合包很大可适当调大。")
        .defineInRange("maxResolutionNodes", 1000, 100, 100000);

    public static final ModConfigSpec.IntValue MAX_INGREDIENT_CANDIDATES = BUILDER
        .comment(
            "每个原料标签(如 #minecraft:logs、#minecraft:wool)最多尝试的候选物品数量。",
            "按\"仓库已有的优先\"排序,所以正常情况不受影响;只影响标签候选极多时的搜索规模。",
            "默认 12。")
        .defineInRange("maxIngredientCandidates", 12, 2, 64);

    public static final ModConfigSpec.IntValue MAX_SYNTHESIS_DEPTH = BUILDER
        .comment(
            "合成链的最大递归深度。",
            "正常配方链(如 原木→木板→木棍→梯子)通常 3~5 层,默认 8 已很宽裕。",
            "调小可进一步降低最坏情况搜索量。")
        .defineInRange("maxSynthesisDepth", 8, 2, 20);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ModConfigs() {
    }
}
