/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.logging.LogUtils
 *  net.minecraft.core.HolderLookup$Provider
 *  net.minecraft.core.NonNullList
 *  net.minecraft.core.RegistryAccess
 *  net.minecraft.world.item.Item
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.Items
 *  net.minecraft.world.item.crafting.AbstractCookingRecipe
 *  net.minecraft.world.item.crafting.BlastingRecipe
 *  net.minecraft.world.item.crafting.CraftingRecipe
 *  net.minecraft.world.item.crafting.Ingredient
 *  net.minecraft.world.item.crafting.Recipe
 *  net.minecraft.world.item.crafting.RecipeManager
 *  net.minecraft.world.item.crafting.RecipeType
 *  net.minecraft.world.item.crafting.SmeltingRecipe
 *  net.minecraft.world.item.crafting.SmithingRecipe
 *  net.minecraft.world.item.crafting.SmithingTransformRecipe
 *  net.minecraft.world.item.crafting.SmokingRecipe
 *  net.minecraft.world.item.crafting.StonecutterRecipe
 *  net.minecraft.world.level.Level
 *  org.slf4j.Logger
 */
package com.easycraftchest.server.recipe;

import com.mojang.logging.LogUtils;
import com.easycraftchest.config.ModConfigs;
import com.easycraftchest.server.recipe.CraftingStep;
import com.easycraftchest.server.recipe.RecipeResolutionResult;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

public class RecipeResolver {
    private static final Logger LOGGER = LogUtils.getLogger();
    private volatile boolean timedOut = false;
    private long deadlineMs = Long.MAX_VALUE;
    /** 解析节点计数:超过上限立即中止,防止染色等密集配方图导致指数爆炸卡死服务端。 */
    private int resolutionNodes = 0;
    private final int maxResolutionNodes;
    private final int maxIngredientCandidates;
    private final int maxDepth;
    /** 单个原料组中,允许"需要真正合成"的成员递归尝试次数上限。
        库存能直接覆盖的成员不计入且不递归(瞬间返回),所以 #wool/#beds 这类十几色标签
        最多只深搜前几个最可能成功的成员,其余直接进缺料兜底,把"每层×成员数"的指数爆炸
        压成"每层×常数"。 */
    private static final int MAX_EXPENSIVE_GROUP_ATTEMPTS = 5;
    private final Level level;
    private final RecipeManager recipeManager;
    private final RegistryAccess registryAccess;
    private final Map<Item, List<Recipe<?>>> recipeCache = new HashMap();

    public RecipeResolver(Level level) {
        this.level = level;
        this.recipeManager = level.getRecipeManager();
        this.registryAccess = level.registryAccess();
        this.maxResolutionNodes = ModConfigs.MAX_RESOLUTION_NODES.get();
        this.maxIngredientCandidates = ModConfigs.MAX_INGREDIENT_CANDIDATES.get();
        this.maxDepth = ModConfigs.MAX_SYNTHESIS_DEPTH.get();
    }

    /** 设置解析截止时间(毫秒时间戳)。超过后热点循环会放弃并标记超时。 */
    public void setDeadline(long deadlineMs) {
        this.deadlineMs = deadlineMs;
    }

    public boolean isTimedOut() {
        return this.timedOut;
    }

    /** 重置节点预算:每个配方独立,一个密集配方爆炸不会污染其它配方。 */
    public void resetResolutionBudget() {
        this.resolutionNodes = 0;
        this.timedOut = false;
    }

    public int getResolutionNodes() {
        return this.resolutionNodes;
    }

    /** 节点预算:每个配方独立,爆炸时快速中止(不管截止时间)。 */
    private boolean checkTimeout() {
        if (++this.resolutionNodes > this.maxResolutionNodes) {
            this.timedOut = true;
            return true;
        }
        return false;
    }

    /** 截止时间:整个合成解析总时限,只在配方边界检查,避免慢配方连累后续配方。 */
    private boolean checkDeadline() {
        if (System.currentTimeMillis() > this.deadlineMs) {
            this.timedOut = true;
            return true;
        }
        return false;
    }

    public boolean hasCraftingRecipe(Item item) {
        if (item == null || item == Items.AIR) {
            return false;
        }
        return !this.getCraftingRecipesForItem(item).isEmpty();
    }

    public Map<Item, Integer> computeMissingBaseMaterialsForCraftingOnly(Item targetItem, int requiredCount, Map<Item, Integer> availableItems) {
        if (targetItem == null || targetItem == Items.AIR || requiredCount <= 0) {
            return Collections.emptyMap();
        }
        MissingInfo info = this.computeMissingInfoCraftingOnly(targetItem, requiredCount, availableItems, -1);
        return info.missing;
    }

    public List<Map<Item, Integer>> computeMissingAlternativesForCraftingOnly(Item targetItem, int requiredCount, Map<Item, Integer> availableItems, int maxPaths) {
        int depthLimit;
        if (targetItem == null || targetItem == Items.AIR || requiredCount <= 0) {
            return Collections.emptyList();
        }
        int limit = Math.max(1, maxPaths);
        this.resetResolutionBudget();
        MissingInfo full = this.computeMissingInfoCraftingOnly(targetItem, requiredCount, availableItems, -1);
        if (full.missing.isEmpty()) {
            return Collections.emptyList();
        }
        int baseDepth = Math.max(0, full.maxDepth);
        ArrayList<Map<Item, Integer>> alternatives = new ArrayList<Map<Item, Integer>>();
        for (int i = 0; i < limit && (depthLimit = baseDepth - i) >= 0; ++i) {
            // 每次不同深度上限的探测都是独立查询:清空上一次可能已耗尽的预算/超时标记,
            // 否则一次爆炸把 timedOut 置位后,后面所有探测与真实合成都会被直接判超时。
            this.resetResolutionBudget();
            MissingInfo limited = this.computeMissingInfoCraftingOnly(targetItem, requiredCount, availableItems, depthLimit);
            if (limited.missing.isEmpty()) continue;
            if (!RecipeResolver.containsMissing(alternatives, limited.missing)) {
                alternatives.add(limited.missing);
            }
            if (alternatives.size() >= limit) break;
        }
        if (alternatives.isEmpty()) {
            alternatives.add(full.missing);
        }
        return alternatives;
    }

