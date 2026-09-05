/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.logging.LogUtils
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiGraphics
 *  net.minecraft.client.gui.components.Button
 *  net.minecraft.client.gui.components.EditBox
 *  net.minecraft.client.gui.components.events.GuiEventListener
 *  net.minecraft.client.gui.screens.Screen
 *  net.minecraft.client.multiplayer.ClientLevel
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.HolderLookup$Provider
 *  net.minecraft.core.NonNullList
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.chat.FormattedText
 *  net.minecraft.network.chat.MutableComponent
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.crafting.AbstractCookingRecipe
 *  net.minecraft.world.item.crafting.BlastingRecipe
 *  net.minecraft.world.item.crafting.CampfireCookingRecipe
 *  net.minecraft.world.item.crafting.CraftingRecipe
 *  net.minecraft.world.item.crafting.Ingredient
 *  net.minecraft.world.item.crafting.Recipe
 *  net.minecraft.world.item.crafting.RecipeManager
 *  net.minecraft.world.item.crafting.RecipeType
 *  net.minecraft.world.item.crafting.SmeltingRecipe
 *  net.minecraft.world.item.crafting.SmithingRecipe
 *  net.minecraft.world.item.crafting.SmokingRecipe
 *  net.minecraft.world.item.crafting.StonecutterRecipe
 *  net.minecraft.world.level.Level
 *  org.slf4j.Logger
 */
package com.easycraftchest.client.gui;

import com.mojang.logging.LogUtils;
import com.easycraftchest.network.NetworkManager;
import com.easycraftchest.network.packet.TrySynthesisPacket;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
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
import org.slf4j.Logger;

