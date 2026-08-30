/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.world.item.Item
 */
package com.stroeud.server.recipe;

import com.stroeud.server.recipe.CraftingStep;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.Item;

public class RecipeResolutionResult {
    private final boolean success;
    private final List<CraftingStep> craftingSteps;
    private final Map<Item, Integer> totalConsumption;
    private final Map<Item, Integer> missingMaterials;
    private final String errorMessage;

    private RecipeResolutionResult(boolean success, List<CraftingStep> craftingSteps, Map<Item, Integer> totalConsumption, Map<Item, Integer> missingMaterials, String errorMessage) {
        this.success = success;
        this.craftingSteps = craftingSteps != null ? craftingSteps : Collections.emptyList();
        this.totalConsumption = totalConsumption != null ? totalConsumption : Collections.emptyMap();
        this.missingMaterials = missingMaterials != null ? missingMaterials : Collections.emptyMap();
        this.errorMessage = errorMessage;
    }

    public static RecipeResolutionResult success(List<CraftingStep> craftingSteps) {
        return new RecipeResolutionResult(true, craftingSteps, Collections.emptyMap(), Collections.emptyMap(), null);
    }

    public static RecipeResolutionResult success(List<CraftingStep> craftingSteps, Map<Item, Integer> totalConsumption) {
        return new RecipeResolutionResult(true, craftingSteps, totalConsumption, Collections.emptyMap(), null);
    }

    public static RecipeResolutionResult failure(String errorMessage) {
        return new RecipeResolutionResult(false, Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap(), errorMessage);
    }

    public static RecipeResolutionResult failure(String errorMessage, Map<Item, Integer> missingMaterials) {
        return new RecipeResolutionResult(false, Collections.emptyList(), Collections.emptyMap(), missingMaterials, errorMessage);
    }

    public static RecipeResolutionResult failureWithConsumption(String errorMessage, Map<Item, Integer> totalConsumption) {
        return new RecipeResolutionResult(false, Collections.emptyList(), totalConsumption, Collections.emptyMap(), errorMessage);
    }

    public boolean isSuccess() {
        return this.success;
    }

    public List<CraftingStep> getCraftingSteps() {
        return this.craftingSteps;
    }

    public Map<Item, Integer> getTotalConsumption() {
        return this.totalConsumption;
    }

    public Map<Item, Integer> getMissingMaterials() {
        return this.missingMaterials;
    }

    public String getErrorMessage() {
        return this.errorMessage;
    }

    public int getComplexity() {
        return this.craftingSteps.size();
    }

    public int getBaseMaterialCount() {
        return this.totalConsumption.values().stream().mapToInt(Integer::intValue).sum();
    }

    public String getPathDescription() {
        if (!this.success) {
            StringBuilder sb = new StringBuilder();
            sb.append("\u5408\u6210\u5931\u8d25: ").append(this.errorMessage);
            if (!this.missingMaterials.isEmpty()) {
                sb.append("\n\u7f3a\u5c11\u6750\u6599:");
                for (Map.Entry<Item, Integer> entry : this.missingMaterials.entrySet()) {
                    sb.append("\n  - ").append(entry.getKey().getDescriptionId()).append(" x").append(entry.getValue());
                }
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\u5408\u6210\u8def\u5f84 (").append(this.craftingSteps.size()).append("\u6b65):");
        for (int i = 0; i < this.craftingSteps.size(); ++i) {
            CraftingStep step = this.craftingSteps.get(i);
            sb.append("\n").append(i + 1).append(". ");
            sb.append(step.getDescription());
        }
        if (!this.totalConsumption.isEmpty()) {
            sb.append("\n\n\u6240\u9700\u57fa\u7840\u6750\u6599:");
            for (Map.Entry<Item, Integer> entry : this.totalConsumption.entrySet()) {
                sb.append("\n  - ").append(entry.getKey().getDescriptionId()).append(" x").append(entry.getValue());
            }
        }
        return sb.toString();
    }

    public String toString() {
        if (!this.success) {
            return "RecipeResolutionResult{failed: " + this.errorMessage + "}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("RecipeResolutionResult{success: true, steps: ").append(this.craftingSteps.size());
        if (!this.totalConsumption.isEmpty()) {
            sb.append(", materials: ").append(this.totalConsumption.size());
        }
        sb.append("}");
        return sb.toString();
    }
}

