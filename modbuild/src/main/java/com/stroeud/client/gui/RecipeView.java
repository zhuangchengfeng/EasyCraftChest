package com.stroeud.client.gui;

import com.stroeud.network.NetworkManager;
import com.stroeud.network.packet.TrySynthesisPacket;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;

/**
 * 内嵌合成视图:在存储主界面的右下角显示"目标物品的配方 + 合成次数 + 合成按钮"。
 * 逻辑从 JeiStyleRecipeScreen 抽出,改为可在任意位置渲染的紧凑版本。
 */
public class RecipeView {
    private final BlockPos storagePos;
    private ItemStack targetItem = ItemStack.EMPTY;
    private final List<Recipe<?>> recipes = new ArrayList<Recipe<?>>();
    private int currentRecipeIndex = 0;
    private int panelX = 0;
    private int panelY = 0;
    private EditBox synthesisCountField;
    private Button prevRecipeButton;
    private Button nextRecipeButton;
    private Button trySynthesisButton;
    private ItemStack hoveredStack = ItemStack.EMPTY;
    private final List<String> resultLines = new ArrayList<String>();
    private boolean resultIsError = false;

    public RecipeView(BlockPos storagePos) {
        this.storagePos = storagePos;
    }

    public void setTarget(ItemStack item) {
        this.recipes.clear();
        this.currentRecipeIndex = 0;
        this.clearResult();
        if (item == null || item.isEmpty()) {
            this.targetItem = ItemStack.EMPTY;
        } else {
            this.targetItem = item.copy();
            this.findRecipes();
        }
        this.updateControls();
    }

