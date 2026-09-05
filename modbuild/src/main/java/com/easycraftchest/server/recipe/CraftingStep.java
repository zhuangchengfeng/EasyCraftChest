/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.world.item.Item
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.crafting.Recipe
 */
package com.easycraftchest.server.recipe;

import java.util.List;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

public class CraftingStep {
    private final ItemStack outputPrototype;
    private final int outputCount;
    private final Map<Item, Integer> requiredMaterials;
    private final List<Recipe<?>> recipes;
    private final StepType stepType;

    public CraftingStep(ItemStack outputPrototype, int outputCount, Map<Item, Integer> requiredMaterials, List<Recipe<?>> recipes) {
        ItemStack itemStack = this.outputPrototype = outputPrototype == null ? ItemStack.EMPTY : outputPrototype.copy();
        if (!this.outputPrototype.isEmpty()) {
            this.outputPrototype.setCount(1);
        }
        this.outputCount = outputCount;
        this.requiredMaterials = requiredMaterials;
        this.recipes = recipes;
        this.stepType = this.determineStepType(recipes);
    }

    private StepType determineStepType(List<Recipe<?>> recipes) {
        if (recipes.isEmpty()) {
            return StepType.DIRECT_USE;
        }
        Recipe<?> recipe = recipes.get(0);
        return switch (recipe.getType().toString()) {
            case "minecraft:crafting" -> StepType.CRAFTING;
            case "minecraft:smelting" -> StepType.SMELTING;
            case "minecraft:blasting" -> StepType.BLASTING;
            case "minecraft:smoking" -> StepType.SMOKING;
            case "minecraft:stonecutting" -> StepType.STONECUTTING;
            case "minecraft:smithing" -> StepType.SMITHING;
            default -> StepType.CRAFTING;
        };
    }

    public Item getOutputItem() {
        return this.outputPrototype.getItem();
    }

    public ItemStack getOutputPrototype() {
        return this.outputPrototype.copy();
    }

    public int getOutputCount() {
        return this.outputCount;
    }

    public Map<Item, Integer> getRequiredMaterials() {
        return this.requiredMaterials;
    }

    public List<Recipe<?>> getRecipes() {
        return this.recipes;
    }

    public StepType getStepType() {
        return this.stepType;
    }

    public boolean isDirectUse() {
        return this.stepType == StepType.DIRECT_USE;
    }

    public boolean requiresCrafting() {
        return this.stepType != StepType.DIRECT_USE;
    }

    public String getDescription() {
        if (this.isDirectUse()) {
            return String.format("\u4f7f\u7528\u73b0\u6709\u7684 %s x%d", this.getOutputItem(), this.outputCount);
        }
        String action = switch (this.stepType.ordinal()) {
            case 1 -> "\u5408\u6210";
            case 2 -> "\u7194\u70bc";
            case 3 -> "\u9ad8\u7089\u51b6\u70bc";
            case 4 -> "\u70df\u718f";
            case 5 -> "\u5207\u77f3";
            case 6 -> "\u953b\u9020";
            default -> "\u5236\u4f5c";
        };
        return String.format("%s %s x%d", action, this.getOutputItem(), this.outputCount);
    }

    public String getMaterialsDescription() {
        if (this.requiredMaterials.isEmpty()) {
            return "\u65e0\u9700\u6750\u6599";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Item, Integer> entry : this.requiredMaterials.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append(" x").append(entry.getValue());
            first = false;
        }
        return sb.toString();
    }

    public String toString() {
        return String.format("CraftingStep{%s, materials: [%s]}", this.getDescription(), this.getMaterialsDescription());
    }

    public static enum StepType {
        DIRECT_USE,
        CRAFTING,
        SMELTING,
        BLASTING,
        SMOKING,
        STONECUTTING,
        SMITHING;

    }
}

