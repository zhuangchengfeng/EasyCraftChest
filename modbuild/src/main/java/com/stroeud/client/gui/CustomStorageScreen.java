package com.stroeud.client.gui;

import com.mojang.logging.LogUtils;
import com.stroeud.client.ModKeyBindings;
import com.stroeud.container.CustomStorageContainer;
import com.stroeud.network.NetworkManager;
import com.stroeud.network.StorageNetworkHandler;
import com.stroeud.network.packet.SynthesisResultPacket;
import com.stroeud.storage.CustomStorageData;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import org.slf4j.Logger;

public class CustomStorageScreen
extends AbstractContainerScreen<CustomStorageContainer> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int GUI_WIDTH = 408;
    private static final int GUI_HEIGHT = 272;
    private static final int SLOT_SIZE = 18;
    private static final int BACKGROUND_COLOR = -6250336;
    private static final int BORDER_COLOR = -13158601;
    private static final int SLOT_COLOR = -8749702;
    private static final int SLOT_BORDER_COLOR = -11184811;
    private static final int EMPTY_SLOT_COLOR = -8749702;
    private static final int TITLE_COLOR = 0x404040;
    private static final float ITEM_COUNT_TEXT_SCALE = 0.5f;
    private static final int ITEM_COUNT_TEXT_COLOR = 0xFFFFFF;

    // 搜索框(背包栏上方,左移)
    private static final int SEARCH_BOX_X = 8;
    private static final int SEARCH_BOX_Y = 152;
    private static final int SEARCH_BOX_WIDTH = 110;
    private static final int CLEAR_BUTTON_X = SEARCH_BOX_X + SEARCH_BOX_WIDTH + 4;
    private static final int CLEAR_BUTTON_Y = SEARCH_BOX_Y - 1;
    // 右下角内嵌合成视图面板
    private static final int RECIPE_PANEL_X = 184;
    private static final int RECIPE_PANEL_Y = 152;
    private static final int RECIPE_PANEL_WIDTH = 162;
    private static final int RECIPE_PANEL_HEIGHT = 108;

    // 左面板:仓库 9x6
    private static final int STORAGE_GRID_START_X = 8;
    private static final int STORAGE_GRID_START_Y = 36;
    private static final int STORAGE_COLS = 9;
    private static final int STORAGE_ROWS = 6;
    private static final int STORAGE_ITEMS_PER_PAGE = 54;
    private static final int STORAGE_PREV_PAGE_X = 55;
    private static final int STORAGE_NEXT_PAGE_X = 103;
    private static final int STORAGE_PAGE_INFO_X = 79;
    private static final int STORAGE_PAGE_INFO_Y = 22;
    // 玩家背包(左下)
    private static final int PLAYER_INV_START_X = 8;
    private static final int PLAYER_INV_START_Y = 176;
    private static final int PLAYER_HOTBAR_START_Y = 234;
    // 右面板:合成目录 9x6(与左侧一致)
    private static final int CATALOG_PANEL_X = 176;
    private static final int CATALOG_GRID_START_X = 184;
    private static final int CATALOG_GRID_START_Y = 36;
    private static final int CATALOG_COLS = 9;
    private static final int CATALOG_ROWS = 6;
    private static final int CATALOG_ITEMS_PER_PAGE = 54;
    private static final int CATALOG_PREV_PAGE_X = 231;
    private static final int CATALOG_NEXT_PAGE_X = 279;
    private static final int CATALOG_PAGE_INFO_X = 265;
    private static final int CATALOG_PAGE_INFO_Y = 22;

    private int leftPos;
    private int topPos;
    private Map<String, Long> storageData = new HashMap<String, Long>();
    private List<Map.Entry<String, Long>> cachedFilteredStorage = null;
    private int currentPage = 0;
    private int maxPage = 0;
    private String searchFilter = "";
    private long totalItems = 0L;
    private int totalTypes = 0;
    private EditBox searchBox;
    private Button clearSearchButton;
    // 右侧合成目录
    private List<ItemStack> catalogAllItems = new ArrayList<ItemStack>();
    private List<ItemStack> catalogFilteredItems = new ArrayList<ItemStack>();
    private int catalogPage = 0;
    private int catalogMaxPage = 0;
    private long lastClientUpdateTime = 0L;
    private String storageSizeText = "";
    private static final Map<BlockPos, StoredState> STATE_CACHE = new HashMap<BlockPos, StoredState>();
    private static CustomStorageScreen currentInstance = null;

    public static CustomStorageScreen getCurrentInstance() {
        return currentInstance;
    }
    private final BlockPos blockPos;
    private final RecipeView recipeView;
    private int syncTicker = 0;
    private final Map<String, ItemStack> itemStackCache = new HashMap<String, ItemStack>();
    private int lastMouseX = -1;
    private int lastMouseY = -1;
    private boolean tooltipCacheValid = false;
    private List<Component> cachedTooltip = null;

    public CustomStorageScreen(CustomStorageContainer container, Inventory playerInventory, Component title) {
        super(container, playerInventory, title);
        this.imageWidth = 354;
        this.imageHeight = 260;
        this.blockPos = container.getBlockPos();
        this.recipeView = new RecipeView(this.blockPos);
        currentInstance = this;
    }

    protected void init() {
        super.init();
        this.leftPos = (this.width - 354) / 2;
        this.topPos = (this.height - 260) / 2;
        StoredState saved = STATE_CACHE.get(this.blockPos);
        if (saved != null) {
            this.searchFilter = saved.searchFilter;
            this.currentPage = saved.storagePage;
            this.catalogPage = saved.catalogPage;
        }
        if (this.catalogAllItems.isEmpty()) {
            this.catalogAllItems = ItemCatalog.buildAllItems();
        }
        this.applyCatalogFilter();
        this.searchBox = new EditBox(this.font, this.leftPos + 8, this.topPos + 152, 110, 14, Component.literal("Search"));
        this.searchBox.setMaxLength(50);
        this.searchBox.setResponder(this::onSearchChanged);
        this.searchBox.setValue(this.searchFilter);
        this.addRenderableWidget(this.searchBox);
        this.clearSearchButton = Button.builder(Component.literal("X"), button -> this.clearSearch()).bounds(this.leftPos + 122, this.topPos + 151, 16, 16).build();
        this.addRenderableWidget(this.clearSearchButton);
        this.recipeView.createControls(this.font, this.leftPos + 184, this.topPos + 152);
        if (saved != null) {
            if (!saved.lastTargetItemId.isEmpty()) {
                Item restoredItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(saved.lastTargetItemId));
                if (restoredItem != null && restoredItem != Items.AIR) {
                    this.recipeView.setTarget(new ItemStack(restoredItem));
                }
            }
            this.recipeView.setSynthesisCount(saved.synthesisCount);
            this.recipeView.restoreHistory(saved.historyItemIds);
        }
        this.recipeView.getSynthesisCountField().setResponder(text -> this.saveState());
        this.addRenderableWidget(this.recipeView.getTrySynthesisButton());
        this.addRenderableWidget(this.recipeView.getSynthesisCountField());
        this.updatePageButtons();
        if (this.minecraft != null) {
            this.minecraft.execute(() -> this.requestStorageData());
        }
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        ++this.syncTicker;
        if (this.syncTicker >= 5) {
            this.syncTicker = 0;
            if (this.syncTicker == 0 && System.currentTimeMillis() - this.lastClientUpdateTime > 1000L) {
                this.requestStorageData();
                this.lastClientUpdateTime = System.currentTimeMillis();
            }
        }
        this.renderItemCounts(graphics, mouseX, mouseY);
        this.renderTooltips(graphics, mouseX, mouseY);
        this.renderCatalogTooltips(graphics, mouseX, mouseY);
        this.recipeView.render(graphics, this.font, this.leftPos + 184, this.topPos + 152, mouseX, mouseY);
    }

    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(this.leftPos, this.topPos, this.leftPos + 354, this.topPos + 260, -6250336);
        this.drawBorder(graphics, this.leftPos, this.topPos, 354, 260, -13158601);
        // 右下角合成面板:独立子区域,亮色底 + 边框,四周留出可见边距(不贴到主界面边缘)
        graphics.fill(this.leftPos + 184, this.topPos + 152, this.leftPos + 342, this.topPos + 256, -6710887);
        this.drawBorder(graphics, this.leftPos + 184, this.topPos + 152, 158, 104, -13158601);
        graphics.drawString(this.font, this.title, this.leftPos + 8, this.topPos + 6, 0x404040, false);
        this.renderStorageGrid(graphics, mouseX, mouseY);
        this.renderCatalogGrid(graphics, mouseX, mouseY);
        this.renderPlayerInventorySlots(graphics);
        this.renderStatistics(graphics);
        this.renderStoragePageInfo(graphics);
        this.renderCatalogPageInfo(graphics);
    }

    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    private void renderStorageGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Map.Entry<String, Long>> pageItems = this.getCurrentPageItems();
        for (int i = 0; i < 54; ++i) {
            int x = this.leftPos + 8 + i % 9 * 18;
            int y = this.topPos + 36 + i / 9 * 18;
            if (i < pageItems.size()) {
                Map.Entry<String, Long> entry = pageItems.get(i);
                this.renderItemSlot(graphics, x, y, entry.getKey(), entry.getValue(), mouseX, mouseY);
                continue;
            }
            this.renderEmptySlot(graphics, x, y, mouseX, mouseY);
        }
    }

    private void renderCatalogGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        int startIndex = this.catalogPage * 54;
        int endIndex = Math.min(startIndex + 54, this.catalogFilteredItems.size());
        for (int i = startIndex; i < endIndex; ++i) {
            int gridIndex = i - startIndex;
            int x = this.leftPos + 184 + gridIndex % 9 * 18;
            int y = this.topPos + 36 + gridIndex / 9 * 18;
            this.renderCatalogSlot(graphics, x, y, this.catalogFilteredItems.get(i), mouseX, mouseY);
        }
        for (int i = endIndex - startIndex; i < 54; ++i) {
            int x = this.leftPos + 184 + i % 9 * 18;
            int y = this.topPos + 36 + i / 9 * 18;
            this.renderCatalogEmptySlot(graphics, x, y);
        }
    }

    private void renderItemSlot(GuiGraphics graphics, int x, int y, String itemKey, long count, int mouseX, int mouseY) {
        ItemStack stack;
        graphics.fill(x, y, x + 16, y + 16, -8749702);
        this.drawBorder(graphics, x, y, 16, 16, -11184811);
        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
            graphics.fill(x + 1, y + 1, x + 15, y + 15, -2130706433);
        }
        if (!(stack = this.getCachedItemStack(itemKey)).isEmpty()) {
            graphics.renderItem(stack, x, y);
            this.renderDurabilityBar(graphics, stack, x, y);
        }
    }

    private void renderEmptySlot(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        boolean isHovered;
        graphics.fill(x, y, x + 16, y + 16, -8749702);
        this.drawBorder(graphics, x, y, 16, 16, -11184811);
        boolean bl = isHovered = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
        if (isHovered) {
            ItemStack carriedItem = ((CustomStorageContainer)this.menu).getCarried();
            if (!carriedItem.isEmpty()) {
                graphics.fill(x + 1, y + 1, x + 15, y + 15, -2147418368);
            } else {
                graphics.fill(x + 1, y + 1, x + 15, y + 15, -2130706433);
            }
        }
    }

    private void renderCatalogSlot(GuiGraphics graphics, int x, int y, ItemStack stack, int mouseX, int mouseY) {
        // 置顶物品用亮蓝色边框,合成过的用金色边框,其余默认
        boolean pinned = !stack.isEmpty() && SynthesisStats.isPinned(stack.getItem());
        boolean synthesized = !pinned && !stack.isEmpty() && SynthesisStats.getCount(stack.getItem()) > 0;
        graphics.fill(x, y, x + 16, y + 16, -8749702);
        this.drawBorder(graphics, x, y, 16, 16, pinned ? -16722433 : (synthesized ? -2643968 : -11184811));
        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
            graphics.fill(x + 1, y + 1, x + 15, y + 15, -2130706433);
        }
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, x, y);
            graphics.renderItemDecorations(this.font, stack, x, y);
            this.renderDurabilityBar(graphics, stack, x, y);
        }
    }

    private void renderCatalogEmptySlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 16, y + 16, -8749702);
        this.drawBorder(graphics, x, y, 16, 16, -11184811);
    }

    private void renderPlayerInventorySlots(GuiGraphics graphics) {
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                int x = this.leftPos + 8 + col * 18;
                int y = this.topPos + 176 + row * 18;
                this.renderSlotBackground(graphics, x, y);
            }
        }
        for (int col = 0; col < 9; ++col) {
            int x = this.leftPos + 8 + col * 18;
            int y = this.topPos + 234;
            this.renderSlotBackground(graphics, x, y);
        }
    }

    private void renderSlotBackground(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 16, y + 16, -8749702);
        this.drawBorder(graphics, x, y, 16, 16, -11184811);
    }

    private void renderStatistics(GuiGraphics graphics) {
        Component stats = Component.translatable("gui.storageandoneclicksynthesis.stats", this.totalTypes, this.formatCount(this.totalItems));
        int x = this.leftPos + 8 + this.font.width(this.title) + 8;
        graphics.drawString(this.font, stats, x, this.topPos + 6, 0x404040, false);
        // if (!this.storageSizeText.isEmpty()) { // 字节大小功能暂不使用,已注释
        //     String sizeText = " | 约 " + this.storageSizeText;
        //     graphics.drawString(this.font, sizeText, x + this.font.width(stats) + 4, this.topPos + 6, 0x404040, false);
        // }
    }

    private void renderStoragePageInfo(GuiGraphics graphics) {
        String pageInfo = String.format("%d/%d", this.currentPage + 1, this.maxPage + 1);
        graphics.pose().pushPose();
        graphics.pose().scale(0.75f, 0.75f, 1.0f);
        int centerX = (int)((this.leftPos + 89) / 0.75f) - this.font.width(pageInfo) / 2;
        graphics.drawString(this.font, pageInfo, centerX, (int)((this.topPos + 30) / 0.75f), 0x404040, false);
        graphics.pose().popPose();
    }

    private void renderCatalogPageInfo(GuiGraphics graphics) {
        String pageInfo = String.format("%d/%d", this.catalogPage + 1, this.catalogMaxPage + 1);
        graphics.pose().pushPose();
        graphics.pose().scale(0.75f, 0.75f, 1.0f);
        int centerX = (int)((this.leftPos + 265) / 0.75f) - this.font.width(pageInfo) / 2;
        graphics.drawString(this.font, pageInfo, centerX, (int)((this.topPos + 30) / 0.75f), 0x404040, false);
        graphics.pose().popPose();
    }

    private void renderItemCounts(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Map.Entry<String, Long>> pageItems = this.getCurrentPageItems();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0f, 0.0f, 200.0f);
        graphics.pose().scale(0.5f, 0.5f, 1.0f);
        for (int i = 0; i < 54 && i < pageItems.size(); ++i) {
            float scaledX = (float)(this.leftPos + 8 + i % 9 * 18) / 0.5f;
            float scaledY = (float)(this.topPos + 36 + i / 9 * 18) / 0.5f;
            Map.Entry<String, Long> entry = pageItems.get(i);
            long count = entry.getValue();
            String countText = this.formatCount(count);
            if (countText.equals("1")) continue;
            int textWidth = this.font.width(countText);
            float textX = scaledX + 36.0f - ((float)textWidth + 4.0f);
            Objects.requireNonNull(this.font);
            float textY = scaledY + 36.0f - (9.0f + 2.0f);
            graphics.drawString(this.font, countText, (int)textX, (int)textY, 0xFFFFFF, true);
        }
        graphics.pose().popPose();
    }

    private void renderTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        int storageSlot;
        boolean mousePositionChanged;
        boolean bl = mousePositionChanged = mouseX != this.lastMouseX || mouseY != this.lastMouseY;
        if (mousePositionChanged) {
            this.lastMouseX = mouseX;
            this.lastMouseY = mouseY;
            this.tooltipCacheValid = false;
        }
        if ((storageSlot = this.getStorageSlotAt(mouseX, mouseY)) >= 0) {
            List<Map.Entry<String, Long>> pageItems = this.getCurrentPageItems();
            if (storageSlot < pageItems.size()) {
                ItemStack stack;
                Map.Entry<String, Long> entry = pageItems.get(storageSlot);
                if (!(this.tooltipCacheValid && this.cachedTooltip != null || (stack = this.getCachedItemStack(entry.getKey())).isEmpty())) {
                    graphics.renderTooltip(this.font, stack, mouseX, mouseY);
                    return;
                }
                if (this.cachedTooltip != null) {
                    graphics.renderComponentTooltip(this.font, this.cachedTooltip, mouseX, mouseY);
                }
            }
        } else {
            Slot slot = this.getSlotUnderMouse();
            if (slot != null && slot.hasItem()) {
                graphics.renderTooltip(this.font, slot.getItem(), mouseX, mouseY);
            } else {
                this.tooltipCacheValid = false;
                this.cachedTooltip = null;
            }
        }
    }

    private void renderCatalogTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        int catalogIndex = this.getCatalogSlotAt(mouseX, mouseY);
        if (catalogIndex >= 0 && catalogIndex < this.catalogFilteredItems.size()) {
            ItemStack stack = this.catalogFilteredItems.get(catalogIndex);
            if (!stack.isEmpty()) {
                graphics.renderTooltip(this.font, stack, mouseX, mouseY);
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean textFocused = this.searchBox != null && this.searchBox.isFocused()
                || this.recipeView.getSynthesisCountField() != null && this.recipeView.getSynthesisCountField().isFocused();
        if (textFocused && this.minecraft != null && this.minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            return true;
        }
        if (!textFocused && ModKeyBindings.PIN_ITEM.matches(keyCode, scanCode)) {
            this.togglePinAtMouse();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 悬停在任意物品上按置顶键:置顶/取消置顶该物品(目录、存储、九宫格原料、结果、历史均可)。 */
    private void togglePinAtMouse() {
        ItemStack item = this.getItemUnderMouseForPin();
        if (!item.isEmpty()) {
            SynthesisStats.togglePin(item.getItem());
            this.applyCatalogFilter();
            this.updatePageButtons();
        }
    }

    /** 按优先级取鼠标下方可置顶的物品:目录 → 历史 → 九宫格原料 → 结果栏 → 存储栏。 */
    private ItemStack getItemUnderMouseForPin() {
        int mouseX = this.lastMouseX;
        int mouseY = this.lastMouseY;
        // 右上合成目录
        int catalogIndex = this.getCatalogSlotAt(mouseX, mouseY);
        if (catalogIndex >= 0 && catalogIndex < this.catalogFilteredItems.size()) {
            return this.catalogFilteredItems.get(catalogIndex);
        }
        int px = this.leftPos + 184;
        int py = this.topPos + 152;
        // 右下配方面板-历史栏
        int historyIndex = this.recipeView.getHistorySlotAt(mouseX, mouseY, px, py);
        if (historyIndex >= 0) {
            ItemStack hist = this.recipeView.getHistoryItem(historyIndex);
            if (!hist.isEmpty()) {
                return hist;
            }
        }
        // 右下配方面板-九宫格原料
        int ingredientSlot = this.recipeView.getIngredientSlotAt(mouseX, mouseY, px, py);
        if (ingredientSlot >= 0) {
            ItemStack ing = this.recipeView.getIngredientItem(ingredientSlot);
            if (!ing.isEmpty()) {
                return ing;
            }
        }
        // 右下配方面板-结果栏
        int resultSlot = this.recipeView.getResultSlotIndex(mouseX, mouseY, px, py);
        if (resultSlot >= 0) {
            ItemStack result = this.recipeView.getResultItem();
            if (!result.isEmpty()) {
                return result;
            }
        }
        // 左上存储栏
        int storageSlot = this.getStorageSlotAt(mouseX, mouseY);
        if (storageSlot >= 0) {
            List<Map.Entry<String, Long>> pageItems = this.getCurrentPageItems();
            if (storageSlot < pageItems.size()) {
                return this.getCachedItemStack(pageItems.get(storageSlot).getKey());
            }
        }
        return ItemStack.EMPTY;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 点击输入框以外区域时,取消输入框聚焦(否则后续按键会被输入框吞掉,A 键置顶失效)
        boolean onSearchBox = this.searchBox != null && this.searchBox.isMouseOver(mouseX, mouseY);
        boolean onCountField = this.recipeView.getSynthesisCountField() != null && this.recipeView.getSynthesisCountField().isMouseOver(mouseX, mouseY);
        if (!onSearchBox && !onCountField) {
            if (this.searchBox != null) {
                this.searchBox.setFocused(false);
            }
            if (this.recipeView.getSynthesisCountField() != null) {
                this.recipeView.getSynthesisCountField().setFocused(false);
            }
        }
        if (this.searchBox.isMouseOver(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int catalogIndex = this.getCatalogSlotAt((int)mouseX, (int)mouseY);
        if (catalogIndex >= 0) {
            if (button == 0) {
                this.recipeView.setTarget(this.catalogFilteredItems.get(catalogIndex));
                this.saveState();
            } else if (button == 1) {
                // 右键快捷合成:按输入框次数直接合成
                this.recipeView.setTarget(this.catalogFilteredItems.get(catalogIndex));
                this.recipeView.quickSynthesize();
                this.saveState();
            }
            return true;
        }
        int historyIndex = this.recipeView.getHistorySlotAt(mouseX, mouseY, this.leftPos + 184, this.topPos + 152);
        if (historyIndex >= 0) {
            if (button == 0) {
                ItemStack hist = this.recipeView.getHistoryItem(historyIndex);
                if (!hist.isEmpty()) {
                    this.recipeView.setTarget(hist);
                    this.saveState();
                }
            }
            return true;
        }
        int ingredientSlot = this.recipeView.getIngredientSlotAt(mouseX, mouseY, this.leftPos + 184, this.topPos + 152);
        if (ingredientSlot >= 0) {
            if (button == 0) {
                ItemStack ing = this.recipeView.getIngredientItem(ingredientSlot);
                if (!ing.isEmpty()) {
                    this.recipeView.setTarget(ing);
                    this.saveState();
                }
            }
            return true;
        }
        int storageSlot = this.getStorageSlotAt((int)mouseX, (int)mouseY);
        if (storageSlot >= 0) {
            this.handleStorageClick(storageSlot, button);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        ItemStack stackToMove;
        if (clickType == ClickType.QUICK_MOVE && slot != null && !(stackToMove = slot.getItem()).isEmpty() && slotId >= 0 && slotId <= 35 && CustomStorageScreen.handleQuickMoveToStorage(stackToMove, slotId)) {
            slot.set(ItemStack.EMPTY);
            return;
        }
        super.slotClicked(slot, slotId, mouseButton, clickType);
    }

    private void handleStorageClick(int slotIndex, int button) {
        List<Map.Entry<String, Long>> pageItems = this.getCurrentPageItems();
        if (slotIndex < pageItems.size()) {
            Map.Entry<String, Long> entry = pageItems.get(slotIndex);
            String itemKey = entry.getKey();
            long count = entry.getValue();
            if (button == 0) {
                boolean isShiftPressed = CustomStorageScreen.hasShiftDown();
                this.handleStorageLeftClick(itemKey, count, isShiftPressed);
            } else if (button == 1) {
                this.handleStorageRightClick(itemKey, count);
            }
        } else if (!((CustomStorageContainer)this.menu).getCarried().isEmpty()) {
            this.handleStoragePutItem();
        }
    }

    private void handleStorageLeftClick(String itemKey, long count, boolean isShiftPressed) {
        ItemStack carriedItem = ((CustomStorageContainer)this.menu).getCarried();
        if (carriedItem.isEmpty()) {
            if (isShiftPressed) {
                this.sendItemOperation(StorageNetworkHandler.OperationType.TAKE, itemKey, 0L, true);
            } else {
                ItemStack stack = this.getCachedItemStack(itemKey);
                int maxStackSize = stack.isEmpty() ? 64 : stack.getMaxStackSize();
                long takeAmount = Math.min((long)maxStackSize, count);
                this.sendItemOperation(StorageNetworkHandler.OperationType.TAKE, itemKey, takeAmount, false);
            }
        } else {
            ItemStack carried = carriedItem.copy();
            String carriedKey = CustomStorageData.getItemKey(carried);
            if (carriedKey.equals(itemKey)) {
                ItemStack storageStack = this.getCachedItemStack(itemKey);
                if (ItemStack.isSameItemSameComponents((ItemStack)carried, (ItemStack)storageStack)) {
                    this.sendItemOperation(StorageNetworkHandler.OperationType.PUT, itemKey, carried.getCount(), false);
                    return;
                }
                this.sendItemOperation(StorageNetworkHandler.OperationType.PUT, itemKey, carried.getCount());
            } else {
                this.sendItemOperation(StorageNetworkHandler.OperationType.PUT, itemKey, carried.getCount());
                this.sendItemOperation(StorageNetworkHandler.OperationType.TAKE, itemKey, Math.min(64L, count));
            }
        }
    }

    private void handleStorageRightClick(String itemKey, long count) {
        ItemStack carriedItem = ((CustomStorageContainer)this.menu).getCarried();
        if (carriedItem.isEmpty()) {
            long takeAmount = 1L;
            this.sendItemOperation(StorageNetworkHandler.OperationType.TAKE, itemKey, takeAmount);
        } else {
            ItemStack storageStack;
            int maxStackSize;
            long currentStorageCount;
            ItemStack carried = carriedItem.copy();
            String carriedKey = CustomStorageData.getItemKey(carried);
            if (carriedKey.equals(itemKey) && (currentStorageCount = count) < (long)(maxStackSize = (storageStack = this.getCachedItemStack(itemKey)).getMaxStackSize())) {
                this.sendItemOperation(StorageNetworkHandler.OperationType.PUT, itemKey, 1L);
            }
        }
    }

    private void handleStoragePutItem() {
        ItemStack carriedItem = ((CustomStorageContainer)this.menu).getCarried();
        if (!carriedItem.isEmpty()) {
            String itemKey = CustomStorageData.getItemKey(carriedItem);
            long putAmount = carriedItem.getCount();
            this.sendItemOperation(StorageNetworkHandler.OperationType.PUT, itemKey, putAmount);
        }
    }

    private void sendItemOperation(StorageNetworkHandler.OperationType type, String itemKey, long amount) {
        StorageNetworkHandler.ItemOperationPacket packet = new StorageNetworkHandler.ItemOperationPacket(type, itemKey, amount, this.currentPage, this.searchFilter, ((CustomStorageContainer)this.menu).getCarried());
        NetworkManager.sendToServer(packet);
    }

    private void sendItemOperation(StorageNetworkHandler.OperationType type, String itemKey, long amount, boolean isShiftClick) {
        StorageNetworkHandler.ItemOperationPacket packet = new StorageNetworkHandler.ItemOperationPacket(type, itemKey, amount, this.currentPage, this.searchFilter, ((CustomStorageContainer)this.menu).getCarried(), isShiftClick, -1);
        NetworkManager.sendToServer(packet);
    }

    private void onSearchChanged(String searchTerm) {
        if (!this.searchFilter.equals(searchTerm)) {
            this.searchFilter = searchTerm;
            this.currentPage = 0;
            this.catalogPage = 0;
            this.cachedFilteredStorage = null;
            this.applyStorageFilter();
            this.applyCatalogFilter();
            this.updatePageButtons();
            this.lastClientUpdateTime = System.currentTimeMillis();
            this.saveState();
        }
    }

    private void clearSearch() {
        this.searchBox.setValue("");
        this.onSearchChanged("");
    }

    private void previousPage() {
        if (this.currentPage > 0) {
            --this.currentPage;
            this.updatePageButtons();
            this.saveState();
        }
    }

    private void nextPage() {
        if (this.currentPage < this.maxPage) {
            ++this.currentPage;
            this.updatePageButtons();
            this.saveState();
        }
    }

    private void catalogPreviousPage() {
        if (this.catalogPage > 0) {
            --this.catalogPage;
            this.updatePageButtons();
            this.saveState();
        }
    }

    private void catalogNextPage() {
        if (this.catalogPage < this.catalogMaxPage) {
            ++this.catalogPage;
            this.updatePageButtons();
            this.saveState();
        }
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.recipeView.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (this.isInStorageGrid(mouseX, mouseY)) {
            if (scrollY < 0.0) {
                this.nextPage();
            } else if (scrollY > 0.0) {
                this.previousPage();
            }
            return true;
        }
        if (this.isInCatalogGrid(mouseX, mouseY)) {
            if (scrollY < 0.0) {
                this.catalogNextPage();
            } else if (scrollY > 0.0) {
                this.catalogPreviousPage();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean isInStorageGrid(double mouseX, double mouseY) {
        return mouseX >= (double)(this.leftPos + 8) && mouseX < (double)(this.leftPos + 8 + 9 * 18) && mouseY >= (double)(this.topPos + 36) && mouseY < (double)(this.topPos + 36 + 6 * 18);
    }

    private boolean isInCatalogGrid(double mouseX, double mouseY) {
        return mouseX >= (double)(this.leftPos + 184) && mouseX < (double)(this.leftPos + 184 + 9 * 18) && mouseY >= (double)(this.topPos + 36) && mouseY < (double)(this.topPos + 36 + 6 * 18);
    }

    private void updatePageButtons() {
    }

    private void applyStorageFilter() {
        this.maxPage = Math.max(0, (this.getFilteredStorageEntries().size() - 1) / 54);
        if (this.currentPage > this.maxPage) {
            this.currentPage = this.maxPage;
        }
    }

    private void applyCatalogFilter() {
        this.catalogFilteredItems.clear();
        Set<Item> craftable = ItemCatalog.buildCraftableItems();
        for (ItemStack stack : this.catalogAllItems) {
            if (!craftable.contains(stack.getItem())) continue;
            if (!ItemCatalog.matchesSearchFilter(stack, this.searchFilter)) continue;
            this.catalogFilteredItems.add(stack);
        }
        // 置顶物品在前(按置顶先后顺序),未置顶按名称
        this.catalogFilteredItems.sort((a, b) -> {
            int pa = SynthesisStats.getPinIndex(BuiltInRegistries.ITEM.getKey(a.getItem()).toString());
            int pb = SynthesisStats.getPinIndex(BuiltInRegistries.ITEM.getKey(b.getItem()).toString());
            if (pa >= 0 && pb >= 0) {
                return Integer.compare(pa, pb);
            }
            if (pa >= 0) {
                return -1;
            }
            if (pb >= 0) {
                return 1;
            }
            return a.getHoverName().getString().compareToIgnoreCase(b.getHoverName().getString());
        });
        this.catalogMaxPage = Math.max(0, (this.catalogFilteredItems.size() - 1) / 54);
        if (this.catalogPage > this.catalogMaxPage) {
            this.catalogPage = this.catalogMaxPage;
        }
    }

    private List<Map.Entry<String, Long>> getFilteredStorageEntries() {
        if (this.cachedFilteredStorage != null) {
            return this.cachedFilteredStorage;
        }
        ArrayList<Map.Entry<String, Long>> result = new ArrayList<Map.Entry<String, Long>>();
        for (Map.Entry<String, Long> entry : this.storageData.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0L) continue;
            if (!this.searchFilter.isEmpty()) {
                ItemStack stack = this.getCachedItemStack(entry.getKey());
                String name = stack.getHoverName().getString();
                if (!ItemCatalog.matchesItemName(name, this.searchFilter)) continue;
            }
            result.add(new AbstractMap.SimpleEntry<String, Long>(entry.getKey(), entry.getValue()));
        }
        this.cachedFilteredStorage = result;
        return result;
    }

    private List<Map.Entry<String, Long>> getCurrentPageItems() {
        List<Map.Entry<String, Long>> filtered = this.getFilteredStorageEntries();
        int startIndex = this.currentPage * 54;
        int endIndex = Math.min(startIndex + 54, filtered.size());
        if (startIndex >= filtered.size()) {
            return new ArrayList<Map.Entry<String, Long>>();
        }
        return new ArrayList<Map.Entry<String, Long>>(filtered.subList(startIndex, endIndex));
    }

    private int getStorageSlotAt(int mouseX, int mouseY) {
        int relX = mouseX - this.leftPos - 8;
        int relY = mouseY - this.topPos - 36;
        if (relX >= 0 && relY >= 0) {
            int col = relX / 18;
            int row = relY / 18;
            if (col < 9 && row < 6) {
                return row * 9 + col;
            }
        }
        return -1;
    }

    private int getCatalogSlotAt(int mouseX, int mouseY) {
        int relX = mouseX - this.leftPos - 184;
        int relY = mouseY - this.topPos - 36;
        if (relX >= 0 && relY >= 0) {
            int col = relX / 18;
            int row = relY / 18;
            if (col < 9 && row < 6) {
                int itemIndex = this.catalogPage * 54 + row * 9 + col;
                if (itemIndex < this.catalogFilteredItems.size()) {
                    return itemIndex;
                }
            }
        }
        return -1;
    }

    private ItemStack getCachedItemStack(String key) {
        ItemStack cached = this.itemStackCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            ResourceLocation itemLocation;
            String itemId = key;
            if (key.contains("#")) {
                itemId = key.substring(0, key.indexOf("#"));
            }
            if ((itemLocation = ResourceLocation.tryParse((String)itemId)) != null && BuiltInRegistries.ITEM.containsKey(itemLocation)) {
                return new ItemStack((ItemLike)BuiltInRegistries.ITEM.get(itemLocation));
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return new ItemStack((ItemLike)Items.BARRIER);
    }

    private String formatCount(long count) {
        if (count < 1000L) {
            return String.valueOf(count);
        }
        if (count < 1000000L) {
            return String.format("%.1fK", (double)count / 1000.0);
        }
        if (count < 1000000000L) {
            return String.format("%.1fM", (double)count / 1000000.0);
        }
        return String.format("%.1fB", (double)count / 1.0E9);
    }

    private void renderDurabilityBar(GuiGraphics graphics, ItemStack stack, int x, int y) {
        if (!stack.isEmpty() && stack.isBarVisible()) {
            int barWidth = stack.getBarWidth();
            int barColor = stack.getBarColor();
            int barX = x + 2;
            int barY = y + 13;
            graphics.fill(RenderType.guiOverlay(), barX, barY, barX + 13, barY + 2, -16777216);
            graphics.fill(RenderType.guiOverlay(), barX, barY, barX + barWidth, barY + 1, barColor | 0xFF000000);
        }
    }

    private void drawBorder(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.hLine(x, x + width - 1, y, color);
        graphics.hLine(x, x + width - 1, y + height - 1, color);
        graphics.vLine(x, y, y + height - 1, color);
        graphics.vLine(x + width - 1, y, y + height - 1, color);
    }

    public void updateStorageData(StorageNetworkHandler.StorageDataPacket packet) {
        this.storageData = packet.getItems();
        this.cachedFilteredStorage = null;
        this.totalItems = packet.getTotalItems();
        this.totalTypes = packet.getTotalTypes();
        Map<String, CompoundTag> cachedData = packet.getCachedItemData();
        if (cachedData != null && !cachedData.isEmpty()) {
            this.itemStackCache.clear();
            for (Map.Entry<String, CompoundTag> entry : cachedData.entrySet()) {
                try {
                    Object registryAccess = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.registryAccess() : RegistryAccess.EMPTY;
                    ItemStack restoredStack = ItemStack.parseOptional((HolderLookup.Provider)registryAccess, (CompoundTag)entry.getValue());
                    if (restoredStack.isEmpty()) continue;
                    this.itemStackCache.put(entry.getKey(), restoredStack);
                }
                catch (Exception exception) {}
            }
        }
        // this.estimateAndCacheStorageSize(cachedData); // 字节大小功能暂不使用,已注释
        this.applyStorageFilter();
        this.updatePageButtons();
    }

    /** 本地估算容器数据字节大小(刷新时算一次并缓存,渲染直接读)。特殊 NBT 物品按粗略大小估算。 */
    private void estimateAndCacheStorageSize(Map<String, CompoundTag> cachedData) {
        long bytes = 0L;
        for (Map.Entry<String, Long> e : this.storageData.entrySet()) {
            if (e.getKey() == null) continue;
            bytes += e.getKey().getBytes(StandardCharsets.UTF_8).length + 16L;
        }
        if (cachedData != null) {
            for (Map.Entry<String, CompoundTag> e : cachedData.entrySet()) {
                bytes += (e.getKey() == null ? 0 : e.getKey().length()) * 2 + 200L;
            }
        }
        this.storageSizeText = CustomStorageScreen.formatBytes(bytes);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format("%.1f KB", (double)bytes / 1024.0);
        }
        return String.format("%.1f MB", (double)bytes / (1024.0 * 1024.0));
    }

    public boolean isPauseScreen() {
        return false;
    }

    private void requestStorageData() {
        if (this.blockPos != null) {
            this.sendItemOperation(StorageNetworkHandler.OperationType.SYNC_REQUEST, "", 0L);
        }
    }

    public void updatePlayerInventory(StorageNetworkHandler.PlayerInventoryPacket packet) {
    }

    public void handleDropResponse(boolean success) {
    }

    /** 服务端发来的合成结果:成功则记录历史合成次数并置顶,失败则显示缺失材料。 */
    public void handleSynthesisResult(SynthesisResultPacket packet) {
        if (packet != null) {
            if (packet.success()) {
                ItemStack target = this.recipeView.getTargetItem();
                if (!target.isEmpty()) {
                    SynthesisStats.recordSynthesis(target.getItem());
                    this.applyCatalogFilter();
                    this.updatePageButtons();
                }
            }
            this.recipeView.showResult(packet.success(), packet.message(), packet.missing());
        }
    }

    public static boolean handleQuickMoveToStorage(ItemStack itemStack, int slotId) {
        if (currentInstance != null && !itemStack.isEmpty()) {
            String itemKey = CustomStorageData.getItemKey(itemStack);
            long putAmount = itemStack.getCount();
            StorageNetworkHandler.ItemOperationPacket packet = new StorageNetworkHandler.ItemOperationPacket(StorageNetworkHandler.OperationType.PUT, itemKey, putAmount, CustomStorageScreen.currentInstance.currentPage, CustomStorageScreen.currentInstance.searchFilter, itemStack, true, slotId);
            NetworkManager.sendToServer(packet);
            return true;
        }
        return false;
    }

    public static boolean handleQuickMoveToStorage(ItemStack itemStack) {
        return CustomStorageScreen.handleQuickMoveToStorage(itemStack, -1);
    }

    public void onClose() {
        this.saveState();
        if (this.blockPos != null) {
            this.sendItemOperation(StorageNetworkHandler.OperationType.CLOSE, "", 0L);
        }
        super.onClose();
        currentInstance = null;
    }

    private void saveState() {
        if (this.blockPos == null) {
            return;
        }
        StoredState state = STATE_CACHE.computeIfAbsent(this.blockPos, k -> new StoredState());
        state.searchFilter = this.searchFilter;
        state.storagePage = this.currentPage;
        state.catalogPage = this.catalogPage;
        state.synthesisCount = this.recipeView.getSynthesisCount();
        state.lastTargetItemId = this.recipeView.getTargetItemId();
        state.historyItemIds = this.recipeView.getHistoryIds();
    }

    private static final class StoredState {
        String searchFilter = "";
        int storagePage = 0;
        int catalogPage = 0;
        String synthesisCount = "1";
        String lastTargetItemId = "";
        List<String> historyItemIds = new ArrayList<String>();
    }
}