    private static boolean containsMissing(List<Map<Item, Integer>> alternatives, Map<Item, Integer> candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return true;
        }
        for (Map<Item, Integer> existing : alternatives) {
            if (!candidate.equals(existing)) continue;
            return true;
        }
        return false;
    }

    private MissingInfo computeMissingInfoCraftingOnly(Item targetItem, int requiredCount, Map<Item, Integer> availableItems, int maxDepthLimit) {
        if (targetItem == null || targetItem == Items.AIR || requiredCount <= 0) {
            return new MissingInfo(Collections.emptyMap(), 0);
        }
        HashMap<Item, Integer> workingAvailable = new HashMap<Item, Integer>(availableItems == null ? Collections.emptyMap() : availableItems);
        MissingInfo missing = this.computeMissingRecursiveCraftingOnlyWithDepth(targetItem, requiredCount, workingAvailable, new HashSet<Item>(), 0, maxDepthLimit);
        if (missing == null) {
            HashMap<Item, Integer> conservative = new HashMap<Item, Integer>();
            conservative.put(targetItem, requiredCount);
            return new MissingInfo(conservative, 0);
        }
        if (missing.missing.isEmpty()) {
            return new MissingInfo(Collections.emptyMap(), missing.maxDepth);
        }
        HashMap<Item, Integer> cleaned = new HashMap<Item, Integer>();
        for (Map.Entry<Item, Integer> e : missing.missing.entrySet()) {
            int v;
            if (e.getKey() == null || (v = e.getValue() == null ? 0 : e.getValue()) <= 0) continue;
            cleaned.put(e.getKey(), v);
        }
        return new MissingInfo(cleaned, missing.maxDepth);
    }

    public RecipeResolutionResult resolveRecipeCraftingOnly(ItemStack targetItem, int requiredCount, Map<Item, Integer> availableItems) {
        return this.resolveRecipeRecursiveCraftingOnly(targetItem.getItem(), requiredCount, availableItems, new HashSet<Item>(), 0);
    }

    public RecipeResolutionResult resolveRecipe(ItemStack targetItem, int requiredCount, Map<Item, Integer> availableItems) {
        return this.resolveRecipeRecursive(targetItem.getItem(), requiredCount, availableItems, new HashSet<Item>(), 0);
    }

    private RecipeResolutionResult resolveRecipeRecursiveCraftingOnly(Item targetItem, int requiredCount, Map<Item, Integer> availableItems, Set<Item> visitedItems, int depth) {
        List<CraftingRecipe> recipes;
        if (depth > this.maxDepth) {
            return RecipeResolutionResult.failure("\u9012\u5f52\u6df1\u5ea6\u8fc7\u6df1");
        }
        if (depth == 0 && this.checkDeadline()) {
            return RecipeResolutionResult.failure("\u5408\u6210\u89e3\u6790\u8d85\u65f6");
        }
        if (this.checkTimeout()) {
            return RecipeResolutionResult.failure("\u5408\u6210\u89e3\u6790\u8d85\u65f6");
        }
        if (visitedItems.contains(targetItem)) {
            // \u56de\u5230\u7956\u5148 = \u53cd\u5411\u4f9d\u8d56(\u5982"\u4e3a\u89e3\u538b\u800c\u9020\u66f4\u5bc6\u7269\u54c1\u3001\u53c8\u56de\u5230\u81ea\u8eab")\u3002
            // \u82e5\u8be5\u7269\u54c1\u672c\u8eab\u6ca1\u6709\u6b63\u5411\u5236\u9020\u8def\u7ebf(\u77f3\u5934/\u91d1\u952d/\u91d1\u7c92\u8fd9\u7c7b\u53f6\u5b50),\u8bf4\u660e\u5b83\u53ea\u80fd\u9760\u5e93\u5b58\u6216\u66f4\u9ad8\u4e00\u7ea7\u89e3\u538b\u800c\u6765,
            // \u5e93\u5b58\u53c8\u4e0d\u591f \u2192 \u5982\u5b9e\u8bb0\u4e3a\u7f3a\u53e3(\u8fd9\u624d\u662f"\u7f3a\u77f3\u5934"\u800c\u975e"\u7f3a\u4e09\u500d\u538b\u7f29\u77f3\u5934"\u7684\u6765\u6e90);
            // \u82e5\u5b83\u6709\u6b63\u5411\u5236\u9020\u8def\u7ebf(\u5982\u6b63\u5728\u9020\u7684\u538b\u7f29\u77f3),\u8fd9\u6761\u53ea\u662f\u88ab\u6392\u9664\u7684\u53cd\u5411\u66ff\u4ee3,\u8fd4\u56de\u7a7a\u5373\u53ef\u3002
            if (!this.hasForwardCrafting(targetItem)) {
                HashMap<Item, Integer> leaf = new HashMap<Item, Integer>();
                leaf.put(targetItem, Math.max(1, requiredCount));
                return RecipeResolutionResult.failure("\u7f3a\u5c11\u57fa\u7840\u6750\u6599", leaf);
            }
            return RecipeResolutionResult.failure("\u5b58\u5728\u5faa\u73af\u4f9d\u8d56");
        }
        if (depth > 0) {
            int availableCount = availableItems.getOrDefault(targetItem, 0);
            if (availableCount >= requiredCount) {
                HashMap<Item, Integer> consumption = new HashMap<Item, Integer>();
                consumption.put(targetItem, requiredCount);
                return RecipeResolutionResult.success(Collections.emptyList(), consumption);
            }
            if (availableCount > 0) {
                int remainingNeeded = requiredCount - availableCount;
                boolean wasInVisited = visitedItems.remove(targetItem);
                HashMap<Item, Integer> availableAfterUsing = new HashMap<Item, Integer>(availableItems);
                availableAfterUsing.put(targetItem, 0);
                RecipeResolutionResult remainingResult = this.resolveRecipeRecursiveCraftingOnly(targetItem, remainingNeeded, availableAfterUsing, visitedItems, depth + 1);
                if (wasInVisited) {
                    visitedItems.add(targetItem);
                }
                if (remainingResult.isSuccess()) {
                    HashMap<Item, Integer> totalConsumption = new HashMap<Item, Integer>(remainingResult.getTotalConsumption());
                    totalConsumption.merge(targetItem, availableCount, Integer::sum);
                    return RecipeResolutionResult.success(remainingResult.getCraftingSteps(), totalConsumption);
                }
            }
        }
        if ((recipes = this.getCraftingRecipesForItem(targetItem)).isEmpty()) {
            HashMap<Item, Integer> missing = new HashMap<Item, Integer>();
            int available = availableItems.getOrDefault(targetItem, 0);
            int need = Math.max(0, requiredCount - available);
            if (need > 0) {
                missing.put(targetItem, need);
            }
            return RecipeResolutionResult.failure("\u6ca1\u6709\u53ef\u7528\u5de5\u4f5c\u53f0\u914d\u65b9", missing);
        }
        visitedItems.add(targetItem);
        ArrayList<RecipeResolutionResult> possiblePaths = new ArrayList<RecipeResolutionResult>();
        ArrayList<RecipeResolutionResult> failedPaths = new ArrayList<RecipeResolutionResult>();
        for (CraftingRecipe recipe : recipes) {
            if (depth == 0) {
                this.resetResolutionBudget();  // 顶层配方独立预算,材质解析不充值
            }
            HashSet<Item> recipeVisitedItems;
            RecipeResolutionResult pathResult = this.tryRecipePath((Recipe<?>)recipe, requiredCount, availableItems, (Set<Item>)(recipeVisitedItems = new HashSet<Item>(visitedItems)), depth + 1);
            if (pathResult.isSuccess()) {
                possiblePaths.add(pathResult);
                break;  // 找到可行路径即返回,不再尝试可能指数爆炸的更复杂配方(如床染色)
            }
            failedPaths.add(pathResult);
        }
        visitedItems.remove(targetItem);
        if (possiblePaths.isEmpty()) {
            return RecipeResolver.selectBestFailure(targetItem, requiredCount, failedPaths);
        }
        return this.selectBestPath(possiblePaths);
    }

    public List<RecipeResolutionResult> getAllPossiblePaths(ItemStack targetItem, int requiredCount, Map<Item, Integer> availableItems, int maxPaths) {
        ArrayList<RecipeResolutionResult> allPaths = new ArrayList<RecipeResolutionResult>();
        this.getAllPathsRecursive(targetItem.getItem(), requiredCount, availableItems, new HashSet<Item>(), 0, allPaths);
        List<RecipeResolutionResult> failedPaths = allPaths.stream().filter(path -> !path.isSuccess()).collect(Collectors.toList());
        failedPaths.sort((a, b) -> {
            int complexityCompare = Integer.compare(a.getComplexity(), b.getComplexity());
            if (complexityCompare != 0) {
                return complexityCompare;
            }
            return Integer.compare(a.getBaseMaterialCount(), b.getBaseMaterialCount());
        });
        return failedPaths.stream().limit(maxPaths).collect(Collectors.toList());
    }

    private void getAllPathsRecursive(Item targetItem, int requiredCount, Map<Item, Integer> availableItems, Set<Item> visitedItems, int depth, List<RecipeResolutionResult> allPaths) {
        if (depth > 10 || visitedItems.contains(targetItem)) {
            return;
        }
        List<Recipe<?>> recipes = this.getRecipesForItem(targetItem);
        if (recipes.isEmpty()) {
            HashMap<Item, Integer> baseMaterials = new HashMap<Item, Integer>();
            int available = availableItems.getOrDefault(targetItem, 0);
            int missing = Math.max(0, requiredCount - available);
            if (missing > 0) {
                baseMaterials.put(targetItem, missing);
            }
            allPaths.add(RecipeResolutionResult.failureWithConsumption("\u65e0\u6cd5\u5408\u6210\u7269\u54c1: " + String.valueOf(targetItem) + " (\u6ca1\u6709\u53ef\u7528\u914d\u65b9)", baseMaterials));
            return;
        }
        visitedItems.add(targetItem);
        for (Recipe<?> recipe : recipes) {
            RecipeResolutionResult pathResult = this.tryRecipePath(recipe, requiredCount, availableItems, new HashSet<Item>(visitedItems), depth + 1);
            allPaths.add(pathResult);
        }
        visitedItems.remove(targetItem);
    }

    private RecipeResolutionResult resolveRecipeRecursive(Item targetItem, int requiredCount, Map<Item, Integer> availableItems, Set<Item> visitedItems, int depth) {
        LOGGER.debug("\u5f00\u59cb\u89e3\u6790\u7269\u54c1: {} x{}, \u6df1\u5ea6: {}, visitedItems: {}", new Object[]{targetItem.getDescriptionId(), requiredCount, depth, visitedItems.stream().map(Item::getDescriptionId).toList()});
        if (depth > this.maxDepth) {
            LOGGER.debug("\u9012\u5f52\u6df1\u5ea6\u8fc7\u6df1 ({}), \u7ec8\u6b62\u89e3\u6790: {}", (Object)depth, (Object)targetItem.getDescriptionId());
            return RecipeResolutionResult.failure("\u9012\u5f52\u6df1\u5ea6\u8fc7\u6df1");
        }
        if (visitedItems.contains(targetItem)) {
            LOGGER.debug("\u68c0\u6d4b\u5230\u5faa\u73af\u4f9d\u8d56: {} \u5df2\u5728visitedItems\u4e2d: {}", (Object)targetItem.getDescriptionId(), visitedItems.stream().map(Item::getDescriptionId).toList());
            return RecipeResolutionResult.failure("\u5b58\u5728\u5faa\u73af\u4f9d\u8d56");
        }
        if (depth > 0) {
            int availableCount = availableItems.getOrDefault(targetItem, 0);
            if (availableCount >= requiredCount) {
                LOGGER.debug("\u5b58\u50a8\u5bb9\u5668\u4e2d\u5df2\u6709\u8db3\u591f\u7684 " + targetItem.getDescriptionId() + " (\u9700\u8981: " + requiredCount + ", \u53ef\u7528: " + availableCount + ")");
                HashMap<Item, Integer> consumption = new HashMap<Item, Integer>();
                consumption.put(targetItem, requiredCount);
                return RecipeResolutionResult.success(Collections.emptyList(), consumption);
            }
            if (availableCount > 0) {
                LOGGER.debug("\u5b58\u50a8\u5bb9\u5668\u4e2d\u6709\u90e8\u5206 " + targetItem.getDescriptionId() + " (\u9700\u8981: " + requiredCount + ", \u53ef\u7528: " + availableCount + "), \u5c1d\u8bd5\u5408\u6210\u5269\u4f59\u90e8\u5206");
                int remainingNeeded = requiredCount - availableCount;
                boolean wasInVisited = visitedItems.remove(targetItem);
                HashMap<Item, Integer> availableAfterUsing = new HashMap<Item, Integer>(availableItems);
                availableAfterUsing.put(targetItem, 0);
                RecipeResolutionResult remainingResult = this.resolveRecipeRecursive(targetItem, remainingNeeded, availableAfterUsing, visitedItems, depth + 1);
                if (wasInVisited) {
                    visitedItems.add(targetItem);
                }
                if (remainingResult.isSuccess()) {
                    HashMap<Item, Integer> hashMap = new HashMap<Item, Integer>(remainingResult.getTotalConsumption());
                    hashMap.merge(targetItem, availableCount, Integer::sum);
                    return RecipeResolutionResult.success(remainingResult.getCraftingSteps(), hashMap);
                }
            }
        }
        List<Recipe<?>> recipes = this.getRecipesForItem(targetItem);
        LOGGER.debug("\u4e3a\u7269\u54c1 " + targetItem.getDescriptionId() + " \u627e\u5230 " + recipes.size() + " \u4e2a\u914d\u65b9");
        for (int i = 0; i < recipes.size(); ++i) {
            Recipe<?> recipe = recipes.get(i);
            LOGGER.debug("\u914d\u65b9 {}: {} - \u7ed3\u679c: {}", new Object[]{i + 1, recipe.getClass().getSimpleName(), recipe.getResultItem((HolderLookup.Provider)this.registryAccess).getDescriptionId()});
            Map<Item, Integer> materials = this.getRequiredMaterials(recipe, 1);
            for (Map.Entry entry : materials.entrySet()) {
                LOGGER.debug("  - \u6750\u6599: {} x{}", (Object)((Item)entry.getKey()).getDescriptionId(), entry.getValue());
            }
        }
        if (recipes.isEmpty()) {
            LOGGER.debug("\u65e0\u6cd5\u83b7\u5f97\u7269\u54c1: " + targetItem.getDescriptionId() + " \u9700\u8981: " + requiredCount + " \u4e14\u6ca1\u6709\u53ef\u7528\u914d\u65b9");
            HashMap<Item, Integer> baseMaterials = new HashMap<Item, Integer>();
            int available = availableItems.getOrDefault(targetItem, 0);
            int missing = Math.max(0, requiredCount - available);
            if (missing > 0) {
                baseMaterials.put(targetItem, missing);
            }
            return RecipeResolutionResult.failureWithConsumption("\u65e0\u6cd5\u901a\u8fc7\u4efb\u4f55\u914d\u65b9\u5408\u6210 " + String.valueOf(targetItem) + " (\u9700\u8981: " + requiredCount + ")", baseMaterials);
        }
        visitedItems.add(targetItem);
        LOGGER.debug("\u5c06 {} \u6dfb\u52a0\u5230visitedItems: {}", (Object)targetItem.getDescriptionId(), visitedItems.stream().map(Item::getDescriptionId).toList());
        ArrayList<RecipeResolutionResult> possiblePaths = new ArrayList<RecipeResolutionResult>();
        ArrayList<RecipeResolutionResult> failedPaths = new ArrayList<RecipeResolutionResult>();
        for (Recipe<?> recipe : recipes) {
            LOGGER.debug("\u5c1d\u8bd5\u914d\u65b9\u8def\u5f84: {} \u5bf9\u4e8e\u7269\u54c1 {}", (Object)recipe.getClass().getSimpleName(), (Object)targetItem.getDescriptionId());
            HashSet<Item> hashSet = new HashSet<Item>(visitedItems);
            LOGGER.debug("\u4e3a\u914d\u65b9\u8def\u5f84\u521b\u5efavisitedItems\u526f\u672c: {}", hashSet.stream().map(Item::getDescriptionId).toList());
            RecipeResolutionResult pathResult = this.tryRecipePath(recipe, requiredCount, availableItems, hashSet, depth + 1);
            if (pathResult.isSuccess()) {
                LOGGER.debug("\u914d\u65b9\u8def\u5f84\u6210\u529f: {} \u5bf9\u4e8e\u7269\u54c1 {}", (Object)recipe.getClass().getSimpleName(), (Object)targetItem.getDescriptionId());
                possiblePaths.add(pathResult);
                continue;
            }
            LOGGER.debug("\u914d\u65b9\u8def\u5f84\u5931\u8d25: {} \u5bf9\u4e8e\u7269\u54c1 {} - {}", new Object[]{recipe.getClass().getSimpleName(), targetItem.getDescriptionId(), pathResult.getErrorMessage()});
            failedPaths.add(pathResult);
        }
        visitedItems.remove(targetItem);
        LOGGER.debug("\u4ecevisitedItems\u4e2d\u79fb\u9664 {}: {}", (Object)targetItem.getDescriptionId(), visitedItems.stream().map(Item::getDescriptionId).toList());
        if (possiblePaths.isEmpty()) {
            return RecipeResolver.selectBestFailure(targetItem, requiredCount, failedPaths);
        }
        return this.selectBestPath(possiblePaths);
    }

    private RecipeResolutionResult tryRecipePath(Recipe<?> recipe, int requiredCount, Map<Item, Integer> availableItems, Set<Item> visitedItems, int depth) {
        if (this.checkDeadline()) {
            return RecipeResolutionResult.failure("合成解析超时");
        }
        ItemStack result = recipe.getResultItem((HolderLookup.Provider)this.registryAccess);
        int recipeYield = result.getCount();
        int craftingTimes = (int)Math.ceil((double)requiredCount / (double)recipeYield);
        List<IngredientGroup> groups = this.buildIngredientGroups(recipe);
        if (groups.isEmpty()) {
            return RecipeResolutionResult.failure("\u65e0\u6cd5\u89e3\u6790\u914d\u65b9\u6750\u6599\uff08\u4e0d\u652f\u6301\u7684\u914d\u65b9\u7c7b\u578b/\u52a8\u6001\u914d\u65b9\uff09");
        }
        for (IngredientGroup group : groups) {
            if (this.orderedIngredientCandidates(group.ingredient, availableItems).isEmpty()) {
                return RecipeResolutionResult.failure("\u65e0\u6cd5\u89e3\u6790\u914d\u65b9\u6750\u6599\uff08\u4e0d\u652f\u6301\u7684\u914d\u65b9\u7c7b\u578b/\u52a8\u6001\u914d\u65b9\uff09");
            }
        }
        @SuppressWarnings("unchecked")
        Map<Item, Integer>[] chosen = new Map[groups.size()];
        ArrayList<RecipeResolutionResult> failures = new ArrayList<RecipeResolutionResult>();
        RecipeResolutionResult success = this.tryResolveIngredientGroups(recipe, recipeYield, craftingTimes, availableItems, visitedItems, depth, groups, 0, chosen, new ArrayList<CraftingStep>(), new HashMap<Item, Integer>(), failures);
        if (success != null) {
            return success;
        }
        if (failures.isEmpty()) {
            return this.reportRecipeMissing(recipe, requiredCount, recipeYield, availableItems);
        }
        return RecipeResolver.selectBestFailure(result.getItem(), requiredCount, failures);
    }

    /** \u515c\u5e95:\u5f53\u56de\u6eaf\u6ca1\u6709\u8bb0\u5f55\u5177\u4f53\u5931\u8d25\u65f6,\u6309\u6bcf\u4e2a\u539f\u6599\u7ec4\u7684"\u9700\u8981\u91cf - \u4ed3\u5e93\u91cf"\u76f4\u63a5\u4f30\u7b97\u7f3a\u5931,\u7ed9\u51fa\u660e\u786e\u63d0\u793a(\u5982\u7f3a\u5c11\u7ea2\u77f3)\u3002 */
    private RecipeResolutionResult reportRecipeMissing(Recipe<?> recipe, int requiredCount, int recipeYield, Map<Item, Integer> availableItems) {
        int craftingTimes = (int)Math.ceil((double)requiredCount / (double)recipeYield);
        HashMap<Item, Integer> missing = new HashMap<Item, Integer>();
        for (IngredientGroup group : this.buildIngredientGroups(recipe)) {
            int needed = group.slotCount * craftingTimes;
            List<Item> candidates = this.orderedIngredientCandidates(group.ingredient, availableItems);
            if (candidates.isEmpty()) {
                continue;
            }
            long have = 0L;
            for (Item candidate : candidates) {
                have += (long)availableItems.getOrDefault(candidate, 0);
            }
            long stillNeed = Math.max(0L, (long)needed - have);
            if (stillNeed > 0L) {
                missing.merge(candidates.get(0), (int)Math.min(stillNeed, (long)Integer.MAX_VALUE), Integer::sum);
            }
        }
        if (missing.isEmpty()) {
            return RecipeResolutionResult.failure("\u65e0\u6cd5\u5408\u6210\u8be5\u7269\u54c1\u6240\u9700\u6750\u6599");
        }
        return RecipeResolutionResult.failure("\u7f3a\u5c11\u6750\u6599", missing);
    }

    private RecipeResolutionResult tryResolveIngredientGroups(Recipe<?> recipe, int recipeYield, int craftingTimes, Map<Item, Integer> availableItems, Set<Item> visitedItems, int depth, List<IngredientGroup> groups, int groupIndex, Map<Item, Integer>[] chosen, List<CraftingStep> stepsAccum, Map<Item, Integer> consumptionAccum, List<RecipeResolutionResult> failures) {
        if (groupIndex >= groups.size()) {
            return this.buildFinalStep(recipe, recipeYield, craftingTimes, groups, chosen, stepsAccum, consumptionAccum);
        }
        IngredientGroup group = groups.get(groupIndex);
        // 无视 tag 开关(默认关):该组是 tag/多可选项(如床 #minecraft:beds)时,直接跳过整组,
        // 不造也不扣;沿当前配方继续下一组。整链都由此规则生效。
        if (RecipeResolver.isTagGroupIgnored(group.ingredient)) {
            chosen[groupIndex] = Collections.emptyMap();
            return this.tryResolveIngredientGroups(recipe, recipeYield, craftingTimes, availableItems, visitedItems, depth, groups, groupIndex + 1, chosen, stepsAccum, consumptionAccum, failures);
        }
        int needed = group.slotCount * craftingTimes;
        Map<Item, Integer> avail = availableItems == null ? Collections.emptyMap() : availableItems;
        List<Item> candidates = this.orderedIngredientCandidates(group.ingredient, avail);
        // 1) 标签/选择列表组是"可互换等价类":先把组内所有成员库存聚拢起来扣,
        //    扣掉的部分从可用池移除,避免同一份库存既被当直接消耗、又被当合成原料重复计算。
        HashMap<Item, Integer> stockUse = new HashMap<Item, Integer>();
        HashMap<Item, Integer> remainingAvail = new HashMap<Item, Integer>(avail);
        int remaining = needed;
        for (Item candidate : candidates) {
            if (remaining <= 0) break;
            int have = avail.getOrDefault(candidate, 0);
            if (have <= 0) continue;
            int take = Math.min(have, remaining);
            stockUse.merge(candidate, take, Integer::sum);
            remaining -= take;
            int left = have - take;
            if (left <= 0) {
                remainingAvail.remove(candidate);
            } else {
                remainingAvail.put(candidate, left);
            }
        }
        if (remaining <= 0) {
            // 整组由库存满足:零合成,直接计入基础消耗并继续下一组
            HashMap<Item, Integer> consumption = new HashMap<Item, Integer>(consumptionAccum);
            for (Map.Entry<Item, Integer> e : stockUse.entrySet()) {
                consumption.merge(e.getKey(), e.getValue(), Integer::sum);
            }
            HashMap<Item, Integer> chosenThis = new HashMap<Item, Integer>(stockUse);
            chosen[groupIndex] = chosenThis;
            return this.tryResolveIngredientGroups(recipe, recipeYield, craftingTimes, remainingAvail, visitedItems, depth, groups, groupIndex + 1, chosen, stepsAccum, consumption, failures);
        }
        // 2) 还差 remaining 个需真正合成:候选按"库存多→最省可造→不可造"排序逐个试(封顶),
        //    第一个能合成的代表成员即满足整组(颜色按你仓库里有的羊毛/半成品来选)。
        // 自身族换色组(如"染料+tag任一接口→某色接口"):若族里有"从零件直接造"的族根(灰本体),
        // 只允许族根作为合成来源,不枚举其它彩色变体互染 → 不爆炸、也直接落到零件缺料。
        Item outX = null;
        ItemStack outStack = recipe.getResultItem((HolderLookup.Provider)this.registryAccess);
        if (outStack != null && !outStack.isEmpty()) {
            outX = outStack.getItem();
        }
        java.util.List<Item> repCandidates = candidates;
        if (outX != null) {
            java.util.Set<Item> fam = new java.util.HashSet<Item>();
            for (ItemStack ms : group.ingredient.getItems()) {
                Item m = ms == null ? null : ms.getItem();
                if (m != null && m != Items.AIR) fam.add(m);
            }
            if (fam.contains(outX)) {
                java.util.ArrayList<Item> roots = new java.util.ArrayList<Item>();
                for (Item m : fam) {
                    if (this.isFamilyRoot(m, fam)) roots.add(m);
                }
                if (!roots.isEmpty()) {
                    repCandidates = roots;
                }
            }
        }
        int expensiveAttempts = 0;
        for (Item rep : repCandidates) {
            if (this.checkTimeout()) {
                return null;
            }
            if (visitedItems.contains(rep)) {
                continue;
            }
            boolean coversByStock = avail.getOrDefault(rep, 0) >= needed;
            if (!coversByStock && ++expensiveAttempts > RecipeResolver.MAX_EXPENSIVE_GROUP_ATTEMPTS) {
                break;
            }
            HashSet<Item> materialVisited = new HashSet<Item>(visitedItems);
            RecipeResolutionResult produced = this.resolveRecipeRecursiveCraftingOnly(rep, remaining, remainingAvail, materialVisited, depth + 1);
            if (produced == null || !produced.isSuccess()) {
                if (produced != null) {
                    failures.add(produced);
                }
                continue;
            }
            ArrayList<CraftingStep> steps = new ArrayList<CraftingStep>(stepsAccum);
            steps.addAll(produced.getCraftingSteps());
            HashMap<Item, Integer> consumption = new HashMap<Item, Integer>(consumptionAccum);
            for (Map.Entry<Item, Integer> e : produced.getTotalConsumption().entrySet()) {
                consumption.merge(e.getKey(), e.getValue(), Integer::sum);
            }
            for (Map.Entry<Item, Integer> e : stockUse.entrySet()) {
                consumption.merge(e.getKey(), e.getValue(), Integer::sum);
            }
            HashMap<Item, Integer> chosenThis = new HashMap<Item, Integer>(stockUse);
            chosenThis.merge(rep, remaining, Integer::sum);
            chosen[groupIndex] = chosenThis;
            RecipeResolutionResult deeper = this.tryResolveIngredientGroups(recipe, recipeYield, craftingTimes, remainingAvail, visitedItems, depth, groups, groupIndex + 1, chosen, steps, consumption, failures);
            if (deeper != null && deeper.isSuccess()) {
                return deeper;
            }
            if (deeper != null) {
                failures.add(deeper);
            }
        }
        return null;
    }

    private RecipeResolutionResult buildFinalStep(Recipe<?> recipe, int recipeYield, int craftingTimes, List<IngredientGroup> groups, Map<Item, Integer>[] chosen, List<CraftingStep> steps, Map<Item, Integer> consumption) {
        HashMap<Item, Integer> requiredMaterials = new HashMap<Item, Integer>();
        for (int i = 0; i < groups.size(); ++i) {
            Map<Item, Integer> m = chosen[i];
            if (m == null) continue;
            for (Map.Entry<Item, Integer> e : m.entrySet()) {
                if (e.getKey() == null || e.getKey() == Items.AIR || e.getValue() == null || e.getValue() <= 0) continue;
                requiredMaterials.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        ItemStack outputPrototype = recipe.getResultItem((HolderLookup.Provider)this.registryAccess).copy();
        outputPrototype.setCount(1);
        steps.add(new CraftingStep(outputPrototype, recipeYield * craftingTimes, requiredMaterials, Collections.singletonList(recipe)));
        return RecipeResolutionResult.success(steps, consumption);
    }

    private Map<Item, Integer> getRequiredMaterials(Recipe<?> recipe, int craftingTimes) {
        return this.getRequiredMaterials(recipe, craftingTimes, Collections.emptyMap());
    }

    private Item chooseIngredientItem(Ingredient ingredient, Map<Item, Integer> availableItems) {
        Item fallback;
        if (ingredient == null || ingredient.isEmpty()) {
            return Items.AIR;
        }
        ItemStack[] options = ingredient.getItems();
        if (options.length == 0) {
            return Items.AIR;
        }
        if (availableItems != null && !availableItems.isEmpty()) {
            for (ItemStack option : options) {
                Item item;
                if (option == null || option.isEmpty() || (item = option.getItem()) == null || item == Items.AIR || availableItems.getOrDefault(item, 0) <= 0) continue;
                return item;
            }
        }
        if ((fallback = options[0].getItem()) == null || fallback == Items.AIR) {
            for (ItemStack option : options) {
                Item item;
                if (option == null || option.isEmpty() || (item = option.getItem()) == null || item == Items.AIR) continue;
                return item;
            }
            return Items.AIR;
        }
        return fallback;
    }

    private List<Ingredient> getIngredientSlots(Recipe<?> recipe) {
        ArrayList<Ingredient> slots = new ArrayList<Ingredient>();
        if (recipe instanceof CraftingRecipe) {
            for (Ingredient ingredient : ((CraftingRecipe)recipe).getIngredients()) {
                if (ingredient == null || ingredient.isEmpty()) continue;
                slots.add(ingredient);
            }
            return slots;
        }
        if (recipe instanceof AbstractCookingRecipe) {
            Ingredient ingredient = ((AbstractCookingRecipe)recipe).getIngredients().get(0);
            if (ingredient != null && !ingredient.isEmpty()) {
                slots.add(ingredient);
            }
            return slots;
        }
        if (recipe instanceof StonecutterRecipe) {
            Ingredient ingredient = ((StonecutterRecipe)recipe).getIngredients().get(0);
            if (ingredient != null && !ingredient.isEmpty()) {
                slots.add(ingredient);
            }
            return slots;
        }
        if (recipe instanceof SmithingTransformRecipe) {
            try {
                Field templateField = SmithingTransformRecipe.class.getDeclaredField("template");
                Field baseField = SmithingTransformRecipe.class.getDeclaredField("base");
                Field additionField = SmithingTransformRecipe.class.getDeclaredField("addition");
                templateField.setAccessible(true);
                baseField.setAccessible(true);
                additionField.setAccessible(true);
                Ingredient templateIngredient = (Ingredient)templateField.get(recipe);
                Ingredient baseIngredient = (Ingredient)baseField.get(recipe);
                Ingredient additionIngredient = (Ingredient)additionField.get(recipe);
                if (templateIngredient != null && !templateIngredient.isEmpty()) {
                    slots.add(templateIngredient);
                }
                if (baseIngredient != null && !baseIngredient.isEmpty()) {
                    slots.add(baseIngredient);
                }
                if (additionIngredient != null && !additionIngredient.isEmpty()) {
                    slots.add(additionIngredient);
                }
            }
            catch (Exception e) {
                LOGGER.error("无法访问SmithingTransformRecipe的私有字段: {}", (Object)e.getMessage());
            }
            return slots;
        }
        if (recipe instanceof SmithingRecipe) {
            for (Ingredient ingredient : ((SmithingRecipe)recipe).getIngredients()) {
                if (ingredient == null || ingredient.isEmpty()) continue;
                slots.add(ingredient);
            }
            return slots;
        }
        return slots;
    }

    private List<IngredientGroup> buildIngredientGroups(Recipe<?> recipe) {
        ArrayList<IngredientGroup> groups = new ArrayList<IngredientGroup>();
        LinkedHashMap<String, IngredientGroup> bySignature = new LinkedHashMap<String, IngredientGroup>();
        for (Ingredient ingredient : this.getIngredientSlots(recipe)) {
            String signature = this.ingredientSignature(ingredient);
            IngredientGroup group = bySignature.get(signature);
            if (group == null) {
                group = new IngredientGroup(ingredient, 0);
                bySignature.put(signature, group);
                groups.add(group);
            }
            group.slotCount++;
        }
        return groups;
    }

    /** "无视 tag"开关(默认关)且该原料有多个可选项(真 tag 或 JSON 物品数组)→ 合成时整组跳过。 */
    private static boolean isTagGroupIgnored(Ingredient ing) {
        return ModConfigs.IGNORE_TAG_INGREDIENTS.get() && ing != null && !ing.isEmpty() && ing.getItems().length > 1;
    }

    private String ingredientSignature(Ingredient ingredient) {
        ArrayList<String> ids = new ArrayList<String>();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (item == null || item == Items.AIR) continue;
            ids.add(item.getDescriptionId());
        }
        Collections.sort(ids);
        return String.join(",", ids);
    }

    /** 某原料组的候选物品,按"越可能快速满足"排序:
        ① 仓库有货的成员(库存多者优先,尽量少去合成);
        ② 没有库存但能造出来的成员(取它"最省的配方"的一级原料缺口,越小越靠前);
        ③ 完全造不出来的成员垫底(它们只会报缺料,深搜也白费)。
        不改变"选哪个成员都满足该组"的语义,只是让最可能成功/最省的先被尝试,
        从而大幅减少因反复试错失败成员而消耗的递归节点。 */
    private List<Item> orderedIngredientCandidates(Ingredient ingredient, Map<Item, Integer> availableItems) {
        LinkedHashSet<Item> withStock = new LinkedHashSet<Item>();
        LinkedHashSet<Item> noStock = new LinkedHashSet<Item>();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (item == null || item == Items.AIR) continue;
            (availableItems != null && availableItems.getOrDefault(item, 0) > 0 ? withStock : noStock).add(item);
        }
        ArrayList<Item> ordered = new ArrayList<Item>();
        ArrayList<Item> stockList = new ArrayList<Item>(withStock);
        stockList.sort(Comparator.comparingInt((Item i) -> availableItems == null ? 0 : availableItems.getOrDefault(i, 0)).reversed());
        ordered.addAll(stockList);
        HashSet<Item> family = new HashSet<Item>(withStock);
        family.addAll(noStock);
        ArrayList<Item> craftable = new ArrayList<Item>();
        ArrayList<Item> uncraftable = new ArrayList<Item>();
        for (Item item : noStock) {
            if (this.getCraftingRecipesForItem(item).isEmpty()) {
                uncraftable.add(item);
            } else {
                craftable.add(item);
            }
        }
        // 族内"头羊"优先:有"完全不需要其它同族成员"的配方(白羊毛=4线)排最前,
        // 再按一级缺口。这样没羊毛时首选取白羊毛(→线),而不是彩色羊毛(→还要染料/墨囊)。
        craftable.sort((a, b) -> {
            // tag 内选 base:自身配方数量最多的成员优先(如 wool 里白羊毛 2 配方 > 彩色 1 配方)。
            int ca = this.memberRecipeCount(b) - this.memberRecipeCount(a);
            if (ca != 0) {
                return ca;
            }
            int ra = this.isFamilyRoot(a, family) ? 0 : 1;
            int rb = this.isFamilyRoot(b, family) ? 0 : 1;
            if (ra != rb) {
                return Integer.compare(ra, rb);
            }
            // 同族打平时,优先免染料的白色基础变体(白床=白羊毛=线):"任意床"倾向白床,不取第一个红床。
            int pa = RecipeResolver.isPreferredBase(a) ? 0 : 1;
            int pb = RecipeResolver.isPreferredBase(b) ? 0 : 1;
            if (pa != pb) {
                return Integer.compare(pa, pb);
            }
            return Integer.compare(this.oneLevelShortfall(a, 1, availableItems), this.oneLevelShortfall(b, 1, availableItems));
        });
        ordered.addAll(craftable);
        ordered.addAll(uncraftable);
        if (ordered.size() > this.maxIngredientCandidates) {
            return new ArrayList<Item>(ordered.subList(0, this.maxIngredientCandidates));
        }
        return ordered;
    }

    /** 族内"头羊"判定:item 是否存在一个配方,其原料完全不需要本族其它成员。
        (#minecraft:wool 里,白羊毛"4 线→白羊毛"不需要任何羊毛 → 头羊;彩色羊毛配方需白羊毛 → 不是。)
        头羊优先作为该族的默认制造选择,报缺料也会先落到它的基础物(线),而非彩色染料的来源。 */
    private boolean isFamilyRoot(Item item, Set<Item> family) {
        if (item == null || item == Items.AIR || family == null || family.isEmpty()) {
            return false;
        }
        List<CraftingRecipe> recipes = this.getCraftingRecipesForItem(item);
        if (recipes.isEmpty()) {
            return false;
        }
        for (CraftingRecipe recipe : recipes) {
            boolean needsFamily = false;
            for (IngredientGroup g : this.buildIngredientGroups(recipe)) {
                for (ItemStack ms : g.ingredient.getItems()) {
                    Item m = ms == null ? null : ms.getItem();
                    if (m == null || m == Items.AIR || m == item) continue;
                    if (family.contains(m)) {
                        needsFamily = true;
                        break;
                    }
                }
                if (needsFamily) {
                    break;
                }
            }
            if (!needsFamily) {
                return true;
            }
        }
        return false;
    }

    /** 完全不深搜地估算"用现成库存造 need 个该物品"的省事程度(取该物品最省的配方)。
        排序键 = 还缺几种原料组 × 1_000_000 + 总缺口。
        先比"缺几种组"是关键:例如白床的羊毛格仓库有货 → 该组不缺,键立刻比其他床小。
        若只比总缺口,染色配方(染剂+另一张床)会让 16 色床缺口全部相等,
        稳定排序就永远先试红床(没红羊毛必失败),造成"只有红毛能合"的假象。
        只用于候选排序启发,不递归;无工作台配方返回 Integer.MAX_VALUE。 */
    private int oneLevelShortfall(Item item, int need, Map<Item, Integer> availableItems) {
        List<CraftingRecipe> recipes = this.getCraftingRecipesForItem(item);
        if (recipes.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int best = Integer.MAX_VALUE;
        Map<Item, Integer> avail = availableItems == null ? Collections.emptyMap() : availableItems;
        for (CraftingRecipe recipe : recipes) {
            ItemStack result = recipe.getResultItem((HolderLookup.Provider)this.registryAccess);
            if (result == null || result.isEmpty()) continue;
            int yield = Math.max(1, result.getCount());
            int times = (int)Math.ceil((double)need / (double)yield);
            int shortGroups = 0;
            long shortfall = 0L;
            for (IngredientGroup g : this.buildIngredientGroups(recipe)) {
                int gNeed = g.slotCount * times;
                long have = 0L;
                for (ItemStack s : g.ingredient.getItems()) {
                    Item m = s == null ? null : s.getItem();
                    if (m != null && m != Items.AIR) {
                        have += (long)avail.getOrDefault(m, 0);
                    }
                }
                if (have < (long)gNeed) {
                    ++shortGroups;
                    shortfall += (long)gNeed - have;
                }
            }
            int key = (int)Math.min((long)Integer.MAX_VALUE, (long)shortGroups * 1000000L + shortfall);
            if (key < best) {
                best = key;
            }
        }
        return best;
    }

    /** 某物品自身的工作台配方数量(用于 tag 内选 base:配方最多者通常就是 base,如 wool 里白羊毛 2 配方 > 彩色 1 配方)。 */
    private int memberRecipeCount(Item item) {
        if (item == null) {
            return 0;
        }
        return this.getCraftingRecipesForItem(item).size();
    }

    /** 免染料的"白色基础变体"(白床=白羊毛=4线,不需要染料)。同族打平时优先选它。 */
    private static boolean isPreferredBase(Item item) {
        if (item == null) {
            return false;
        }
        String id = item.getDescriptionId();
        return id.endsWith(".white_bed") || id.endsWith(".white_wool") || id.endsWith(".white_carpet");
    }

    private Map<Item, Integer> getRequiredMaterials(Recipe<?> recipe, int craftingTimes, Map<Item, Integer> availableItems) {
        HashMap<Item, Integer> materials;
        block10: {
            block13: {
                Item item;
                block12: {
                    Item item2;
                    block11: {
                        materials = new HashMap<Item, Integer>();
                        if (!(recipe instanceof CraftingRecipe)) break block11;
                        CraftingRecipe craftingRecipe = (CraftingRecipe)recipe;
                        for (Ingredient ingredient : craftingRecipe.getIngredients()) {
                            Item item3;
                            if (ingredient.isEmpty() || (item3 = this.chooseIngredientItem(ingredient, availableItems)) == null || item3 == Items.AIR) continue;
                            materials.merge(item3, craftingTimes, Integer::sum);
                        }
                        break block10;
                    }
                    if (!(recipe instanceof AbstractCookingRecipe)) break block12;
                    AbstractCookingRecipe cookingRecipe = (AbstractCookingRecipe)recipe;
                    Ingredient ingredient = (Ingredient)cookingRecipe.getIngredients().get(0);
                    if (ingredient.isEmpty() || (item2 = this.chooseIngredientItem(ingredient, availableItems)) == null || item2 == Items.AIR) break block10;
                    materials.put(item2, craftingTimes);
                    break block10;
                }
                if (!(recipe instanceof StonecutterRecipe)) break block13;
                StonecutterRecipe stonecutterRecipe = (StonecutterRecipe)recipe;
                Ingredient ingredient = (Ingredient)stonecutterRecipe.getIngredients().get(0);
                if (ingredient.isEmpty() || (item = this.chooseIngredientItem(ingredient, availableItems)) == null || item == Items.AIR) break block10;
                materials.put(item, craftingTimes);
                break block10;
            }
            if (recipe instanceof SmithingTransformRecipe) {
                SmithingTransformRecipe smithingTransformRecipe = (SmithingTransformRecipe)recipe;
                LOGGER.debug("\u5904\u7406SmithingTransformRecipe: {}", (Object)smithingTransformRecipe.getClass().getSimpleName());
                try {
                    Item item;
                    Field templateField = SmithingTransformRecipe.class.getDeclaredField("template");
                    Field baseField = SmithingTransformRecipe.class.getDeclaredField("base");
                    Field additionField = SmithingTransformRecipe.class.getDeclaredField("addition");
                    templateField.setAccessible(true);
                    baseField.setAccessible(true);
                    additionField.setAccessible(true);
                    Ingredient templateIngredient = (Ingredient)templateField.get(smithingTransformRecipe);
                    Ingredient baseIngredient = (Ingredient)baseField.get(smithingTransformRecipe);
                    Ingredient additionIngredient = (Ingredient)additionField.get(smithingTransformRecipe);
                    LOGGER.debug("SmithingTransformRecipe\u6210\u5206\u83b7\u53d6\u6210\u529f");
                    if (!templateIngredient.isEmpty() && (item = this.chooseIngredientItem(templateIngredient, availableItems)) != null && item != Items.AIR) {
                        materials.merge(item, craftingTimes, Integer::sum);
                    }
                    if (!baseIngredient.isEmpty() && (item = this.chooseIngredientItem(baseIngredient, availableItems)) != null && item != Items.AIR) {
                        materials.merge(item, craftingTimes, Integer::sum);
                    }
                    if (!additionIngredient.isEmpty() && (item = this.chooseIngredientItem(additionIngredient, availableItems)) != null && item != Items.AIR) {
                        materials.merge(item, craftingTimes, Integer::sum);
                    }
                }
                catch (Exception e) {
                    LOGGER.error("\u65e0\u6cd5\u8bbf\u95eeSmithingTransformRecipe\u7684\u79c1\u6709\u5b57\u6bb5: {}", (Object)e.getMessage());
                }
            } else if (recipe instanceof SmithingRecipe) {
                SmithingRecipe smithingRecipe = (SmithingRecipe)recipe;
                NonNullList<Ingredient> ingredients = smithingRecipe.getIngredients();
                for (Ingredient ingredient : ingredients) {
                    Item item;
                    if (ingredient.isEmpty() || (item = this.chooseIngredientItem(ingredient, availableItems)) == null || item == Items.AIR) continue;
                    materials.merge(item, craftingTimes, Integer::sum);
                }
            }
        }
        return materials;
    }

    private List<Recipe<?>> getRecipesForItem(Item item) {
        return this.recipeCache.computeIfAbsent(item, this::findRecipesForItem);
    }

    /** 某物品可用来"制造"它的工作台配方(含正/反向;反向=解压,用于从库存里已有更密物品取更细物品,
        如 金块→金锭→金粒。循环依赖由解析器的 visited 检测兜住,见 resolveRecipeRecursiveCraftingOnly)。 */
    private List<CraftingRecipe> getCraftingRecipesForItem(Item item) {
        if (item == null || item == Items.AIR) {
            return Collections.emptyList();
        }
        return this.getFabricationByResult().getOrDefault(item, Collections.emptyList());
    }

    /** 某物品可用来"从零制造"它的配方表(已过滤掉"换色/回染自身族"的配方):
        若某物品已有"直接配方"(原料不含它自身,如灰接口的零件配方),则丢弃"消耗它自身/同族"的染色配方
        (如"淡蓝染料 + 任一接口 → 灰接口"),因为那只是给已有物品换色、不该被当作凭空制造它的途径,
        否则会陷入"要灰接口→先要另一个接口→又只能互染"的循环/8008 爆炸。
        若某物品只有回染配方(如只染出来的彩色变体),则保留它作为唯一途径。 */
    private Map<Item, List<CraftingRecipe>> fabricationByResult = null;

    private Map<Item, List<CraftingRecipe>> getFabricationByResult() {
        if (this.fabricationByResult == null) {
            Map<Item, List<CraftingRecipe>> shared = RecipeResolver.SHARED_FABRICATION_BY_RESULT.get(this.recipeManager);
            if (shared != null) {
                this.fabricationByResult = shared;
                return shared;
            }
            HashMap<Item, List<CraftingRecipe>> fab = new HashMap<Item, List<CraftingRecipe>>();
            for (Map.Entry<Item, List<CraftingRecipe>> e : this.getCraftingByResult().entrySet()) {
                Item out = e.getKey();
                ArrayList<CraftingRecipe> direct = new ArrayList<CraftingRecipe>();
                ArrayList<CraftingRecipe> selfLike = new ArrayList<CraftingRecipe>();
                for (CraftingRecipe r : e.getValue()) {
                    if (this.recipeConsumesItem(r, out)) {
                        selfLike.add(r);
                    } else {
                        direct.add(r);
                    }
                }
                if (direct.isEmpty()) {
                    // 只有"自身族换色"配方 → 保留(这是它唯一的合成途径,如白色/彩色磁盘接口)
                    fab.put(out, e.getValue());
                } else {
                    // 有直接配方 → 只造直接配方,丢弃换色/回染,避免把它当凭空制造途径
                    fab.put(out, direct);
                }
            }
            this.fabricationByResult = fab;
            RecipeResolver.SHARED_FABRICATION_BY_RESULT.put(this.recipeManager, fab);
        }
        return this.fabricationByResult;
    }

    /** 该配方是否"消耗产物自身/同族":存在某个原料组的成员等于 out(如染灰配方原料 tag 里含灰本体)。 */
    private boolean recipeConsumesItem(CraftingRecipe recipe, Item out) {
        if (out == null || out == Items.AIR) {
            return false;
        }
        try {
            for (IngredientGroup g : this.buildIngredientGroups(recipe)) {
                for (ItemStack ms : g.ingredient.getItems()) {
                    Item m = ms == null ? null : ms.getItem();
                    if (m == out) {
                        return true;
                    }
                }
            }
        }
        catch (Exception e) {
            // 特殊配方解析失败时不误伤,当作不消耗
        }
        return false;
    }

    /** 按产物物品索引的工作台配方表:一次构建,查询 O(1),避免每次递归都全量扫配方导致节点代价过高。 */
    private Map<Item, List<CraftingRecipe>> craftingByResult = null;

    // ---- 服务端生命周期级缓存:按 RecipeManager 共享,避免每次合成请求都全量重扫配方 ----
    private static final java.util.WeakHashMap<RecipeManager, Map<Item, List<CraftingRecipe>>> SHARED_CRAFTING_BY_RESULT = new java.util.WeakHashMap<RecipeManager, Map<Item, List<CraftingRecipe>>>();
    private static final java.util.WeakHashMap<RecipeManager, Map<Item, List<CraftingRecipe>>> SHARED_FORWARD_BY_RESULT = new java.util.WeakHashMap<RecipeManager, Map<Item, List<CraftingRecipe>>>();
    private static final java.util.WeakHashMap<RecipeManager, Map<Item, List<CraftingRecipe>>> SHARED_FABRICATION_BY_RESULT = new java.util.WeakHashMap<RecipeManager, Map<Item, List<CraftingRecipe>>>();
    private static final java.util.WeakHashMap<RecipeManager, Map<Item, Map<Item, Integer>>> SHARED_DECOMPRESS_BY_DENSE = new java.util.WeakHashMap<RecipeManager, Map<Item, Map<Item, Integer>>>();

    private Map<Item, List<CraftingRecipe>> getCraftingByResult() {
        if (this.craftingByResult == null) {
            Map<Item, List<CraftingRecipe>> shared = RecipeResolver.SHARED_CRAFTING_BY_RESULT.get(this.recipeManager);
            if (shared != null) {
                this.craftingByResult = shared;
                return shared;
            }
            HashMap<Item, List<CraftingRecipe>> index = new HashMap<Item, List<CraftingRecipe>>();
            for (RecipeHolder<CraftingRecipe> holder : this.recipeManager.getAllRecipesFor(RecipeType.CRAFTING)) {
                CraftingRecipe recipe = holder.value();
                if (recipe == null) continue;
                Item result = recipe.getResultItem((HolderLookup.Provider)this.registryAccess).getItem();
                if (result == null || result == Items.AIR) continue;
                index.computeIfAbsent(result, k -> new ArrayList<CraftingRecipe>()).add(recipe);
            }
            // 占用原料格多的配方优先(如床的羊毛配方 6 格 > 染色配方 2 格),
            // 让解析先走"真合成"大配方,避免染料/染色这类小配方先被探寻。
            for (List<CraftingRecipe> list : index.values()) {
                list.sort((a, b) -> Integer.compare(RecipeResolver.craftingUsedSlots(b), RecipeResolver.craftingUsedSlots(a)));
            }
            this.craftingByResult = index;
            RecipeResolver.SHARED_CRAFTING_BY_RESULT.put(this.recipeManager, index);
        }
        return this.craftingByResult;
    }

    /** 只含"正向(制造)"配方的结果索引:解压/反向配方(把高阶物品拆回低阶,如压缩石→9 石头)不算制造配方。
        这样"造压缩石头"只会走压缩方向,不会因解压配方产生"造石头→要压缩石→又要石头"的循环,
        也才能把真正缺的基础物(石头)正确上报。 */
    private Map<Item, List<CraftingRecipe>> forwardCraftingByResult = null;

    private final Set<CraftingRecipe> reverseRecipeCache = new HashSet<CraftingRecipe>();

    private Map<Item, List<CraftingRecipe>> getForwardCraftingByResult() {
        if (this.forwardCraftingByResult == null) {
            Map<Item, List<CraftingRecipe>> shared = RecipeResolver.SHARED_FORWARD_BY_RESULT.get(this.recipeManager);
            if (shared != null) {
                this.forwardCraftingByResult = shared;
                return shared;
            }
            HashMap<Item, List<CraftingRecipe>> fwd = new HashMap<Item, List<CraftingRecipe>>();
            for (Map.Entry<Item, List<CraftingRecipe>> e : this.getCraftingByResult().entrySet()) {
                for (CraftingRecipe recipe : e.getValue()) {
                    if (this.isReverseCompressRecipe(recipe)) continue;
                    fwd.computeIfAbsent(e.getKey(), k -> new ArrayList<CraftingRecipe>()).add(recipe);
                }
            }
            this.forwardCraftingByResult = fwd;
            RecipeResolver.SHARED_FORWARD_BY_RESULT.put(this.recipeManager, fwd);
        }
        return this.forwardCraftingByResult;
    }

    /** 工作台配方占用非空格子的数量。 */
    private static int craftingUsedSlots(CraftingRecipe recipe) {
        if (recipe == null) {
            return 0;
        }
        int n = 0;
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing != null && !ing.isEmpty()) ++n;
        }
        return n;
    }

    /** 该物品是否存在"正向(压缩/合成)"制造路线(不含解压)。正向路线为空的物品(=叶子)通常
        靠解压更高一级获得(如石头、金锭、金粒):库存里没有它且又不足以合成时,它就是真正的缺口。 */
    private boolean hasForwardCrafting(Item item) {
        if (item == null || item == Items.AIR) {
            return false;
        }
        return !this.getForwardCraftingByResult().getOrDefault(item, Collections.emptyList()).isEmpty();
    }

    /** 该配方是否是"反向/解压":即存在原料成员 m 比产物 out 更高一级 —— m 有一条把 ≥2 个 out 压成 1 个 m 的配方。
        (压缩石 out=石头 时,m=压缩石,压缩石有"9 石头→1 压缩石" ⇒ 判定为反向。) */
    private boolean isReverseCompressRecipe(CraftingRecipe recipe) {
        if (this.reverseRecipeCache.contains(recipe)) {
            return true;
        }
        ItemStack outStack = recipe.getResultItem((HolderLookup.Provider)this.registryAccess);
        if (outStack == null || outStack.isEmpty()) {
            return false;
        }
        Item out = outStack.getItem();
        for (IngredientGroup g : this.buildIngredientGroups(recipe)) {
            for (ItemStack ms : g.ingredient.getItems()) {
                Item m = ms == null ? null : ms.getItem();
                if (m == null || m == Items.AIR || m == out) continue;
                List<CraftingRecipe> mRecipes = this.getCraftingByResult().getOrDefault(m, Collections.emptyList());
                for (CraftingRecipe mr : mRecipes) {
                    if (this.pilesUp(mr, out)) {
                        this.reverseRecipeCache.add(recipe);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** mr 是否"把 ≥2 个 target 堆成一个 mr 的产物"(即压缩方向)。 */
    private boolean pilesUp(CraftingRecipe mr, Item target) {
        for (IngredientGroup g : this.buildIngredientGroups(mr)) {
            if (g.slotCount < 2) continue;
            for (ItemStack ms : g.ingredient.getItems()) {
                Item m = ms == null ? null : ms.getItem();
                if (m == target) {
                    return true;
                }
            }
        }
        return false;
    }

    private MissingInfo computeMissingRecursiveCraftingOnlyWithDepth(Item targetItem, int requiredCount, Map<Item, Integer> availableItems, Set<Item> visitedItems, int depth, int maxDepthLimit) {
        if (requiredCount <= 0) {
            return new MissingInfo(Collections.emptyMap(), depth);
        }
        if (depth > this.maxDepth) {
            return null;
        }
        if (depth == 0 && this.checkDeadline()) {
            return null;
        }
        if (this.checkTimeout()) {
            return null;
        }
        if (targetItem == null || targetItem == Items.AIR) {
            return null;
        }
        int available = availableItems.getOrDefault(targetItem, 0);
        if (available > 0) {
            int use = Math.min(available, requiredCount);
            int left = available - use;
            if (left <= 0) {
                availableItems.remove(targetItem);
            } else {
                availableItems.put(targetItem, left);
            }
            if ((requiredCount -= use) <= 0) {
                return new MissingInfo(Collections.emptyMap(), depth);
            }
        }
        if (maxDepthLimit >= 0 && depth >= maxDepthLimit) {
            HashMap<Item, Integer> missing = new HashMap<Item, Integer>();
            missing.put(targetItem, requiredCount);
            return new MissingInfo(missing, depth);
        }
        if (visitedItems.contains(targetItem)) {
            // 与合成路径一致的叶子判定:无正向制造路线的基础物在循环处如实记为缺口,
            // 否则(可正向制造)只是反向替代,返回 null 交给上层其它路径。
            if (!this.hasForwardCrafting(targetItem)) {
                HashMap<Item, Integer> leaf = new HashMap<Item, Integer>();
                leaf.put(targetItem, requiredCount);
                return new MissingInfo(leaf, depth);
            }
            return null;
        }
        List<CraftingRecipe> recipes = this.getCraftingRecipesForItem(targetItem);
        if (recipes.isEmpty()) {
            HashMap<Item, Integer> missing = new HashMap<Item, Integer>();
            missing.put(targetItem, requiredCount);
            return new MissingInfo(missing, depth);
        }
        visitedItems.add(targetItem);
        MissingInfo bestInfo = null;
        Map<Item, Integer> bestAvailable = null;
        for (CraftingRecipe recipe : recipes) {
            if (depth == 0) {
                this.resetResolutionBudget();
            }
            HashMap<Item, Integer> availableClone = new HashMap<Item, Integer>(availableItems);
            HashSet<Item> visitedClone = new HashSet<Item>(visitedItems);
            ItemStack result = recipe.getResultItem((HolderLookup.Provider)this.registryAccess);
            int yield = Math.max(1, result.getCount());
            int craftingTimes = (int)Math.ceil((double)requiredCount / (double)yield);
            List<IngredientGroup> groups = this.buildIngredientGroups((Recipe<?>)recipe);
            if (groups.isEmpty()) continue;
            MissingCandidate candidate = this.computeBestMissingForGroups(groups, 0, craftingTimes, availableClone, visitedClone, depth, maxDepthLimit, new MissingInfo(Collections.emptyMap(), depth));
            if (candidate == null) continue;
            if (bestInfo != null && !RecipeResolver.isMissingBetter(candidate.info.missing, bestInfo.missing)) continue;
            bestInfo = candidate.info;
            bestAvailable = candidate.available;
            if (candidate.info.missing.isEmpty()) {
                break;  // 已找到零缺失配方,不再尝试可能指数爆炸的后续配方(如床染色)
            }
        }
        visitedItems.remove(targetItem);
        if (bestInfo == null) {
            if (this.isTimedOut()) {
                LOGGER.debug("缺料深挖被预算截断: {} x{} (已耗节点 {})", targetItem.getDescriptionId(), requiredCount, this.resolutionNodes);
            } else {
                LOGGER.debug("缺料深挖找不到可行配方: {} x{}", targetItem.getDescriptionId(), requiredCount);
            }
            return null;
        }
        if (bestAvailable != null) {
            availableItems.clear();
            availableItems.putAll(bestAvailable);
        }
        return bestInfo;
    }

    private MissingCandidate computeBestMissingForGroups(List<IngredientGroup> groups, int groupIndex, int craftingTimes, Map<Item, Integer> availableItems, Set<Item> visitedItems, int depth, int maxDepthLimit, MissingInfo accumulated) {
        if (this.checkTimeout()) {
            return null;
        }
        if (groupIndex >= groups.size()) {
            return new MissingCandidate(accumulated, new HashMap<Item, Integer>(availableItems));
        }
        IngredientGroup group = groups.get(groupIndex);
        int needed = group.slotCount * craftingTimes;
        Map<Item, Integer> avail = availableItems == null ? Collections.emptyMap() : availableItems;
        List<Item> candidates = this.orderedIngredientCandidates(group.ingredient, avail);
        // 缺料同合成路径:标签/选择列表是"可互换等价类",先把组内所有成员库存聚拢,
        // 总量够 → 本组不缺;不够的部分只需挑一个"可行色"去深挖真正缺什么,而非枚举每种颜色。
        HashMap<Item, Integer> stockUse = new HashMap<Item, Integer>();
        HashMap<Item, Integer> remainingAvail = new HashMap<Item, Integer>(avail);
        int remaining = needed;
        for (Item candidate : candidates) {
            if (remaining <= 0) break;
            int have = avail.getOrDefault(candidate, 0);
            if (have <= 0) continue;
            int take = Math.min(have, remaining);
            stockUse.merge(candidate, take, Integer::sum);
            remaining -= take;
            int left = have - take;
            if (left <= 0) {
                remainingAvail.remove(candidate);
            } else {
                remainingAvail.put(candidate, left);
            }
        }
        if (remaining <= 0) {
            // 本组库存足够 → 无缺失;把扣掉本组消耗后的可用池继续带下去
            return this.computeBestMissingForGroups(groups, groupIndex + 1, craftingTimes, remainingAvail, visitedItems, depth, maxDepthLimit, accumulated);
        }
        int shortfall = remaining;
        if (candidates.size() > 1) {
            StringBuilder order = new StringBuilder();
            int show = Math.min(8, candidates.size());
            for (int ci = 0; ci < show; ++ci) {
                if (ci > 0) order.append(", ");
                order.append(candidates.get(ci).getDescriptionId());
            }
            if (candidates.size() > show) {
                order.append(", …共").append(candidates.size()).append("种");
            }
            LOGGER.debug("[缺料选代表] 组缺口 {} 个, 候选顺序: {}", shortfall, order);
        }
        MissingCandidate best = null;
        int expensiveAttempts = 0;
        for (Item rep : candidates) {
            if (this.checkTimeout()) {
                return null;
            }
            if (visitedItems.contains(rep)) {
                continue;
            }
            boolean coversByStock = avail.getOrDefault(rep, 0) >= needed;
            if (!coversByStock && ++expensiveAttempts > RecipeResolver.MAX_EXPENSIVE_GROUP_ATTEMPTS) {
                LOGGER.debug("[缺料选代表] 达到单组尝试上限 {},停止枚举该组其余成员", RecipeResolver.MAX_EXPENSIVE_GROUP_ATTEMPTS);
                break;
            }
            HashMap<Item, Integer> snapshot = new HashMap<Item, Integer>(remainingAvail);
            HashSet<Item> visitedClone = new HashSet<Item>(visitedItems);
            MissingInfo part = this.computeMissingRecursiveCraftingOnlyWithDepth(rep, shortfall, remainingAvail, visitedClone, depth + 1, maxDepthLimit);
            if (part == null) {
                remainingAvail.clear();
                remainingAvail.putAll(snapshot);
                continue;
            }
            HashMap<Item, Integer> mergedMissing = new HashMap<Item, Integer>(accumulated.missing);
            for (Map.Entry<Item, Integer> e : part.missing.entrySet()) {
                mergedMissing.merge(e.getKey(), e.getValue(), Integer::sum);
            }
            MissingInfo newAccum = new MissingInfo(mergedMissing, Math.max(accumulated.maxDepth, part.maxDepth));
            MissingCandidate deeper = this.computeBestMissingForGroups(groups, groupIndex + 1, craftingTimes, remainingAvail, visitedClone, depth, maxDepthLimit, newAccum);
            remainingAvail.clear();
            remainingAvail.putAll(snapshot);
            if (deeper != null && (best == null || RecipeResolver.isMissingBetter(deeper.info.missing, best.info.missing))) {
                best = deeper;
                if (best.info.missing.isEmpty()) {
                    return best;
                }
            }
        }
        if (best == null) {
            // 深挖失败(超预算/死循环):用"组缺口"估算并继续后续组;缺口名挂"库存最多"的成员,
            // 使提示贴近真实(如你有白床就报缺白床数量),而不是永远报第一个颜色。
            HashMap<Item, Integer> directMissing = new HashMap<Item, Integer>(accumulated.missing);
            if (!candidates.isEmpty()) {
                Item repName = candidates.get(0);
                long maxStock = -1L;
                for (Item c : candidates) {
                    long st = avail.getOrDefault(c, 0);
                    if (st > maxStock) {
                        maxStock = st;
                        repName = c;
                    }
                }
                if (repName != null && shortfall > 0) {
                    directMissing.merge(repName, shortfall, Integer::sum);
                }
            }
            MissingInfo directAccum = new MissingInfo(directMissing, Math.max(accumulated.maxDepth, depth));
            return this.computeBestMissingForGroups(groups, groupIndex + 1, craftingTimes, remainingAvail, visitedItems, depth, maxDepthLimit, directAccum);
        }
        return best;
    }

    private static boolean isMissingBetter(Map<Item, Integer> a, Map<Item, Integer> b) {
        int aSum = 0;
        int bSum = 0;
        for (Integer v : a.values()) {
            if (v == null || v <= 0) continue;
            aSum = (int)Math.min((long)aSum + (long)v.intValue(), Integer.MAX_VALUE);
        }
        for (Integer v : b.values()) {
            if (v == null || v <= 0) continue;
            bSum = (int)Math.min((long)bSum + (long)v.intValue(), Integer.MAX_VALUE);
        }
        if (aSum != bSum) {
            return aSum < bSum;
        }
        if (a.size() != b.size()) {
            return a.size() < b.size();
        }
        return false;
    }

    private List<Recipe<?>> findRecipesForItem(Item item) {
        ArrayList recipes = new ArrayList();
        this.recipeManager.getAllRecipesFor(RecipeType.CRAFTING).stream().filter(recipe -> ((CraftingRecipe)recipe.value()).getResultItem((HolderLookup.Provider)this.registryAccess).getItem() == item).forEach(recipe -> recipes.add(recipe.value()));
        this.recipeManager.getAllRecipesFor(RecipeType.SMELTING).stream().filter(recipe -> ((SmeltingRecipe)recipe.value()).getResultItem((HolderLookup.Provider)this.registryAccess).getItem() == item).forEach(recipe -> recipes.add(recipe.value()));
        this.recipeManager.getAllRecipesFor(RecipeType.BLASTING).stream().filter(recipe -> ((BlastingRecipe)recipe.value()).getResultItem((HolderLookup.Provider)this.registryAccess).getItem() == item).forEach(recipe -> recipes.add(recipe.value()));
        this.recipeManager.getAllRecipesFor(RecipeType.SMOKING).stream().filter(recipe -> ((SmokingRecipe)recipe.value()).getResultItem((HolderLookup.Provider)this.registryAccess).getItem() == item).forEach(recipe -> recipes.add(recipe.value()));
        this.recipeManager.getAllRecipesFor(RecipeType.STONECUTTING).stream().filter(recipe -> ((StonecutterRecipe)recipe.value()).getResultItem((HolderLookup.Provider)this.registryAccess).getItem() == item).forEach(recipe -> recipes.add(recipe.value()));
        this.recipeManager.getAllRecipesFor(RecipeType.SMITHING).stream().filter(recipe -> ((SmithingRecipe)recipe.value()).getResultItem((HolderLookup.Provider)this.registryAccess).getItem() == item).forEach(recipe -> recipes.add(recipe.value()));
        return recipes;
    }

    private RecipeResolutionResult selectBestPath(List<RecipeResolutionResult> paths) {
        return paths.stream().min(Comparator.comparingInt(path -> path.getTotalConsumption().size())).orElse(RecipeResolutionResult.failure("\u6ca1\u6709\u53ef\u7528\u8def\u5f84"));
    }

    private static RecipeResolutionResult selectBestFailure(Item targetItem, int requiredCount, List<RecipeResolutionResult> failedPaths) {
        if (failedPaths == null || failedPaths.isEmpty()) {
            return RecipeResolutionResult.failure("\u65e0\u6cd5\u901a\u8fc7\u4efb\u4f55\u914d\u65b9\u5408\u6210 " + String.valueOf(targetItem) + " (\u9700\u8981: " + requiredCount + ")");
        }
        Comparator<RecipeResolutionResult> comparator = Comparator.comparingInt(RecipeResolver::getFailureMissingSum).thenComparingInt(RecipeResolver::getFailureMissingTypes).thenComparingInt(RecipeResolutionResult::getComplexity);
        RecipeResolutionResult best = failedPaths.stream().filter(r -> RecipeResolver.getFailureMissingSum(r) != Integer.MAX_VALUE).min(comparator).orElse(null);
        if (best != null) {
            return best;
        }
        return failedPaths.get(0);
    }

    private static int getFailureMissingSum(RecipeResolutionResult result) {
        Map<Item, Integer> missing;
        Map<Item, Integer> map = missing = result == null ? null : result.getMissingMaterials();
        if (missing == null || missing.isEmpty()) {
            Map<Item, Integer> map2 = missing = result == null ? null : result.getTotalConsumption();
        }
        if (missing == null || missing.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        long sum = 0L;
        for (Integer v : missing.values()) {
            if (v == null || v <= 0 || (sum += (long)v.intValue()) < Integer.MAX_VALUE) continue;
            return Integer.MAX_VALUE;
        }
        return (int)sum;
    }

    private static int getFailureMissingTypes(RecipeResolutionResult result) {
        Map<Item, Integer> missing;
        Map<Item, Integer> map = missing = result == null ? null : result.getMissingMaterials();
        if (missing == null || missing.isEmpty()) {
            Map<Item, Integer> map2 = missing = result == null ? null : result.getTotalConsumption();
        }
        if (missing == null || missing.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        return missing.size();
    }

    public void clearCache() {
        this.recipeCache.clear();
    }

    /** "正向链缺料"估算用的小预算。 */
    private long estBudget = 0L;

    /** 解压来源索引:更密物品 → (可解压出的"无正向制造"细颗粒 → 每 1 个更密拆出的个数)。
        只收录"细颗粒本身没有正向制造配方"的(如金块→金锭→金粒、压缩石→石头),
        避免把 log→planks 这类正常 1→4 也算进去。 */
    private Map<Item, Map<Item, Integer>> decompressByDense = null;

    private Map<Item, Map<Item, Integer>> getDecompressByDense() {
        if (this.decompressByDense == null) {
            Map<Item, Map<Item, Integer>> shared = RecipeResolver.SHARED_DECOMPRESS_BY_DENSE.get(this.recipeManager);
            if (shared != null) {
                this.decompressByDense = shared;
                return shared;
            }
            HashMap<Item, Map<Item, Integer>> map = new HashMap<Item, Map<Item, Integer>>();
            for (Map.Entry<Item, List<CraftingRecipe>> e : this.getCraftingByResult().entrySet()) {
                for (CraftingRecipe recipe : e.getValue()) {
                    ItemStack fine;
                    try {
                        fine = recipe.getResultItem((HolderLookup.Provider)this.registryAccess);
                    } catch (Exception ex) { continue; }
                    if (fine == null || fine.isEmpty() || fine.getCount() <= 1) continue;
                    Item fineItem = fine.getItem();
                    if (fineItem == null || fineItem == Items.AIR) continue;
                    if (!this.getForwardCraftingByResult().getOrDefault(fineItem, Collections.emptyList()).isEmpty()) continue;
                    List<IngredientGroup> groups;
                    try {
                        groups = this.buildIngredientGroups(recipe);
                    } catch (Exception ex) { continue; }
                    if (groups.size() != 1 || groups.get(0).slotCount != 1) continue;
                    for (ItemStack ms : groups.get(0).ingredient.getItems()) {
                        Item dense = ms == null ? null : ms.getItem();
                        if (dense == null || dense == Items.AIR || dense == fineItem) continue;
                        map.computeIfAbsent(dense, k -> new HashMap<Item, Integer>()).put(fineItem, fine.getCount());
                    }
                }
            }
            this.decompressByDense = map;
            RecipeResolver.SHARED_DECOMPRESS_BY_DENSE.put(this.recipeManager, map);
        }
        return this.decompressByDense;
    }

    /** 把"仓库里已有的更密物品"可解压出的细颗粒补进可用池(不扣原物,仅用于缺料估算),
        使缺料报告不把 铁粒/金锭/钻石 当缺口(你仓库有对应块时),而是真正缺的块数?不——
        报告会落到真正缺的基础(如铁块够就不报铁粒)。 */
    private void expandAvailableByDecompress(Map<Item, Integer> stock) {
        Map<Item, Map<Item, Integer>> src = this.getDecompressByDense();
        if (src == null || src.isEmpty()) return;
        for (int pass = 0; pass < 6; ++pass) {
            boolean changed = false;
            HashMap<Item, Integer> snap = new HashMap<Item, Integer>(stock);
            for (Map.Entry<Item, Integer> se : snap.entrySet()) {
                int c = se.getValue();
                if (c <= 0) continue;
                Map<Item, Integer> fines = src.get(se.getKey());
                if (fines == null) continue;
                for (Map.Entry<Item, Integer> fe : fines.entrySet()) {
                    long addL = Math.min((long)Integer.MAX_VALUE - (long)stock.getOrDefault(fe.getKey(), 0), (long)c * (long)fe.getValue());
                    int add = (int)Math.max(0L, addL);
                    if (add <= 0) continue;
                    stock.merge(fe.getKey(), add, Integer::sum);
                    changed = true;
                }
            }
            if (!changed) break;
        }
    }

    /** 只沿"正向制造链"(不含解压)展开的缺料估算 —— 合成失败后用它给出真正的基础物缺料:
        1) 天然无循环(不看解压,三倍→二倍→压缩→石头,叶子即石头);
        2) 确定性贪心(不逐个试所有成员求最优,避免指数爆炸):配方选"原料组最少"者
           (白羊毛"4 线"只有 1 组原料,优先于"白染料+羊毛"),同族成员优先选头羊;
           于是没羊毛造床时,会一路落到 白羊毛→线 这条基础路线,报缺 线,而不是 墨囊 等染料来源;
        3) depth/预算硬上限,恒有结果(不会返回空)。 */
    public Map<Item, Integer> computeBaseShortage(Item target, int requiredCount, Map<Item, Integer> availableItems) {
        if (target == null || target == Items.AIR || requiredCount <= 0) {
            return Collections.emptyMap();
        }
        this.estBudget = 120000L;
        HashMap<Item, Integer> stock = new HashMap<Item, Integer>(availableItems == null ? Collections.emptyMap() : availableItems);
        this.expandAvailableByDecompress(stock);
        HashMap<Item, Integer> out = new HashMap<Item, Integer>();
        // 简单稳定模式:纯头羊确定性下钻(plain=true,不做成员择优/试算),永不空、永不跳色。
        this.greedyShortage(target, requiredCount, stock, new HashSet<Item>(), out, 0, true);
        return out;
    }

    /** 确定性贪心展开:把 need 个 item 沿"最少原料组"的正向配方下钻到基础物叶子,写入 out。
        同族组选头羊;库存能抵扣就抵扣;depth 封顶保证线性、不爆炸。
        plain=true 表示"纯头羊"下钻(用于评估候选成员,不套娃地再做择优),避免指数爆炸;
        plain=false 才会在成员之间择优(白床只要线 < 红床还要染料)。 */
    private void greedyShortage(Item item, int need, Map<Item, Integer> stock, Set<Item> onStack, Map<Item, Integer> out, int depth, boolean plain) {
        if (need <= 0) {
            return;
        }
        if (depth > 22 || --this.estBudget < 0L) {
            return;
        }
        int have = stock.getOrDefault(item, 0);
        if (have > 0) {
            int use = Math.min(have, need);
            stock.put(item, have - use);
            need -= use;
        }
        if (need <= 0) {
            return;
        }
        if (onStack.contains(item)) {
            out.merge(item, need, Integer::sum);
            return;
        }
        // 叶子判定:该物品若没有"正向(压缩)配方"(如黑曜石、石头、锭/粒这类只能靠更高一级解压或世界来源的),
        // 就把它当作基础物直接记缺口,不再走"解压更高一级→又绕回自己"的环
        // (它的解压来源已由 expandAvailableByDecompress 按仓库更高一级预补;没有就确实缺它)。
        if (this.getForwardCraftingByResult().getOrDefault(item, Collections.emptyList()).isEmpty()) {
            out.merge(item, need, Integer::sum);
            return;
        }
        // 用"制造配方过滤表"(=合成路径同一套):已剔除"消耗自身/同族的换色配方"(如 RS 淡蓝染灰接口),
        // 因此灰接口只会走零件配方,缺料就报 富石英铁 而非 兰花/骨头。彩色羊毛的染料配方(原料不含自身)
        // 与解压配方都保留;onStack 与深度上限兜住循环。
        List<CraftingRecipe> fwd = this.getCraftingRecipesForItem(item);
        if (fwd.isEmpty()) {
            out.merge(item, need, Integer::sum);
            return;
        }
        onStack.add(item);
        CraftingRecipe chosenRecipe = null;
        int minGroups = Integer.MAX_VALUE;
        for (CraftingRecipe recipe : fwd) {
            List<IngredientGroup> groups;
            try {
                groups = this.buildIngredientGroups(recipe);
            } catch (Exception e) {
                continue;
            }
            if (groups.isEmpty()) continue;
            if (groups.size() < minGroups) {
                minGroups = groups.size();
                chosenRecipe = recipe;
                if (minGroups == 1) break;
            }
        }
        if (chosenRecipe == null) {
            onStack.remove(item);
            out.merge(item, need, Integer::sum);
            return;
        }
        ItemStack rs;
        try {
            rs = chosenRecipe.getResultItem((HolderLookup.Provider)this.registryAccess);
        } catch (Exception e) {
            rs = null;
        }
        if (rs == null || rs.isEmpty()) {
            onStack.remove(item);
            out.merge(item, need, Integer::sum);
            return;
        }
        int yield = Math.max(1, rs.getCount());
        int times = (int)Math.ceil((double)need / (double)yield);
        for (IngredientGroup g : this.buildIngredientGroups(chosenRecipe)) {
            int gNeed = g.slotCount * times;
            ArrayList<Item> members = new ArrayList<Item>();
            for (ItemStack ms : g.ingredient.getItems()) {
                Item m = ms == null ? null : ms.getItem();
                if (m != null && m != Items.AIR && !members.contains(m)) members.add(m);
            }
            if (members.isEmpty()) {
                continue;
            }
            Item chosen = null;
            if (plain) {
                // 免染料白色基础变体最优先(白床/白羊毛);其次"族根"(从零件直接造的灰本体等,不依赖同族成员);
                // 再次配方数量最多;否则第一个不在当前链上的
                for (Item mem : members) {
                    if (RecipeResolver.isPreferredBase(mem)) { chosen = mem; break; }
                }
                if (chosen == null) {
                    HashSet<Item> fam = new HashSet<Item>(members);
                    for (Item mem : members) {
                        if (this.isFamilyRoot(mem, fam)) { chosen = mem; break; }
                    }
                }
                if (chosen == null) {
                    int bestCount = -1;
                    for (Item mem : members) {
                        int c = this.memberRecipeCount(mem);
                        if (c > bestCount) { bestCount = c; chosen = mem; }
                    }
                }
                if (chosen == null) {
                    for (Item mem : members) {
                        if (!onStack.contains(mem)) { chosen = mem; break; }
                    }
                }
                if (chosen == null) chosen = members.get(0);
            } else {
                // 择优:每个成员用 plain(纯头羊、不套娃)深算缺料,取"种类最少、数量最少"者。
                // 例:做"任意床"时,白床只要 线(1 类) < 红床要 线+红色郁金香(2 类) → 白床胜出。
                HashMap<Item, Integer> stockSnap = new HashMap<Item, Integer>(stock);
                long chosenScore = Long.MAX_VALUE;
                for (Item mem : members) {
                    HashMap<Item, Integer> tmp = new HashMap<Item, Integer>();
                    HashMap<Item, Integer> trialStock = new HashMap<Item, Integer>(stockSnap);
                    this.greedyShortage(mem, gNeed, trialStock, new HashSet<Item>(onStack), tmp, depth + 1, true);
                    if (tmp.isEmpty()) { chosen = mem; break; }
                    long sum = 0L;
                    for (Integer v : tmp.values()) sum += (long)v;
                    long score = (long)tmp.size() * 1000000L + sum;
                    if (score < chosenScore) {
                        chosenScore = score;
                        chosen = mem;
                    }
                }
                if (chosen == null) chosen = members.get(0);
            }
            this.greedyShortage(chosen, gNeed, stock, onStack, out, depth + 1, plain);
        }
        onStack.remove(item);
    }

    /** ---- 规范树(阶段一,库存无关,按 Item 记忆化,构建一次) ---- */

    private final Map<Item, CanonPlan> canonCache = new HashMap<Item, CanonPlan>();
    private final Map<Item, List<Item>> canonLeafMemo = new HashMap<Item, List<Item>>();

    /** 无库存时"制造 item 需用到"的不同基础叶子的最小集合(仅用于结构定型时选代表/配方)。
        染料这类共享菱形只算一次并被记忆化,不受某个仓库状态影响。 */
    private List<Item> canonLeaf(Item item, Set<Item> progress) {
        if (item == null || item == Items.AIR) {
            return new ArrayList<Item>();
        }
        List<Item> hit = this.canonLeafMemo.get(item);
        if (hit != null) {
            return hit;
        }
        if (progress.contains(item)) {
            ArrayList<Item> cyc = new ArrayList<Item>();
            cyc.add(item);
            return cyc;
        }
        List<CraftingRecipe> fwd = this.getForwardCraftingByResult().getOrDefault(item, Collections.emptyList());
        if (fwd.isEmpty()) {
            ArrayList<Item> single = new ArrayList<Item>();
            single.add(item);
            this.canonLeafMemo.put(item, single);
            return single;
        }
        progress.add(item);
        List<Item> best = null;
        for (CraftingRecipe recipe : fwd) {
            List<IngredientGroup> groups;
            try {
                groups = this.buildIngredientGroups(recipe);
            } catch (Exception e) {
                continue;
            }
            if (groups.isEmpty()) continue;
            LinkedHashSet<Item> acc = new LinkedHashSet<Item>();
            boolean ok = true;
            for (IngredientGroup g : groups) {
                ArrayList<Item> members = new ArrayList<Item>();
                for (ItemStack ms : g.ingredient.getItems()) {
                    Item m = ms == null ? null : ms.getItem();
                    if (m != null && m != Items.AIR && !members.contains(m)) members.add(m);
                }
                if (members.isEmpty()) { ok = false; break; }
                List<Item> bm = null;
                for (Item mem : members) {
                    List<Item> lf = this.canonLeaf(mem, progress);
                    if (bm == null || lf.size() < bm.size()) bm = lf;
                }
                acc.addAll(bm);
            }
            if (!ok) continue;
            if (best == null || acc.size() < best.size()) best = new ArrayList<Item>(acc);
            if (best.size() == 1) break;
        }
        progress.remove(item);
        if (best == null) {
            best = new ArrayList<Item>();
            best.add(item);
        }
        best.sort(Comparator.comparing(Item::getDescriptionId));
        this.canonLeafMemo.put(item, best);
        return best;
    }

    /** 给物品定一棵"标准制造树":配方 = 叶子集合最小的;每个标签槽位选"叶子最少"的代表成员
        (做"任意床"会选中白床——它只要线;白羊毛同理压过彩色)。递归、按 Item 缓存。 */
    private CanonPlan canonPlan(Item item, Set<Item> progress) {
        if (item == null || item == Items.AIR) {
            return CanonPlan.leaf(item);
        }
        CanonPlan cached = this.canonCache.get(item);
        if (cached != null) {
            return cached;
        }
        if (progress.contains(item)) {
            CanonPlan cycLeaf = CanonPlan.leaf(item);
            this.canonCache.put(item, cycLeaf);
            return cycLeaf;
        }
        List<CraftingRecipe> fwd = this.getForwardCraftingByResult().getOrDefault(item, Collections.emptyList());
        if (fwd.isEmpty()) {
            CanonPlan lp = CanonPlan.leaf(item);
            this.canonCache.put(item, lp);
            return lp;
        }
        progress.add(item);
        CanonPlan best = null;
        int bestLeaf = Integer.MAX_VALUE;
        for (CraftingRecipe recipe : fwd) {
            ItemStack rs;
            try {
                rs = recipe.getResultItem((HolderLookup.Provider)this.registryAccess);
            } catch (Exception e) { continue; }
            if (rs == null || rs.isEmpty()) continue;
            List<IngredientGroup> groups;
            try {
                groups = this.buildIngredientGroups(recipe);
            } catch (Exception e) { continue; }
            if (groups.isEmpty()) continue;
            ArrayList<CanonSlot> slots = new ArrayList<CanonSlot>();
            LinkedHashSet<Item> leafAcc = new LinkedHashSet<Item>();
            boolean ok = true;
            for (IngredientGroup g : groups) {
                ArrayList<Item> members = new ArrayList<Item>();
                for (ItemStack ms : g.ingredient.getItems()) {
                    Item m = ms == null ? null : ms.getItem();
                    if (m != null && m != Items.AIR && !members.contains(m)) members.add(m);
                }
                if (members.isEmpty()) { ok = false; break; }
                Item repPick = members.get(0);
                int repLeaf = Integer.MAX_VALUE;
                for (Item mem : members) {
                    List<Item> lf = this.canonLeaf(mem, progress);
                    if (lf.size() < repLeaf) {
                        repLeaf = lf.size();
                        repPick = mem;
                    }
                }
                slots.add(new CanonSlot(repPick, members, g.slotCount));
                leafAcc.addAll(this.canonLeaf(repPick, progress));
            }
            if (!ok) continue;
            if (best == null || leafAcc.size() < bestLeaf) {
                bestLeaf = leafAcc.size();
                best = new CanonPlan(item, Math.max(1, rs.getCount()), false, slots);
            }
            if (bestLeaf == 1) break;
        }
        progress.remove(item);
        if (best == null) {
            CanonPlan lp = CanonPlan.leaf(item);
            this.canonCache.put(item, lp);
            return lp;
        }
        this.canonCache.put(item, best);
        return best;
    }

    /** 阶段二:沿固定规范树线性扣库存,缺到叶子就记入 out。无回溯、无成员枚举。 */
    private void walkPlan(CanonPlan plan, int need, Map<Item, Integer> stock, Map<Item, Integer> out) {
        if (plan == null || need <= 0) {
            return;
        }
        Item it = plan.item;
        if (it == null || it == Items.AIR) {
            return;
        }
        int have = stock.getOrDefault(it, 0);
        if (have > 0) {
            int use = Math.min(have, need);
            stock.put(it, have - use);
            need -= use;
        }
        if (need <= 0) {
            return;
        }
        if (plan.leaf || plan.slots.isEmpty()) {
            out.merge(it, need, Integer::sum);
            return;
        }
        int times = (int)Math.ceil((double)need / (double)Math.max(1, plan.yield));
        for (CanonSlot slot : plan.slots) {
            int childNeed = slot.count * times;
            this.walkPlan(this.canonPlan(slot.rep, new HashSet<Item>()), childNeed, stock, out);
        }
    }

    /** 超时兜底:对目标物品按"第一个工作台配方各组直接缺口"做一次不递归的估算,
        保证即使深搜爆炸/超时,也能给玩家一个"缺什么"的明确提示,而不是空白。 */
    public Map<Item, Integer> estimateTopLevelMissing(Item targetItem, int requiredCount, Map<Item, Integer> availableItems) {
        if (targetItem == null || targetItem == Items.AIR || requiredCount <= 0) {
            return Collections.emptyMap();
        }
        for (CraftingRecipe recipe : this.getCraftingRecipesForItem(targetItem)) {
            Map<Item, Integer> miss = this.directShortfallForRecipe(recipe, requiredCount, availableItems);
            if (miss != null && !miss.isEmpty()) {
                return miss;
            }
        }
        return Collections.emptyMap();
    }

    /** 单一配方各原料组的直接缺口(不递归)。缺口挂在"该组库存最多的成员"上,
        使提示贴近你实际拥有的颜色,而不是永远第一个成员。 */
    private Map<Item, Integer> directShortfallForRecipe(Recipe<?> recipe, int requiredCount, Map<Item, Integer> availableItems) {
        ItemStack result = recipe.getResultItem((HolderLookup.Provider)this.registryAccess);
        if (result == null || result.isEmpty()) {
            return Collections.emptyMap();
        }
        int yield = Math.max(1, result.getCount());
        int times = (int)Math.ceil((double)requiredCount / (double)yield);
        HashMap<Item, Integer> miss = new HashMap<Item, Integer>();
        for (IngredientGroup g : this.buildIngredientGroups(recipe)) {
            int gNeed = g.slotCount * times;
            long have = 0L;
            Item rep = null;
            long repStock = -1L;
            for (ItemStack s : g.ingredient.getItems()) {
                Item m = s == null ? null : s.getItem();
                if (m == null || m == Items.AIR) continue;
                long st = availableItems == null ? 0L : (long)availableItems.getOrDefault(m, 0);
                have += st;
                if (st > repStock) {
                    repStock = st;
                    rep = m;
                }
            }
            if (rep != null && have < (long)gNeed) {
                miss.merge(rep, (int)Math.min((long)Integer.MAX_VALUE, (long)gNeed - have), Integer::sum);
            }
        }
        return miss;
    }

    private static final class CanonPlan {
        private final Item item;
        private final int yield;
        private final boolean leaf;
        private final List<CanonSlot> slots;

        private CanonPlan(Item item, int yield, boolean leaf, List<CanonSlot> slots) {
            this.item = item;
            this.yield = yield;
            this.leaf = leaf;
            this.slots = slots == null ? Collections.emptyList() : slots;
        }

        private static CanonPlan leaf(Item item) {
            return new CanonPlan(item, 1, true, Collections.<CanonSlot>emptyList());
        }
    }

    private static final class CanonSlot {
        private final Item rep;
        private final List<Item> alternatives;
        private final int count;

        private CanonSlot(Item rep, List<Item> alternatives, int count) {
            this.rep = rep;
            this.alternatives = alternatives == null ? Collections.emptyList() : alternatives;
            this.count = count;
        }
    }

    private static final class IngredientGroup {
        private final Ingredient ingredient;
        private int slotCount;

        private IngredientGroup(Ingredient ingredient, int slotCount) {
            this.ingredient = ingredient;
            this.slotCount = slotCount;
        }
    }

    private static final class MissingCandidate {
        private final MissingInfo info;
        private final Map<Item, Integer> available;

        private MissingCandidate(MissingInfo info, Map<Item, Integer> available) {
            this.info = info;
            this.available = available == null ? Collections.emptyMap() : available;
        }
    }

    private static final class MissingInfo {
        private final Map<Item, Integer> missing;
        private final int maxDepth;

        private MissingInfo(Map<Item, Integer> missing, int maxDepth) {
            this.missing = missing == null ? Collections.emptyMap() : missing;
            this.maxDepth = maxDepth;
        }
    }
}