    /** 显示合成结果:成功则清空;失败则在配方栏内显示缺失材料或错误信息。 */
    public void showResult(boolean success, String message, Map<String, Long> missing) {
        this.resultLines.clear();
        this.resultIsError = !success;
        if (!success && missing != null && !missing.isEmpty()) {
            this.resultLines.add("缺少材料:");
            for (Map.Entry<String, Long> e : missing.entrySet()) {
                if (this.resultLines.size() >= 4) {
                    break;
                }
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(e.getKey()));
                ItemStack stack = item == null ? ItemStack.EMPTY : new ItemStack(item);
                String name = stack.isEmpty() ? e.getKey() : stack.getHoverName().getString();
                this.resultLines.add(name + " x" + e.getValue());
            }
        } else if (message != null && !message.isEmpty()) {
            this.resultLines.add(message);
        }
    }

    public void clearResult() {
        this.resultLines.clear();
    }

    public boolean hasRecipes() {
        return !this.recipes.isEmpty();
    }

    public ItemStack getTargetItem() {
        return this.targetItem;
    }

    public void setSynthesisCount(String count) {
        if (this.synthesisCountField != null && count != null) {
            this.synthesisCountField.setValue(count);
        }
    }

    public String getSynthesisCount() {
        if (this.synthesisCountField != null) {
            return this.synthesisCountField.getValue();
        }
        return "1";
    }

    public String getTargetItemId() {
        if (this.targetItem == null || this.targetItem.isEmpty()) {
            return "";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(this.targetItem.getItem());
        return key == null ? "" : key.toString();
    }

    private void findRecipes() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        RecipeManager recipeManager = level.getRecipeManager();
        this.findCraftingRecipes(recipeManager, (Level)level);
    }

    private void findCraftingRecipes(RecipeManager recipeManager, Level level) {
        recipeManager.getAllRecipesFor(RecipeType.CRAFTING).stream().filter(recipe -> ((CraftingRecipe)recipe.value()).getResultItem((HolderLookup.Provider)level.registryAccess()).getItem() == this.targetItem.getItem()).forEach(recipe -> this.recipes.add(recipe.value()));
    }

    private void findSmeltingRecipes(RecipeManager recipeManager, Level level) {
        recipeManager.getAllRecipesFor(RecipeType.SMELTING).stream().filter(recipe -> ((SmeltingRecipe)recipe.value()).getResultItem((HolderLookup.Provider)level.registryAccess()).getItem() == this.targetItem.getItem()).forEach(recipe -> this.recipes.add(recipe.value()));
    }

    private void findBlastingRecipes(RecipeManager recipeManager, Level level) {
        recipeManager.getAllRecipesFor(RecipeType.BLASTING).stream().filter(recipe -> ((BlastingRecipe)recipe.value()).getResultItem((HolderLookup.Provider)level.registryAccess()).getItem() == this.targetItem.getItem()).forEach(recipe -> this.recipes.add(recipe.value()));
    }

    private void findSmokingRecipes(RecipeManager recipeManager, Level level) {
        recipeManager.getAllRecipesFor(RecipeType.SMOKING).stream().filter(recipe -> ((SmokingRecipe)recipe.value()).getResultItem((HolderLookup.Provider)level.registryAccess()).getItem() == this.targetItem.getItem()).forEach(recipe -> this.recipes.add(recipe.value()));
    }

    private void findCampfireCookingRecipes(RecipeManager recipeManager, Level level) {
        recipeManager.getAllRecipesFor(RecipeType.CAMPFIRE_COOKING).stream().filter(recipe -> ((CampfireCookingRecipe)recipe.value()).getResultItem((HolderLookup.Provider)level.registryAccess()).getItem() == this.targetItem.getItem()).forEach(recipe -> this.recipes.add(recipe.value()));
    }

    private void findStonecuttingRecipes(RecipeManager recipeManager, Level level) {
        recipeManager.getAllRecipesFor(RecipeType.STONECUTTING).stream().filter(recipe -> ((StonecutterRecipe)recipe.value()).getResultItem((HolderLookup.Provider)level.registryAccess()).getItem() == this.targetItem.getItem()).forEach(recipe -> this.recipes.add(recipe.value()));
    }

    private void findSmithingRecipes(RecipeManager recipeManager, Level level) {
        recipeManager.getAllRecipesFor(RecipeType.SMITHING).stream().filter(recipe -> ((SmithingRecipe)recipe.value()).getResultItem((HolderLookup.Provider)level.registryAccess()).getItem() == this.targetItem.getItem()).forEach(recipe -> this.recipes.add(recipe.value()));
    }

    /** 在面板 (px, py) 处创建控件(上一/下一配方、合成次数、合成按钮)。由父界面 addRenderableWidget。 */
    public void createControls(Font font, int px, int py) {
        this.panelX = px;
        this.panelY = py;
        int ry = py + 8 + 54 + 18;
        this.synthesisCountField = new EditBox(font, px + 8, ry, 50, 16, Component.literal("次数"));
        this.synthesisCountField.setValue("1");
        this.synthesisCountField.setMaxLength(3);
        this.synthesisCountField.setFilter(text -> {
            if (text.isEmpty()) {
                return true;
            }
            try {
                int value = Integer.parseInt(text);
                return value > 0 && value <= 999;
            }
            catch (NumberFormatException e) {
                return false;
            }
        });
        this.trySynthesisButton = Button.builder(Component.literal("合成"), b -> this.onTrySynthesis()).bounds(px + 62, ry, 60, 16).build();
        this.updateControls();
    }

    public Button getTrySynthesisButton() {
        return this.trySynthesisButton;
    }

    public EditBox getSynthesisCountField() {
        return this.synthesisCountField;
    }

    public void updateControls() {
        boolean has = this.hasRecipes();
        if (this.trySynthesisButton != null) {
            this.trySynthesisButton.visible = has;
            this.trySynthesisButton.active = has;
        }
        if (this.synthesisCountField != null) {
            this.synthesisCountField.setVisible(has);
        }
    }

    public void render(GuiGraphics graphics, Font font, int px, int py, int mouseX, int mouseY) {
        this.hoveredStack = ItemStack.EMPTY;
        if (!this.hasRecipes() || this.targetItem.isEmpty()) {
            String hint = "点击右侧物品查看配方";
            int hx = px + (162 - font.width(hint)) / 2;
            graphics.drawString(font, hint, hx, py + 45, 0xFFFFFF);
            return;
        }
        if (!this.resultLines.isEmpty()) {
            int lineY = py + 12;
            for (String line : this.resultLines) {
                graphics.drawString(font, line, px + 8, lineY, this.resultIsError ? 0xFF5555 : 0xFFFFFF);
                lineY += 10;
                if (lineY > py + 62) {
                    break;
                }
            }
            return;
        }
        Recipe<?> current = this.recipes.get(this.currentRecipeIndex);
        int rx = px + 8;
        int ry = py + 8;
        if (current instanceof CraftingRecipe) {
            this.renderCraftingRecipe(graphics, font, (CraftingRecipe)current, rx, ry, mouseX, mouseY);
        } else if (current instanceof AbstractCookingRecipe) {
            this.renderCookingRecipe(graphics, font, (AbstractCookingRecipe)current, rx, ry, mouseX, mouseY);
        } else if (current instanceof StonecutterRecipe) {
            this.renderStonecutterRecipe(graphics, font, (StonecutterRecipe)current, rx, ry, mouseX, mouseY);
        } else if (current instanceof SmithingRecipe) {
            this.renderSmithingRecipe(graphics, font, (SmithingRecipe)current, rx, ry, mouseX, mouseY);
        }
        String idx = String.format("%d/%d", this.currentRecipeIndex + 1, this.recipes.size());
        int idxWidth = font.width(idx);
        graphics.drawString(font, idx, px + (162 - idxWidth) / 2, py + 72, 0xFFFFFF);
        if (!this.hoveredStack.isEmpty()) {
            graphics.renderTooltip(font, this.hoveredStack, mouseX, mouseY);
        }
    }

    private void renderCraftingRecipe(GuiGraphics graphics, Font font, CraftingRecipe recipe, int x, int y, int mouseX, int mouseY) {
        NonNullList ingredients = recipe.getIngredients();
        for (int i = 0; i < 9; ++i) {
            int row = i / 3;
            int col = i % 3;
            int slotX = x + col * 18;
            int slotY = y + row * 18;
            Ingredient ingredient = i < ingredients.size() ? (Ingredient)ingredients.get(i) : Ingredient.EMPTY;
            this.renderIngredientSlot(graphics, font, ingredient, slotX, slotY, mouseX, mouseY);
        }
        this.renderArrow(graphics, font, x + 60, y + 18);
        ItemStack result = recipe.getResultItem((HolderLookup.Provider)Minecraft.getInstance().level.registryAccess());
        this.renderSlot(graphics, font, x + 90, y + 18, result, mouseX, mouseY);
    }

    private void renderCookingRecipe(GuiGraphics graphics, Font font, AbstractCookingRecipe recipe, int x, int y, int mouseX, int mouseY) {
        Ingredient ingredient = recipe.getIngredients().isEmpty() ? Ingredient.EMPTY : (Ingredient)recipe.getIngredients().get(0);
        ItemStack[] stacks = ingredient.getItems();
        if (stacks.length > 0) {
            ItemStack displayStack = stacks[(int)(System.currentTimeMillis() / 1000L % (long)stacks.length)];
            this.renderSlot(graphics, font, x + 10, y + 18, displayStack, mouseX, mouseY);
        } else {
            this.renderEmptySlot(graphics, font, x + 10, y + 18);
        }
        this.renderArrow(graphics, font, x + 40, y + 18);
        ItemStack result = recipe.getResultItem((HolderLookup.Provider)Minecraft.getInstance().level.registryAccess());
        this.renderSlot(graphics, font, x + 80, y + 18, result, mouseX, mouseY);
    }

    private void renderStonecutterRecipe(GuiGraphics graphics, Font font, StonecutterRecipe recipe, int x, int y, int mouseX, int mouseY) {
        Ingredient ingredient = recipe.getIngredients().isEmpty() ? Ingredient.EMPTY : (Ingredient)recipe.getIngredients().get(0);
        ItemStack[] stacks = ingredient.getItems();
        if (stacks.length > 0) {
            ItemStack displayStack = stacks[(int)(System.currentTimeMillis() / 1000L % (long)stacks.length)];
            this.renderSlot(graphics, font, x + 20, y + 18, displayStack, mouseX, mouseY);
        } else {
            this.renderEmptySlot(graphics, font, x + 20, y + 18);
        }
        this.renderArrow(graphics, font, x + 50, y + 18);
        ItemStack result = recipe.getResultItem((HolderLookup.Provider)Minecraft.getInstance().level.registryAccess());
        this.renderSlot(graphics, font, x + 80, y + 18, result, mouseX, mouseY);
    }

    private void renderSmithingRecipe(GuiGraphics graphics, Font font, SmithingRecipe recipe, int x, int y, int mouseX, int mouseY) {
        Ingredient templateIngredient = Ingredient.EMPTY;
        Ingredient baseIngredient = Ingredient.EMPTY;
        Ingredient additionIngredient = Ingredient.EMPTY;
        List<Ingredient> ingredients = RecipeView.extractSmithingIngredients(recipe);
        if (ingredients.size() >= 3) {
            templateIngredient = ingredients.get(0);
            baseIngredient = ingredients.get(1);
            additionIngredient = ingredients.get(2);
        } else if (ingredients.size() == 2) {
            baseIngredient = ingredients.get(0);
            additionIngredient = ingredients.get(1);
        }
        this.renderIngredientSlot(graphics, font, templateIngredient, x + 5, y + 5, mouseX, mouseY);
        this.renderIngredientSlot(graphics, font, baseIngredient, x + 5, y + 25, mouseX, mouseY);
        this.renderIngredientSlot(graphics, font, additionIngredient, x + 25, y + 25, mouseX, mouseY);
        this.renderArrow(graphics, font, x + 50, y + 18);
        ItemStack result = recipe.getResultItem((HolderLookup.Provider)Minecraft.getInstance().level.registryAccess());
        this.renderSlot(graphics, font, x + 80, y + 18, result, mouseX, mouseY);
    }

    private static List<Ingredient> extractSmithingIngredients(SmithingRecipe recipe) {
        ArrayList<Ingredient> ingredients = new ArrayList<Ingredient>();
        if (recipe == null) {
            return ingredients;
        }
        for (Class current = recipe.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType() != Ingredient.class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(recipe);
                    if (!(value instanceof Ingredient)) continue;
                    ingredients.add((Ingredient)value);
                }
                catch (Exception e) {
                    // skip inaccessible fields
                }
            }
        }
        return ingredients;
    }

    private void renderIngredientSlot(GuiGraphics graphics, Font font, Ingredient ingredient, int x, int y, int mouseX, int mouseY) {
        ItemStack[] stacks;
        if (!ingredient.isEmpty() && (stacks = ingredient.getItems()).length > 0) {
            ItemStack displayStack = stacks[(int)(System.currentTimeMillis() / 1000L % (long)stacks.length)];
            this.renderSlot(graphics, font, x, y, displayStack, mouseX, mouseY);
            return;
        }
        this.renderEmptySlot(graphics, font, x, y);
    }

    private void renderSlot(GuiGraphics graphics, Font font, int x, int y, ItemStack stack, int mouseX, int mouseY) {
        boolean isHovered = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
        if (isHovered) {
            graphics.fill(x, y, x + 16, y + 16, -2130706433);
        }
        graphics.fill(x - 1, y - 1, x + 17, y, -11184811);
        graphics.fill(x - 1, y - 1, x, y + 17, -11184811);
        graphics.fill(x + 16, y, x + 17, y + 17, -1);
        graphics.fill(x, y + 16, x + 17, y + 17, -1);
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, x, y);
            graphics.renderItemDecorations(font, stack, x, y);
            if (isHovered) {
                this.hoveredStack = stack;
            }
        }
    }

    private void renderEmptySlot(GuiGraphics graphics, Font font, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y, -11184811);
        graphics.fill(x - 1, y - 1, x, y + 17, -11184811);
        graphics.fill(x + 16, y, x + 17, y + 17, -1);
        graphics.fill(x, y + 16, x + 17, y + 17, -1);
        graphics.fill(x, y, x + 16, y + 16, -7631989);
    }

    private void renderArrow(GuiGraphics graphics, Font font, int x, int y) {
        // JEI 风格右箭头:横杆 + 三角头,尖端朝右(从原料指向产物)
        int color = 0xFF9A9A9A;
        graphics.fill(x, y + 6, x + 9, y + 8, color);
        graphics.fill(x + 9, y + 2, x + 11, y + 12, color);
        graphics.fill(x + 11, y + 4, x + 13, y + 10, color);
        graphics.fill(x + 13, y + 6, x + 15, y + 8, color);
    }

    public void previousRecipe() {
        if (this.currentRecipeIndex > 0) {
            --this.currentRecipeIndex;
            this.updateControls();
        }
    }

    public void nextRecipe() {
        if (this.currentRecipeIndex < this.recipes.size() - 1) {
            ++this.currentRecipeIndex;
            this.updateControls();
        }
    }

    /** 鼠标滚轮在配方面板区域滚动时切换上一个/下一个配方。 */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!this.hasRecipes()) {
            return false;
        }
        if (mouseX >= (double)this.panelX && mouseX < (double)(this.panelX + 162) && mouseY >= (double)this.panelY && mouseY < (double)(this.panelY + 108)) {
            if (scrollY < 0.0) {
                this.nextRecipe();
            } else if (scrollY > 0.0) {
                this.previousRecipe();
            }
            return true;
        }
        return false;
    }

    private void onTrySynthesis() {
        try {
            if (!this.hasRecipes() || this.recipes.get(this.currentRecipeIndex).getType() != RecipeType.CRAFTING) {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(Component.literal("一键合成仅支持工作台合成;熔炉/高炉/锻造等配方只能查看"), true);
                }
                return;
            }
            int count = Integer.parseInt(this.synthesisCountField.getValue());
            if (count <= 0 || this.targetItem.isEmpty() || this.storagePos == null) {
                return;
            }
            TrySynthesisPacket packet = new TrySynthesisPacket(this.targetItem, this.storagePos, count);
            NetworkManager.sendToServer(packet);
        }
        catch (NumberFormatException e) {
            // ignore invalid input
        }
    }
}
