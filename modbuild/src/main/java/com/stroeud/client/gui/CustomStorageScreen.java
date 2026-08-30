package com.stroeud.client.gui;

import com.mojang.logging.LogUtils;
import com.stroeud.container.CustomStorageContainer;
import com.stroeud.network.NetworkManager;
import com.stroeud.network.StorageNetworkHandler;
import com.stroeud.storage.CustomStorageData;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final int BACKGROUND_COLOR = -7631989;
    private static final int BORDER_COLOR = -13158601;
    private static final int SLOT_COLOR = -10855846;
    private static final int SLOT_BORDER_COLOR = -13750738;
    private static final int EMPTY_SLOT_COLOR = -12566464;
    private static final int TITLE_COLOR = 0x404040;
    private static final float ITEM_COUNT_TEXT_SCALE = 0.5f;
    private static final int ITEM_COUNT_TEXT_COLOR = 0xFFFFFF;

    // 顶部共享搜索框
    private static final int SEARCH_BOX_X = 8;
    private static final int SEARCH_BOX_Y = 20;
    private static final int SEARCH_BOX_WIDTH = 300;
    private static final int CLEAR_BUTTON_X = 312;
    private static final int CLEAR_BUTTON_Y = 19;

    // 左面板:仓库 9x6
    private static final int STORAGE_GRID_START_X = 8;
    private static final int STORAGE_GRID_START_Y = 40;
    private static final int STORAGE_COLS = 9;
    private static final int STORAGE_ROWS = 6;
    private static final int STORAGE_ITEMS_PER_PAGE = 54;
    private static final int STORAGE_PREV_PAGE_X = 100;
    private static final int STORAGE_PREV_PAGE_Y = 156;
    private static final int STORAGE_NEXT_PAGE_X = 164;
    private static final int STORAGE_NEXT_PAGE_Y = 156;
    private static final int STORAGE_PAGE_INFO_CENTER_X = 142;
    private static final int STORAGE_PAGE_INFO_Y = 158;
    // 玩家背包(左下)
    private static final int PLAYER_INV_START_X = 8;
    private static final int PLAYER_INV_START_Y = 180;
    private static final int PLAYER_HOTBAR_START_Y = 238;
    // 右面板:合成目录 12x6
    private static final int CATALOG_PANEL_X = 176;
    private static final int CATALOG_GRID_START_X = 184;
    private static final int CATALOG_GRID_START_Y = 40;
    private static final int CATALOG_COLS = 12;
    private static final int CATALOG_ROWS = 6;
    private static final int CATALOG_ITEMS_PER_PAGE = 72;
    private static final int CATALOG_PREV_PAGE_X = 184;
    private static final int CATALOG_PREV_PAGE_Y = 156;
    private static final int CATALOG_NEXT_PAGE_X = 356;
    private static final int CATALOG_NEXT_PAGE_Y = 156;
    private static final int CATALOG_PAGE_INFO_CENTER_X = 292;
    private static final int CATALOG_PAGE_INFO_Y = 158;

    private int leftPos;
    private int topPos;
    private Map<String, Long> storageData = new HashMap<String, Long>();
    private int currentPage = 0;
    private int maxPage = 0;
    private String searchFilter = "";
    private long totalItems = 0L;
    private int totalTypes = 0;
    private EditBox searchBox;
    private Button prevPageButton;
    private Button nextPageButton;
    private Button clearSearchButton;
    // 右侧合成目录
    private List<ItemStack> catalogAllItems = new ArrayList<ItemStack>();
    private List<ItemStack> catalogFilteredItems = new ArrayList<ItemStack>();
    private int catalogPage = 0;
    private int catalogMaxPage = 0;
    private Button catalogPrevPageButton;
    private Button catalogNextPageButton;
    private long lastClientUpdateTime = 0L;
    private boolean isInitialLoad = true;
    private static CustomStorageScreen currentInstance = null;
    private final BlockPos blockPos;
    private int syncTicker = 0;
    private final Map<String, ItemStack> itemStackCache = new HashMap<String, ItemStack>();
    private int lastMouseX = -1;
    private int lastMouseY = -1;
    private boolean tooltipCacheValid = false;
    private List<Component> cachedTooltip = null;

    public CustomStorageScreen(CustomStorageContainer container, Inventory playerInventory, Component title) {
        super(container, playerInventory, title);
        this.imageWidth = 408;
        this.imageHeight = 272;
        this.blockPos = container.getBlockPos();
        currentInstance = this;
    }

    protected void init() {
        super.init();
        this.leftPos = (this.width - 408) / 2;
        this.topPos = (this.height - 272) / 2;
        if (this.catalogAllItems.isEmpty()) {
            this.catalogAllItems = ItemCatalog.buildAllItems();
        }
        this.applyCatalogFilter();
        this.searchBox = new EditBox(this.font, this.leftPos + 8, this.topPos + 20, 300, 14, Component.literal("Search"));
        this.searchBox.setMaxLength(50);
        this.searchBox.setResponder(this::onSearchChanged);
        this.searchBox.setValue(this.searchFilter);
        this.addRenderableWidget(this.searchBox);
        this.clearSearchButton = Button.builder(Component.literal("X"), button -> this.clearSearch()).bounds(this.leftPos + 312, this.topPos + 19, 16, 16).build();
        this.addRenderableWidget(this.clearSearchButton);
        this.prevPageButton = Button.builder(Component.literal("<"), button -> this.previousPage()).bounds(this.leftPos + 100, this.topPos + 156, 20, 12).build();
        this.addRenderableWidget(this.prevPageButton);
        this.nextPageButton = Button.builder(Component.literal(">"), button -> this.nextPage()).bounds(this.leftPos + 164, this.topPos + 156, 20, 12).build();
        this.addRenderableWidget(this.nextPageButton);
        this.catalogPrevPageButton = Button.builder(Component.literal("<"), button -> this.catalogPreviousPage()).bounds(this.leftPos + 184, this.topPos + 156, 20, 12).build();
        this.addRenderableWidget(this.catalogPrevPageButton);
        this.catalogNextPageButton = Button.builder(Component.literal(">"), button -> this.catalogNextPage()).bounds(this.leftPos + 356, this.topPos + 156, 20, 12).build();
        this.addRenderableWidget(this.catalogNextPageButton);
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
    }

    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(this.leftPos, this.topPos, this.leftPos + 408, this.topPos + 272, -7631989);
        this.drawBorder(graphics, this.leftPos, this.topPos, 408, 272, -13158601);
        graphics.fill(this.leftPos + 176, this.topPos + 6, this.leftPos + 177, this.topPos + 272, -13750738);
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
            int y = this.topPos + 40 + i / 9 * 18;
            if (i < pageItems.size()) {
                Map.Entry<String, Long> entry = pageItems.get(i);
                this.renderItemSlot(graphics, x, y, entry.getKey(), entry.getValue(), mouseX, mouseY);
                continue;
            }
            this.renderEmptySlot(graphics, x, y, mouseX, mouseY);
        }
    }

    private void renderCatalogGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        int startIndex = this.catalogPage * 72;
        int endIndex = Math.min(startIndex + 72, this.catalogFilteredItems.size());
        for (int i = startIndex; i < endIndex; ++i) {
            int gridIndex = i - startIndex;
            int x = this.leftPos + 184 + gridIndex % 12 * 18;
            int y = this.topPos + 40 + gridIndex / 12 * 18;
            this.renderCatalogSlot(graphics, x, y, this.catalogFilteredItems.get(i), mouseX, mouseY);
        }
        for (int i = endIndex - startIndex; i < 72; ++i) {
            int x = this.leftPos + 184 + i % 12 * 18;
            int y = this.topPos + 40 + i / 12 * 18;
            this.renderCatalogEmptySlot(graphics, x, y);
        }
    }

    private void renderItemSlot(GuiGraphics graphics, int x, int y, String itemKey, long count, int mouseX, int mouseY) {
        ItemStack stack;
        graphics.fill(x, y, x + 16, y + 16, -10855846);
        this.drawBorder(graphics, x, y, 16, 16, -13750738);
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
        graphics.fill(x, y, x + 16, y + 16, -12566464);
        this.drawBorder(graphics, x, y, 16, 16, -13750738);
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
        graphics.fill(x, y, x + 16, y + 16, -10855846);
        this.drawBorder(graphics, x, y, 16, 16, -13750738);
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
        graphics.fill(x, y, x + 16, y + 16, -12566464);
        this.drawBorder(graphics, x, y, 16, 16, -13750738);
    }

    private void renderPlayerInventorySlots(GuiGraphics graphics) {
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                int x = this.leftPos + 8 + col * 18;
                int y = this.topPos + 180 + row * 18;
                this.renderSlotBackground(graphics, x, y);
            }
        }
        for (int col = 0; col < 9; ++col) {
            int x = this.leftPos + 8 + col * 18;
            int y = this.topPos + 238;
            this.renderSlotBackground(graphics, x, y);
        }
    }

    private void renderSlotBackground(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 16, y + 16, -10855846);
        this.drawBorder(graphics, x, y, 16, 16, -13750738);
    }

    private void renderStatistics(GuiGraphics graphics) {
        String stats = String.format("Types: %d | Items: %s", this.totalTypes, this.formatCount(this.totalItems));
        graphics.drawString(this.font, stats, this.leftPos + 8, this.topPos + 260, 0x404040, false);
    }

    private void renderStoragePageInfo(GuiGraphics graphics) {
        String pageInfo = String.format("%d / %d", this.currentPage + 1, this.maxPage + 1);
        int pageInfoWidth = this.font.width(pageInfo);
        int centerX = this.leftPos + 142 - pageInfoWidth / 2;
        graphics.drawString(this.font, pageInfo, centerX, this.topPos + 158, 0x404040, false);
    }

    private void renderCatalogPageInfo(GuiGraphics graphics) {
        String pageInfo = String.format("%d / %d", this.catalogPage + 1, this.catalogMaxPage + 1);
        int pageInfoWidth = this.font.width(pageInfo);
        int centerX = this.leftPos + 292 - pageInfoWidth / 2;
        graphics.drawString(this.font, pageInfo, centerX, this.topPos + 158, 0x404040, false);
    }

    private void renderItemCounts(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Map.Entry<String, Long>> pageItems = this.getCurrentPageItems();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0f, 0.0f, 200.0f);
        graphics.pose().scale(0.5f, 0.5f, 1.0f);
        for (int i = 0; i < 54 && i < pageItems.size(); ++i) {
            float scaledX = (float)(this.leftPos + 8 + i % 9 * 18) / 0.5f;
            float scaledY = (float)(this.topPos + 40 + i / 9 * 18) / 0.5f;
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

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.searchBox.isMouseOver(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int catalogIndex = this.getCatalogSlotAt((int)mouseX, (int)mouseY);
        if (catalogIndex >= 0) {
            if (button == 0) {
                this.viewRecipes(this.catalogFilteredItems.get(catalogIndex));
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
            this.applyStorageFilter();
            this.applyCatalogFilter();
            this.updatePageButtons();
            this.lastClientUpdateTime = System.currentTimeMillis();
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
        }
    }

    private void nextPage() {
        if (this.currentPage < this.maxPage) {
            ++this.currentPage;
            this.updatePageButtons();
        }
    }

    private void catalogPreviousPage() {
        if (this.catalogPage > 0) {
            --this.catalogPage;
            this.updatePageButtons();
        }
    }

    private void catalogNextPage() {
        if (this.catalogPage < this.catalogMaxPage) {
            ++this.catalogPage;
            this.updatePageButtons();
        }
    }

    private void updatePageButtons() {
        if (this.prevPageButton != null) {
            this.prevPageButton.active = this.currentPage > 0;
        }
        if (this.nextPageButton != null) {
            this.nextPageButton.active = this.currentPage < this.maxPage;
        }
        if (this.catalogPrevPageButton != null) {
            this.catalogPrevPageButton.active = this.catalogPage > 0;
        }
        if (this.catalogNextPageButton != null) {
            this.catalogNextPageButton.active = this.catalogPage < this.catalogMaxPage;
        }
    }

    private void applyStorageFilter() {
        this.maxPage = Math.max(0, (this.getFilteredStorageEntries().size() - 1) / 54);
        if (this.currentPage > this.maxPage) {
            this.currentPage = this.maxPage;
        }
    }

    private void applyCatalogFilter() {
        this.catalogFilteredItems.clear();
        for (ItemStack stack : this.catalogAllItems) {
            if (!ItemCatalog.matchesSearchFilter(stack, this.searchFilter)) continue;
            this.catalogFilteredItems.add(stack);
        }
        this.catalogMaxPage = Math.max(0, (this.catalogFilteredItems.size() - 1) / 72);
        if (this.catalogPage > this.catalogMaxPage) {
            this.catalogPage = this.catalogMaxPage;
        }
    }

    private List<Map.Entry<String, Long>> getFilteredStorageEntries() {
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
        int relY = mouseY - this.topPos - 40;
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
        int relY = mouseY - this.topPos - 40;
        if (relX >= 0 && relY >= 0) {
            int col = relX / 18;
            int row = relY / 18;
            if (col < 12 && row < 6) {
                int itemIndex = this.catalogPage * 72 + row * 12 + col;
                if (itemIndex < this.catalogFilteredItems.size()) {
                    return itemIndex;
                }
            }
        }
        return -1;
    }

    private void viewRecipes(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return;
        }
        if (this.minecraft != null) {
            try {
                JeiStyleRecipeScreen recipeScreen = new JeiStyleRecipeScreen(item, this.blockPos, this);
                this.minecraft.setScreen(recipeScreen);
            }
            catch (Exception e) {
                LOGGER.error("Failed to open recipe screen for item: {}", item.getDisplayName().getString(), e);
            }
        }
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
        if (this.isInitialLoad) {
            this.searchFilter = packet.getSearchFilter();
        } else if (this.searchBox != null) {
            this.searchFilter = this.searchBox.getValue();
        }
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
        this.applyStorageFilter();
        if (this.searchBox != null && this.isInitialLoad) {
            this.searchBox.setValue(this.searchFilter);
            this.isInitialLoad = false;
        }
        this.updatePageButtons();
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
        if (this.blockPos != null) {
            this.sendItemOperation(StorageNetworkHandler.OperationType.CLOSE, "", 0L);
        }
        super.onClose();
        currentInstance = null;
    }
}
