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
package com.stroeud.server.recipe;

import com.mojang.logging.LogUtils;
import com.stroeud.config.ModConfigs;
import com.stroeud.server.recipe.CraftingStep;
import com.stroeud.server.recipe.RecipeResolutionResult;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
        MissingInfo full = this.computeMissingInfoCraftingOnly(targetItem, requiredCount, availableItems, -1);
        if (full.missing.isEmpty()) {
            return Collections.emptyList();
        }
        int baseDepth = Math.max(0, full.maxDepth);
        ArrayList<Map<Item, Integer>> alternatives = new ArrayList<Map<Item, Integer>>();
        for (int i = 0; i < limit && (depthLimit = baseDepth - i) >= 0; ++i) {
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
        Item[] chosen = new Item[groups.size()];
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

    private RecipeResolutionResult tryResolveIngredientGroups(Recipe<?> recipe, int recipeYield, int craftingTimes, Map<Item, Integer> availableItems, Set<Item> visitedItems, int depth, List<IngredientGroup> groups, int groupIndex, Item[] chosen, List<CraftingStep> stepsAccum, Map<Item, Integer> consumptionAccum, List<RecipeResolutionResult> failures) {
        if (groupIndex >= groups.size()) {
            return this.buildFinalStep(recipe, recipeYield, craftingTimes, groups, chosen, stepsAccum, consumptionAccum);
        }
        IngredientGroup group = groups.get(groupIndex);
        int needed = group.slotCount * craftingTimes;
        for (Item candidate : this.orderedIngredientCandidates(group.ingredient, availableItems)) {
            if (this.checkTimeout()) {
                return null;
            }
            if (visitedItems.contains(candidate)) {
                continue;
            }
            HashSet<Item> materialVisited = new HashSet<Item>(visitedItems);
            RecipeResolutionResult materialResult = this.resolveRecipeRecursiveCraftingOnly(candidate, needed, availableItems, materialVisited, depth + 1);
            if (materialResult.isSuccess()) {
                ArrayList<CraftingStep> steps = new ArrayList<CraftingStep>(stepsAccum);
                steps.addAll(materialResult.getCraftingSteps());
                HashMap<Item, Integer> consumption = new HashMap<Item, Integer>(consumptionAccum);
                for (Map.Entry<Item, Integer> e : materialResult.getTotalConsumption().entrySet()) {
                    consumption.merge(e.getKey(), e.getValue(), Integer::sum);
                }
                chosen[groupIndex] = candidate;
                RecipeResolutionResult deeper = this.tryResolveIngredientGroups(recipe, recipeYield, craftingTimes, availableItems, visitedItems, depth, groups, groupIndex + 1, chosen, steps, consumption, failures);
                if (deeper != null && deeper.isSuccess()) {
                    return deeper;
                }
                if (deeper != null) {
                    failures.add(deeper);
                }
            } else {
                failures.add(materialResult);
            }
        }
        return null;
    }

    private RecipeResolutionResult buildFinalStep(Recipe<?> recipe, int recipeYield, int craftingTimes, List<IngredientGroup> groups, Item[] chosen, List<CraftingStep> steps, Map<Item, Integer> consumption) {
        HashMap<Item, Integer> requiredMaterials = new HashMap<Item, Integer>();
        for (int i = 0; i < groups.size(); ++i) {
            Item chosenItem = chosen[i];
            if (chosenItem == null || chosenItem == Items.AIR) continue;
            requiredMaterials.merge(chosenItem, groups.get(i).slotCount * craftingTimes, Integer::sum);
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

    private List<Item> orderedIngredientCandidates(Ingredient ingredient, Map<Item, Integer> availableItems) {
        ArrayList<Item> availableFirst = new ArrayList<Item>();
        ArrayList<Item> rest = new ArrayList<Item>();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (item == null || item == Items.AIR) continue;
            List<Item> target = availableItems != null && availableItems.getOrDefault(item, 0) > 0 ? availableFirst : rest;
            if (!target.contains(item)) {
                target.add(item);
            }
        }
        availableFirst.addAll(rest);
        if (availableFirst.size() > this.maxIngredientCandidates) {
            return new ArrayList<Item>(availableFirst.subList(0, this.maxIngredientCandidates));
        }
        return availableFirst;
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

    private List<CraftingRecipe> getCraftingRecipesForItem(Item item) {
        if (item == null || item == Items.AIR) {
            return Collections.emptyList();
        }
        return this.getCraftingByResult().getOrDefault(item, Collections.emptyList());
    }

    /** 按产物物品索引的工作台配方表:一次构建,查询 O(1),避免每次递归都全量扫配方导致节点代价过高。 */
    private Map<Item, List<CraftingRecipe>> craftingByResult = null;

    private Map<Item, List<CraftingRecipe>> getCraftingByResult() {
        if (this.craftingByResult == null) {
            HashMap<Item, List<CraftingRecipe>> index = new HashMap<Item, List<CraftingRecipe>>();
            for (RecipeHolder<CraftingRecipe> holder : this.recipeManager.getAllRecipesFor(RecipeType.CRAFTING)) {
                CraftingRecipe recipe = holder.value();
                if (recipe == null) continue;
                Item result = recipe.getResultItem((HolderLookup.Provider)this.registryAccess).getItem();
                if (result == null || result == Items.AIR) continue;
                index.computeIfAbsent(result, k -> new ArrayList<CraftingRecipe>()).add(recipe);
            }
            this.craftingByResult = index;
        }
        return this.craftingByResult;
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
        MissingCandidate best = null;
        for (Item candidate : this.orderedIngredientCandidates(group.ingredient, availableItems)) {
            if (this.checkTimeout()) {
                return null;
            }
            if (visitedItems.contains(candidate)) {
                continue;
            }
            HashMap<Item, Integer> snapshot = new HashMap<Item, Integer>(availableItems);
            HashSet<Item> visitedClone = new HashSet<Item>(visitedItems);
            MissingInfo part = this.computeMissingRecursiveCraftingOnlyWithDepth(candidate, needed, availableItems, visitedClone, depth + 1, maxDepthLimit);
            if (part == null) {
                availableItems.clear();
                availableItems.putAll(snapshot);
                continue;
            }
            HashMap<Item, Integer> mergedMissing = new HashMap<Item, Integer>(accumulated.missing);
            for (Map.Entry<Item, Integer> e : part.missing.entrySet()) {
                mergedMissing.merge(e.getKey(), e.getValue(), Integer::sum);
            }
            MissingInfo newAccum = new MissingInfo(mergedMissing, Math.max(accumulated.maxDepth, part.maxDepth));
            MissingCandidate deeper = this.computeBestMissingForGroups(groups, groupIndex + 1, craftingTimes, availableItems, visitedClone, depth, maxDepthLimit, newAccum);
            availableItems.clear();
            availableItems.putAll(snapshot);
            if (deeper != null && (best == null || RecipeResolver.isMissingBetter(deeper.info.missing, best.info.missing))) {
                best = deeper;
                if (best.info.missing.isEmpty()) {
                    return best;
                }
            }
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