public class JeiStyleRecipeScreen
extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int GUI_WIDTH = 220;
    private static final int GUI_HEIGHT = 180;
    private static final int BORDER_PADDING = 6;
    private static final int RECIPE_AREA_WIDTH = 116;
    private static final int RECIPE_AREA_HEIGHT = 54;
    private static final int BACKGROUND_COLOR = -301989888;
    private static final int BORDER_COLOR_LIGHT = -1;
    private static final int BORDER_COLOR_DARK = -11184811;
    private static final int RECIPE_BG_COLOR = -13158601;
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final int TITLE_COLOR = 0xFFFFFF;
    private static final int INPUT_BG_COLOR = -13816528;
    private static final int BUTTON_COLOR = -11751600;
    private static final int BUTTON_HOVER_COLOR = -12214199;
    private static final int RECIPE_INFO_OFFSET_X = 0;
    private static final int RECIPE_INFO_OFFSET_Y = -60;
    private final ItemStack targetItem;
    private final BlockPos storagePos;
    private final Screen parentScreen;
    private List<Recipe<?>> recipes;
    private int currentRecipeIndex = 0;
    private Button backButton;
    private Button prevRecipeButton;
    private Button nextRecipeButton;
    private Button categoryButton;
    private EditBox synthesisCountField;
    private Button trySynthesisButton;
    private int leftPos;
    private int topPos;
    private ItemStack hoveredStack = ItemStack.EMPTY;

    public JeiStyleRecipeScreen(ItemStack targetItem, BlockPos storagePos, Screen parentScreen) {
        super((Component)Component.translatable((String)"gui.easycraftchest.recipe_view"));
        this.targetItem = targetItem;
        this.storagePos = storagePos;
        this.parentScreen = parentScreen;
        this.recipes = new ArrayList();
    }

    public JeiStyleRecipeScreen(ItemStack targetItem, Screen parentScreen) {
        this(targetItem, BlockPos.ZERO, parentScreen);
    }

    protected void init() {
        super.init();
        this.findRecipes();
        this.leftPos = (this.width - 220) / 2;
        this.topPos = (this.height - 180) / 2;
        this.backButton = Button.builder((Component)Component.literal((String)"\u2190"), button -> Minecraft.getInstance().setScreen(this.parentScreen)).bounds(this.leftPos + 6, this.topPos + 6, 20, 20).build();
        this.addRenderableWidget(this.backButton);
        this.categoryButton = Button.builder((Component)this.getCurrentRecipeTypeComponent(), button -> this.switchToNextRecipeType()).bounds(this.leftPos + 220 - 6 - 60, this.topPos + 6, 60, 20).build();
        this.addRenderableWidget(this.categoryButton);
        int navButtonY = this.topPos + 180 - 6 - 50;
        this.prevRecipeButton = Button.builder((Component)Component.literal((String)"\u25c0"), button -> {
            if (this.currentRecipeIndex > 0) {
                --this.currentRecipeIndex;
                this.updateRecipeDisplay();
            }
        }).bounds(this.leftPos + 6, navButtonY, 20, 20).build();
        this.addRenderableWidget(this.prevRecipeButton);
        this.nextRecipeButton = Button.builder((Component)Component.literal((String)"\u25b6"), button -> {
            if (this.currentRecipeIndex < this.recipes.size() - 1) {
                ++this.currentRecipeIndex;
                this.updateRecipeDisplay();
            }
        }).bounds(this.leftPos + 220 - 6 - 20, navButtonY, 20, 20).build();
        this.addRenderableWidget(this.nextRecipeButton);
        int inputY = this.topPos + 180 - 6 - 25;
        this.synthesisCountField = new EditBox(this.font, this.leftPos + 6 + 80, inputY, 40, 20, (Component)Component.literal((String)"\u6b21\u6570"));
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
        this.addRenderableWidget(this.synthesisCountField);
        this.trySynthesisButton = Button.builder((Component)Component.literal((String)"\u5c1d\u8bd5\u5408\u6210"), button -> this.onTrySynthesis()).bounds(this.leftPos + 6 + 125, inputY, 80, 20).build();
        this.addRenderableWidget(this.trySynthesisButton);
        this.updateRecipeDisplay();
    }

    private void onTrySynthesis() {
        try {
            if (this.recipes.isEmpty() || this.recipes.get(this.currentRecipeIndex).getType() != RecipeType.CRAFTING) {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage((Component)Component.literal((String)"\u4e00\u952e\u5408\u6210\u4ec5\u652f\u6301\u5de5\u4f5c\u53f0\u5408\u6210\uff1b\u7194\u7089/\u9ad8\u7089/\u953b\u9020\u7b49\u914d\u65b9\u53ea\u80fd\u67e5\u770b"), true);
                }
                return;
            }
            String countText = this.synthesisCountField.getValue();
            int count = Integer.parseInt(countText);
            if (count <= 0) {
                LOGGER.info("\u5408\u6210\u6b21\u6570\u5fc5\u987b\u5927\u4e8e0");
                return;
            }
            if (this.targetItem.isEmpty()) {
                LOGGER.info("\u76ee\u6807\u7269\u54c1\u4e3a\u7a7a");
                return;
            }
            if (this.storagePos == null) {
                LOGGER.info("\u5b58\u50a8\u65b9\u5757\u4f4d\u7f6e\u4e3a\u7a7a");
                return;
            }
            LOGGER.info("\u5c1d\u8bd5\u5408\u6210: " + this.targetItem.getHoverName().getString() + " x" + count);
            LOGGER.info("\u5b58\u50a8\u4f4d\u7f6e: " + String.valueOf(this.storagePos));
            boolean depositToPlayer = CraftChestScreen.getCurrentInstance() != null && CraftChestScreen.getCurrentInstance().shouldDepositToPlayer();
            TrySynthesisPacket packet = new TrySynthesisPacket(this.targetItem, this.storagePos, count, depositToPlayer);
            NetworkManager.sendToServer(packet);
        }
        catch (NumberFormatException e) {
            LOGGER.info("\u65e0\u6548\u7684\u5408\u6210\u6b21\u6570: " + this.synthesisCountField.getValue());
        }
    }

    private void findRecipes() {
        this.recipes.clear();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            LOGGER.warn("Level is null when finding recipes for item: {}", (Object)this.targetItem.getDisplayName().getString());
            return;
        }
        LOGGER.info("Finding recipes for item: {}", (Object)this.targetItem.getDisplayName().getString());
        RecipeManager recipeManager = level.getRecipeManager();
        this.findCraftingRecipes(recipeManager, (Level)level);
        this.findSmeltingRecipes(recipeManager, (Level)level);
        this.findBlastingRecipes(recipeManager, (Level)level);
        this.findSmokingRecipes(recipeManager, (Level)level);
        this.findCampfireCookingRecipes(recipeManager, (Level)level);
        this.findStonecuttingRecipes(recipeManager, (Level)level);
        this.findSmithingRecipes(recipeManager, (Level)level);
        LOGGER.info("Found {} recipes for item: {}", (Object)this.recipes.size(), (Object)this.targetItem.getDisplayName().getString());
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

    private Component getCurrentRecipeTypeComponent() {
        if (this.recipes.isEmpty()) {
            return Component.literal((String)"\u65e0\u914d\u65b9");
        }
        Recipe<?> currentRecipe = this.recipes.get(this.currentRecipeIndex);
        RecipeType type = currentRecipe.getType();
        if (type == RecipeType.CRAFTING) {
            return Component.literal((String)"\u5408\u6210");
        }
        if (type == RecipeType.SMELTING) {
            return Component.literal((String)"\u7194\u70bc");
        }
        if (type == RecipeType.BLASTING) {
            return Component.literal((String)"\u9ad8\u7089");
        }
        if (type == RecipeType.SMOKING) {
            return Component.literal((String)"\u70df\u718f");
        }
        if (type == RecipeType.STONECUTTING) {
            return Component.literal((String)"\u5207\u77f3");
        }
        if (type == RecipeType.SMITHING) {
            return Component.literal((String)"\u953b\u9020");
        }
        return Component.literal((String)"\u5176\u4ed6");
    }

    private void switchToNextRecipeType() {
        int i;
        if (this.recipes.isEmpty()) {
            return;
        }
        RecipeType currentType = this.recipes.get(this.currentRecipeIndex).getType();
        for (i = this.currentRecipeIndex + 1; i < this.recipes.size(); ++i) {
            if (this.recipes.get(i).getType() == currentType) continue;
            this.currentRecipeIndex = i;
            this.updateRecipeDisplay();
            return;
        }
        for (i = 0; i < this.currentRecipeIndex; ++i) {
            if (this.recipes.get(i).getType() == currentType) continue;
            this.currentRecipeIndex = i;
            this.updateRecipeDisplay();
            return;
        }
    }

    private void updateRecipeDisplay() {
        this.prevRecipeButton.active = this.currentRecipeIndex > 0;
        this.nextRecipeButton.active = this.currentRecipeIndex < this.recipes.size() - 1;
        this.categoryButton.setMessage(this.getCurrentRecipeTypeComponent());
        if (this.trySynthesisButton != null) {
            boolean allow;
            this.trySynthesisButton.active = allow = !this.recipes.isEmpty() && this.recipes.get(this.currentRecipeIndex).getType() == RecipeType.CRAFTING;
        }
    }

    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.hoveredStack = ItemStack.EMPTY;
        this.renderJeiStyleBackground(guiGraphics);
        this.renderTitle(guiGraphics);
        this.renderRecipeContent(guiGraphics, mouseX, mouseY);
        this.renderRecipeInfo(guiGraphics);
        this.renderSynthesisLabels(guiGraphics);
        if (this.backButton != null) {
            this.backButton.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        if (this.prevRecipeButton != null) {
            this.prevRecipeButton.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        if (this.nextRecipeButton != null) {
            this.nextRecipeButton.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        if (this.categoryButton != null) {
            this.categoryButton.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        if (this.synthesisCountField != null) {
            this.synthesisCountField.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        if (this.trySynthesisButton != null) {
            this.trySynthesisButton.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        this.renderTooltips(guiGraphics, mouseX, mouseY);
    }

    private void renderSynthesisLabels(GuiGraphics guiGraphics) {
        MutableComponent countLabel = Component.literal((String)"\u5408\u6210\u6b21\u6570:");
        int labelX = this.leftPos + 6;
        int labelY = this.topPos + 180 - 6 - 20;
        guiGraphics.drawString(this.font, (Component)countLabel, labelX, labelY, 0xFFFFFF);
    }

    private void renderJeiStyleBackground(GuiGraphics guiGraphics) {
        int x = this.leftPos;
        int y = this.topPos;
        int width = 220;
        int height = 180;
        guiGraphics.fill(x, y, x + width, y + height, -301989888);
        guiGraphics.fill(x, y, x + width - 1, y + 1, -1);
        guiGraphics.fill(x, y, x + 1, y + height - 1, -1);
        guiGraphics.fill(x + 1, y + height - 1, x + width, y + height, -11184811);
        guiGraphics.fill(x + width - 1, y + 1, x + width, y + height - 1, -11184811);
        int recipeX = x + (width - 116) / 2;
        int recipeY = y + 35;
        guiGraphics.fill(recipeX, recipeY, recipeX + 116, recipeY + 54, -13158601);
    }

    private void renderTitle(GuiGraphics guiGraphics) {
        MutableComponent title = Component.literal((String)(this.targetItem.getHoverName().getString() + " \u7684\u914d\u65b9"));
        int titleX = this.leftPos + (220 - this.font.width((FormattedText)title)) / 2;
        int titleY = this.topPos + 10;
        guiGraphics.drawString(this.font, (Component)title, titleX, titleY, 0xFFFFFF);
    }

    private void renderRecipeContent(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.recipes.isEmpty()) {
            MutableComponent noRecipe = Component.literal((String)"\u6ca1\u6709\u627e\u5230\u914d\u65b9");
            int textX = this.leftPos + (220 - this.font.width((FormattedText)noRecipe)) / 2;
            int textY = this.topPos + 90;
            guiGraphics.drawString(this.font, (Component)noRecipe, textX, textY, 0xFFFFFF);
            return;
        }
        Recipe<?> currentRecipe = this.recipes.get(this.currentRecipeIndex);
        int recipeX = this.leftPos + 52;
        int recipeY = this.topPos + 35;
        if (currentRecipe instanceof CraftingRecipe) {
            CraftingRecipe craftingRecipe = (CraftingRecipe)currentRecipe;
            this.renderCraftingRecipe(guiGraphics, craftingRecipe, recipeX, recipeY, mouseX, mouseY);
        } else if (currentRecipe instanceof AbstractCookingRecipe) {
            AbstractCookingRecipe cookingRecipe = (AbstractCookingRecipe)currentRecipe;
            this.renderCookingRecipe(guiGraphics, cookingRecipe, recipeX, recipeY, mouseX, mouseY);
        } else if (currentRecipe instanceof StonecutterRecipe) {
            StonecutterRecipe stonecutterRecipe = (StonecutterRecipe)currentRecipe;
            this.renderStonecutterRecipe(guiGraphics, stonecutterRecipe, recipeX, recipeY, mouseX, mouseY);
        } else if (currentRecipe instanceof SmithingRecipe) {
            SmithingRecipe smithingRecipe = (SmithingRecipe)currentRecipe;
            this.renderSmithingRecipe(guiGraphics, smithingRecipe, recipeX, recipeY, mouseX, mouseY);
        }
    }

    private void renderCraftingRecipe(GuiGraphics guiGraphics, CraftingRecipe recipe, int x, int y, int mouseX, int mouseY) {
        NonNullList ingredients = recipe.getIngredients();
        int slotSize = 18;
        for (int i = 0; i < 9; ++i) {
            int row = i / 3;
            int col = i % 3;
            int slotX = x + col * slotSize;
            int slotY = y + row * slotSize;
            Ingredient ingredient = i < ingredients.size() ? (Ingredient)ingredients.get(i) : Ingredient.EMPTY;
            this.renderIngredientSlot(guiGraphics, ingredient, slotX, slotY, mouseX, mouseY);
        }
        this.renderArrow(guiGraphics, x + 60, y + 18);
        ItemStack result = recipe.getResultItem((HolderLookup.Provider)Minecraft.getInstance().level.registryAccess());
        this.renderSlot(guiGraphics, x + 90, y + 18, result, mouseX, mouseY);
    }

    private void renderCookingRecipe(GuiGraphics guiGraphics, AbstractCookingRecipe recipe, int x, int y, int mouseX, int mouseY) {
        Ingredient ingredient = recipe.getIngredients().isEmpty() ? Ingredient.EMPTY : (Ingredient)recipe.getIngredients().get(0);
        ItemStack[] stacks = ingredient.getItems();
        if (stacks.length > 0) {
            ItemStack displayStack = stacks[(int)(System.currentTimeMillis() / 1000L % (long)stacks.length)];
            this.renderSlot(guiGraphics, x + 10, y + 18, displayStack, mouseX, mouseY);
        } else {
            this.renderEmptySlot(guiGraphics, x + 10, y + 18);
        }
        this.renderArrow(guiGraphics, x + 40, y + 18);
        ItemStack result = recipe.getResultItem((HolderLookup.Provider)Minecraft.getInstance().level.registryAccess());
        this.renderSlot(guiGraphics, x + 80, y + 18, result, mouseX, mouseY);
        String expText = String.format("\u7ecf\u9a8c: %.1f", Float.valueOf(recipe.getExperience()));
        String timeText = String.format("\u65f6\u95f4: %ds", recipe.getCookingTime() / 20);
        guiGraphics.drawString(this.font, expText, x + 5, y + 45, 0xFFFFFF);
        guiGraphics.drawString(this.font, timeText, x + 60, y + 45, 0xFFFFFF);
    }

    private void renderStonecutterRecipe(GuiGraphics guiGraphics, StonecutterRecipe recipe, int x, int y, int mouseX, int mouseY) {
        Ingredient ingredient = recipe.getIngredients().isEmpty() ? Ingredient.EMPTY : (Ingredient)recipe.getIngredients().get(0);
        ItemStack[] stacks = ingredient.getItems();
        if (stacks.length > 0) {
            ItemStack displayStack = stacks[(int)(System.currentTimeMillis() / 1000L % (long)stacks.length)];
            this.renderSlot(guiGraphics, x + 20, y + 18, displayStack, mouseX, mouseY);
        } else {
            this.renderEmptySlot(guiGraphics, x + 20, y + 18);
        }
        this.renderArrow(guiGraphics, x + 50, y + 18);
        ItemStack result = recipe.getResultItem((HolderLookup.Provider)Minecraft.getInstance().level.registryAccess());
        this.renderSlot(guiGraphics, x + 80, y + 18, result, mouseX, mouseY);
    }

    private void renderSmithingRecipe(GuiGraphics guiGraphics, SmithingRecipe recipe, int x, int y, int mouseX, int mouseY) {
        Ingredient templateIngredient = Ingredient.EMPTY;
        Ingredient baseIngredient = Ingredient.EMPTY;
        Ingredient additionIngredient = Ingredient.EMPTY;
        List<Ingredient> ingredients = JeiStyleRecipeScreen.extractSmithingIngredients(recipe);
        if (ingredients.size() >= 3) {
            templateIngredient = ingredients.get(0);
            baseIngredient = ingredients.get(1);
            additionIngredient = ingredients.get(2);
        } else if (ingredients.size() == 2) {
            baseIngredient = ingredients.get(0);
            additionIngredient = ingredients.get(1);
        }
        this.renderIngredientSlot(guiGraphics, templateIngredient, x + 5, y + 5, mouseX, mouseY);
        this.renderIngredientSlot(guiGraphics, baseIngredient, x + 5, y + 25, mouseX, mouseY);
        this.renderIngredientSlot(guiGraphics, additionIngredient, x + 25, y + 25, mouseX, mouseY);
        this.renderArrow(guiGraphics, x + 50, y + 18);
        ItemStack result = recipe.getResultItem((HolderLookup.Provider)Minecraft.getInstance().level.registryAccess());
        this.renderSlot(guiGraphics, x + 80, y + 18, result, mouseX, mouseY);
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
                    Ingredient ingredient = (Ingredient)value;
                    ingredients.add(ingredient);
                }
                catch (Exception e) {
                    LOGGER.debug("Failed to read smithing ingredient via reflection", (Throwable)e);
                }
            }
        }
        return ingredients;
    }

    private void renderIngredientSlot(GuiGraphics guiGraphics, Ingredient ingredient, int x, int y, int mouseX, int mouseY) {
        ItemStack[] stacks;
        if (!ingredient.isEmpty() && (stacks = ingredient.getItems()).length > 0) {
            ItemStack displayStack = stacks[(int)(System.currentTimeMillis() / 1000L % (long)stacks.length)];
            this.renderSlot(guiGraphics, x, y, displayStack, mouseX, mouseY);
            return;
        }
        this.renderEmptySlot(guiGraphics, x, y);
    }

    private void renderSlot(GuiGraphics guiGraphics, int x, int y, ItemStack stack, int mouseX, int mouseY) {
        boolean isHovered;
        boolean bl = isHovered = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
        if (isHovered) {
            guiGraphics.fill(x, y, x + 16, y + 16, -2130706433);
        }
        guiGraphics.fill(x - 1, y - 1, x + 17, y, -11184811);
        guiGraphics.fill(x - 1, y - 1, x, y + 17, -11184811);
        guiGraphics.fill(x + 16, y, x + 17, y + 17, -1);
        guiGraphics.fill(x, y + 16, x + 17, y + 17, -1);
        if (!stack.isEmpty()) {
            guiGraphics.renderItem(stack, x, y);
            guiGraphics.renderItemDecorations(this.font, stack, x, y);
            if (isHovered) {
                this.hoveredStack = stack;
            }
        }
    }

    private void renderEmptySlot(GuiGraphics guiGraphics, int x, int y) {
        guiGraphics.fill(x - 1, y - 1, x + 17, y, -11184811);
        guiGraphics.fill(x - 1, y - 1, x, y + 17, -11184811);
        guiGraphics.fill(x + 16, y, x + 17, y + 17, -1);
        guiGraphics.fill(x, y + 16, x + 17, y + 17, -1);
        guiGraphics.fill(x, y, x + 16, y + 16, -7631989);
    }

    private void renderArrow(GuiGraphics guiGraphics, int x, int y) {
        guiGraphics.drawString(this.font, "\u2192", x, y, 0xFFFFFF);
    }

    private void renderRecipeInfo(GuiGraphics guiGraphics) {
        if (!this.recipes.isEmpty()) {
            String info = String.format("\u914d\u65b9 %d/%d", this.currentRecipeIndex + 1, this.recipes.size());
            int infoX = this.leftPos + (220 - this.font.width(info)) / 2 + 0;
            int infoY = this.topPos + 180 + -60;
            guiGraphics.drawString(this.font, info, infoX, infoY, 0xFFFFFF);
        }
    }

    private void renderTooltips(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.hoveredStack != null && !this.hoveredStack.isEmpty()) {
            guiGraphics.renderTooltip(this.font, this.hoveredStack, mouseX, mouseY);
        }
    }

    public boolean isPauseScreen() {
        return false;
    }
}

