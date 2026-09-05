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
            "自 1.0.3 起标签组已做'代表成员+递归封顶',合法深链不再轻易打满预算,故默认放宽到 8000。",
            "正常配方解析通常几十~几百个节点,若你的整合包很大可适当调大。")
        .defineInRange("maxResolutionNodes", 8000, 100, 100000);

    public static final ModConfigSpec.IntValue MAX_INGREDIENT_CANDIDATES = BUILDER
        .comment(
            "每个原料标签(如 #minecraft:logs、#minecraft:wool)最多尝试的候选物品数量。",
            "按\"仓库已有的优先\"排序,所以正常情况不受影响;只影响标签候选极多时的搜索规模。",
            "默认 12。")
        .defineInRange("maxIngredientCandidates", 12, 2, 64);

    public static final ModConfigSpec.BooleanValue IGNORE_TAG_INGREDIENTS = BUILDER
        .comment(
            "合成时是否无视 tag 原料(默认关)。",
            "开启后:配方里凡是 tag/多可选项的原料格(如 #minecraft:beds 的床、#minecraft:planks 的木板、#c:compressed_stone)一律不参与合成——",
            "不造、也不从仓库扣它们;整条合成链都如此。效果是很多依赖 tag 中间物的物品会近乎免费被造出,",
            "通常仅用于调试/省事,正式游玩不建议开启。",
            "注意:纯 tag 组成的配方会在开启时被直接产出(无材料消耗)。",
            "",
            "Whether to IGNORE tag ingredients when synthesizing (default false).",
            "When true: any ingredient slot that is a tag / has multiple choices (e.g. the bed of #minecraft:beds,",
            "#minecraft:planks, #c:compressed_stone) is skipped entirely — neither crafted nor deducted from storage.",
            "This applies across the whole synthesis chain, so many items depending on tag-driven intermediates are",
            "produced almost for free. Intended for debugging/convenience only, not for normal play.",
            "Note: recipes made purely of tag slots will be produced with no material cost while this is on.")
        .define("ignoreTagIngredients", false);

    public static final ModConfigSpec.IntValue MAX_SYNTHESIS_DEPTH = BUILDER
        .comment(
            "合成链的最大递归深度。",
            "自 1.0.3 起配方解析按'标签聚拢 + 单一代表合成',深度开销大减,故默认放宽到 16。",
            "正常配方链(如 原木→木板→木棍→梯子)通常 3~6 层;嵌套成品(如 prefab 的高级房子链)可达 10+ 层。",
            "若仍报'递归深度过深'可继续调大;若出现卡顿/超时可适当调小。")
        .defineInRange("maxSynthesisDepth", 16, 2, 32);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ModConfigs() {
    }
}
