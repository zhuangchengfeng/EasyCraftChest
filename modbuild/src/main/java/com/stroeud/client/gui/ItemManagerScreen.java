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
 *  net.minecraft.client.multiplayer.ClientPacketListener
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Holder$Reference
 *  net.minecraft.core.HolderLookup$Provider
 *  net.minecraft.core.RegistryAccess
 *  net.minecraft.core.registries.BuiltInRegistries
 *  net.minecraft.network.chat.Component
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.world.entity.player.Player
 *  net.minecraft.world.flag.FeatureFlagSet
 *  net.minecraft.world.item.CreativeModeTab
 *  net.minecraft.world.item.CreativeModeTab$ItemDisplayParameters
 *  net.minecraft.world.item.CreativeModeTab$Type
 *  net.minecraft.world.item.CreativeModeTabs
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.Items
 *  org.slf4j.Logger
 */
package com.stroeud.client.gui;

import com.mojang.logging.LogUtils;
import com.stroeud.client.gui.JeiStyleRecipeScreen;
import com.stroeud.network.NetworkManager;
import com.stroeud.network.StorageNetworkHandler;
import com.stroeud.util.PinyinHelper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

public class ItemManagerScreen
extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int GUI_WIDTH = 270;
    private static final int GUI_HEIGHT = 220;
    private static final int BORDER_PADDING = 6;
    private static final int INNER_PADDING = 2;
    private static final int BUTTON_SIZE = 20;
    private static final int SEARCH_HEIGHT = 20;
    private static final int ITEMS_PER_ROW = 12;
    private static final int ROWS_PER_PAGE = 6;
    private static final int ITEMS_PER_PAGE = 72;
    private static final int BACKGROUND_COLOR = -301989888;
    private static final int BORDER_COLOR_LIGHT = -1;
    private static final int BORDER_COLOR_DARK = -11184811;
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final int SELECTED_BACKGROUND_COLOR = -2130706535;
    private static final int SELECTED_BORDER_COLOR = -10496;
    private static final int HOVER_BACKGROUND_COLOR = -2139029505;
    private static final int HOVER_BORDER_COLOR = -8323073;
    private static final int SELECTED_HOVER_COLOR = -2130706560;
    private static final int SELECTION_GLOW_COLOR = -10496;
    private long lastSelectionTime = 0L;
    private float selectionAnimationProgress = 0.0f;
    private static final long SELECTION_ANIMATION_DURATION = 200L;
    private int leftPos;
    private int topPos;
    private final BlockPos blockPos;
    private List<ItemStack> allItems = new ArrayList<ItemStack>();
    private List<ItemStack> filteredItems = new ArrayList<ItemStack>();
    private int currentPage = 0;
    private int maxPage = 0;
    private String searchFilter = "";
    private ItemStack selectedItem = ItemStack.EMPTY;
    private EditBox searchBox;
    private Button prevPageButton;
    private Button nextPageButton;
    private Button configButton;
    private Button viewRecipeButton;
    private Button backToStorageButton;
    private Button closeButton;
    private static final int GRID_WIDTH = 12;
    private static final int GRID_HEIGHT = 6;
    private static final int SLOT_SIZE = 18;
    private static final int PAGE_INFO_OFFSET_X = 0;
    private static final int PAGE_INFO_OFFSET_Y = -22;

    public ItemManagerScreen(BlockPos blockPos) {
        super((Component)Component.translatable((String)"gui.storageandoneclicksynthesis.item_manager"));
        this.blockPos = blockPos;
    }

    protected void init() {
        super.init();
        this.leftPos = (this.width - 270) / 2;
        this.topPos = (this.height - 220) / 2;
        this.initializeItemList();
        int searchBoxX = this.leftPos + 6 + 20 + 2;
        int searchBoxY = this.topPos + 6;
        int searchBoxWidth = 214;
        this.searchBox = new EditBox(this.font, searchBoxX, searchBoxY, searchBoxWidth, 20, (Component)Component.translatable((String)"gui.storageandoneclicksynthesis.search"));
        this.searchBox.setMaxLength(50);
        this.searchBox.setBordered(true);
        this.searchBox.setResponder(this::onSearchChanged);
        this.addWidget(this.searchBox);
        this.backToStorageButton = Button.builder((Component)Component.literal((String)"\u2190"), button -> {
            if (this.blockPos != null) {
                NetworkManager.sendToServer(new StorageNetworkHandler.OpenStoragePacket(this.blockPos.getX(), this.blockPos.getY(), this.blockPos.getZ()));
            }
        }).bounds(this.leftPos + 6, this.topPos + 6, 20, 20).build();
        this.addRenderableWidget(this.backToStorageButton);
        this.configButton = Button.builder((Component)Component.literal((String)"\u2699"), button -> this.onClose()).bounds(this.leftPos + 270 - 6 - 20, this.topPos + 6, 20, 20).build();
        this.addRenderableWidget(this.configButton);
        int navButtonY = this.topPos + 220 - 6 - 20;
        this.prevPageButton = Button.builder((Component)Component.literal((String)"\u25c0"), button -> {
            if (this.currentPage > 0) {
                --this.currentPage;
                this.updatePageButtons();
            }
        }).bounds(this.leftPos + 6, navButtonY, 20, 20).build();
        this.addRenderableWidget(this.prevPageButton);
        this.nextPageButton = Button.builder((Component)Component.literal((String)"\u25b6"), button -> {
            if (this.currentPage < this.maxPage) {
                ++this.currentPage;
                this.updatePageButtons();
            }
        }).bounds(this.leftPos + 270 - 6 - 20, navButtonY, 20, 20).build();
        this.addRenderableWidget(this.nextPageButton);
        this.viewRecipeButton = Button.builder((Component)Component.translatable((String)"gui.storageandoneclicksynthesis.view_recipe"), button -> {
            LOGGER.info("\u67e5\u770b\u914d\u65b9\u6309\u94ae\u88ab\u70b9\u51fb");
            if (!this.selectedItem.isEmpty()) {
                LOGGER.info("\u9009\u4e2d\u7684\u7269\u54c1: {}", (Object)this.selectedItem.getHoverName().getString());
                this.viewRecipes(this.selectedItem);
            } else {
                LOGGER.warn("\u6ca1\u6709\u9009\u62e9\u7269\u54c1\uff0c\u65e0\u6cd5\u67e5\u770b\u914d\u65b9");
                if (this.minecraft != null && this.minecraft.player != null) {
                    this.minecraft.player.displayClientMessage((Component)Component.translatable((String)"gui.storageandoneclicksynthesis.no_item_selected"), true);
                }
            }
        }).bounds(this.leftPos + 135 - 40, navButtonY, 80, 20).build();
        this.addRenderableWidget(this.viewRecipeButton);
        this.updatePageButtons();
        this.updateFilteredItems();
    }

    private void initializeItemList() {
        boolean hasOperatorItemsTabPermissions;
        this.allItems.clear();
        HashSet<Object> itemUidSet = new HashSet<Object>();
        Minecraft minecraft = Minecraft.getInstance();
        FeatureFlagSet features = Optional.ofNullable(minecraft.player).map(p -> p.connection).map(ClientPacketListener::enabledFeatures).orElse(FeatureFlagSet.of());
        boolean bl = hasOperatorItemsTabPermissions = (Boolean)minecraft.options.operatorItemsTab().get() != false || Optional.of(minecraft).map(m -> m.player).map(Player::canUseGameMasterBlocks).orElse(false) != false;
        if (minecraft.level == null) {
            return;
        }
        RegistryAccess registryAccess = minecraft.level.registryAccess();
        CreativeModeTab.ItemDisplayParameters displayParameters = new CreativeModeTab.ItemDisplayParameters(features, hasOperatorItemsTabPermissions, (HolderLookup.Provider)registryAccess);
        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
            if (tab.getType() != CreativeModeTab.Type.CATEGORY) continue;
            try {
                tab.buildContents(displayParameters);
                Collection displayItems = tab.getDisplayItems();
                Collection searchTabDisplayItems = tab.getSearchTabDisplayItems();
                this.addItemsFromCollection(displayItems, itemUidSet);
                if (displayItems.equals(searchTabDisplayItems)) continue;
                this.addItemsFromCollection(searchTabDisplayItems, itemUidSet);
            }
            catch (Exception exception) {}
        }
        this.addItemsFromRegistries(itemUidSet, features);
        this.allItems.sort((a, b) -> {
            String nameA = a.getHoverName().getString();
            String nameB = b.getHoverName().getString();
            return nameA.compareToIgnoreCase(nameB);
        });
        this.updateFilteredItems();
    }

    private void addItemsFromCollection(Collection<ItemStack> items, Set<Object> itemUidSet) {
        for (ItemStack stack : items) {
            String uid;
            if (stack.isEmpty() || !this.isItemVisible(stack) || (uid = this.getItemUid(stack)) == null || !itemUidSet.add(uid)) continue;
            this.allItems.add(stack.copy());
        }
    }

    private void addItemsFromRegistries(Set<Object> itemUidSet, FeatureFlagSet features) {
        BuiltInRegistries.ITEM.asLookup().filterFeatures(features).listElements().map(ItemStack::new).filter(stack -> !stack.isEmpty() && this.isItemVisible((ItemStack)stack)).forEach(stack -> {
            String uid = this.getItemUid((ItemStack)stack);
            if (uid != null && itemUidSet.add(uid)) {
                this.allItems.add((ItemStack)stack);
            }
        });
        BuiltInRegistries.BLOCK.asLookup().filterFeatures(features).listElements().map(Holder.Reference::value).map(ItemStack::new).filter(stack -> !stack.isEmpty() && this.isItemVisible((ItemStack)stack)).forEach(stack -> {
            String uid = this.getItemUid((ItemStack)stack);
            if (uid != null && itemUidSet.add(uid)) {
                this.allItems.add((ItemStack)stack);
            }
        });
    }

    private String getItemUid(ItemStack stack) {
        try {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString() + (!stack.getComponents().isEmpty() ? stack.getComponents().toString() : "");
        }
        catch (Exception e) {
            return null;
        }
    }

    private boolean isItemVisible(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() == Items.AIR) {
            return false;
        }
        return !this.isLightSourceBlock(stack);
    }

    private boolean isLightSourceBlock(ItemStack stack) {
        return stack.getItem() == Items.LIGHT;
    }

    private void onSearchChanged(String newFilter) {
        this.searchFilter = newFilter.toLowerCase();
        this.currentPage = 0;
        this.updateFilteredItems();
        this.updatePageButtons();
    }

    private void updateFilteredItems() {
        this.filteredItems.clear();
        if (this.searchFilter.isEmpty()) {
            this.filteredItems.addAll(this.allItems);
        } else {
            for (ItemStack stack : this.allItems) {
                if (!this.matchesSearchFilter(stack, this.searchFilter)) continue;
                this.filteredItems.add(stack);
            }
        }
        this.maxPage = Math.max(0, (this.filteredItems.size() - 1) / 72);
        if (this.currentPage > this.maxPage) {
            this.currentPage = this.maxPage;
        }
    }

    /*
     * Enabled aggressive block sorting
     */
    private boolean matchesSearchFilter(ItemStack stack, String filter) {
        String modId;
        String lowerFilter = filter.toLowerCase().trim();
        if (lowerFilter.isEmpty()) return true;
        String itemName = stack.getHoverName().getString().toLowerCase();
        if (itemName.contains(lowerFilter)) {
            return true;
        }
        if (PinyinHelper.toPinyin(itemName).toLowerCase().contains(lowerFilter) || PinyinHelper.getFirstLetters(itemName).toLowerCase().contains(lowerFilter)) {
            return true;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId != null) {
            String registryName = itemId.toString().toLowerCase();
            if (registryName.contains(lowerFilter)) {
                return true;
            }
            String itemPath = itemId.getPath().toLowerCase();
            if (itemPath.contains(lowerFilter)) {
                return true;
            }
        }
        if (itemId != null && (modId = itemId.getNamespace().toLowerCase()).contains(lowerFilter)) {
            return true;
        }
        if (lowerFilter.startsWith("@")) {
            String modFilter = lowerFilter.substring(1);
            if (itemId == null || !itemId.getNamespace().toLowerCase().contains(modFilter)) return false;
            return true;
        } else {
            if (lowerFilter.startsWith("#")) {
                String tagFilter = lowerFilter.substring(1);
                return itemName.contains(tagFilter);
            }
            if (!lowerFilter.startsWith("$")) return false;
            String tabFilter = lowerFilter.substring(1);
            return itemName.contains(tabFilter);
        }
    }

    private void updatePageButtons() {
        if (this.prevPageButton != null) {
            boolean bl = this.prevPageButton.active = this.currentPage > 0;
        }
        if (this.nextPageButton != null) {
            boolean bl = this.nextPageButton.active = this.currentPage < this.maxPage;
        }
        if (this.viewRecipeButton != null) {
            this.viewRecipeButton.active = !this.selectedItem.isEmpty();
        }
    }

    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderJeiStyleBackground(guiGraphics);
        this.renderItemGrid(guiGraphics, mouseX, mouseY);
        this.renderPageInfo(guiGraphics);
        if (this.searchBox != null) {
            this.searchBox.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        if (this.backToStorageButton != null) {
            this.backToStorageButton.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        if (this.prevPageButton != null) {
            this.prevPageButton.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        if (this.nextPageButton != null) {
            this.nextPageButton.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        if (this.configButton != null) {
            this.configButton.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        if (this.viewRecipeButton != null) {
            this.viewRecipeButton.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        if (this.closeButton != null) {
            this.closeButton.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        this.renderItemTooltip(guiGraphics, mouseX, mouseY);
    }

    private void renderJeiStyleBackground(GuiGraphics guiGraphics) {
        int x = this.leftPos;
        int y = this.topPos;
        int width = 270;
        int height = 220;
        guiGraphics.fill(x, y, x + width, y + height, -301989888);
        guiGraphics.fill(x, y, x + width - 1, y + 1, -1);
        guiGraphics.fill(x, y, x + 1, y + height - 1, -1);
        guiGraphics.fill(x + 1, y + height - 1, x + width, y + height, -11184811);
        guiGraphics.fill(x + width - 1, y + 1, x + width, y + height - 1, -11184811);
        guiGraphics.fill(x + 1, y + 1, x + width - 2, y + 2, -1);
        guiGraphics.fill(x + 1, y + 1, x + 2, y + height - 2, -1);
        guiGraphics.fill(x + 2, y + height - 2, x + width - 1, y + height - 1, -11184811);
        guiGraphics.fill(x + width - 2, y + 2, x + width - 1, y + height - 2, -11184811);
    }

    private void renderPageInfo(GuiGraphics guiGraphics) {
        if (this.filteredItems.isEmpty()) {
            String noItemsText = "\u6ca1\u6709\u627e\u5230\u7269\u54c1";
            int textX = this.leftPos + (270 - this.font.width(noItemsText)) / 2 + 0;
            int textY = this.topPos + 110 + -22;
            guiGraphics.drawString(this.font, noItemsText, textX, textY, 0xFFFFFF);
        } else {
            String pageInfo = String.format("%d/%d", this.currentPage + 1, this.maxPage + 1);
            int pageInfoX = this.leftPos + (270 - this.font.width(pageInfo)) / 2 + 0;
            int pageInfoY = this.topPos + 220 - 6 - 10 + -22;
            guiGraphics.drawString(this.font, pageInfo, pageInfoX, pageInfoY, 0xFFFFFF);
        }
    }

    private void renderItemGrid(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        boolean isHovered;
        boolean isSelected;
        ItemStack stack;
        int slotY;
        int slotX;
        int col;
        int row;
        int gridIndex;
        int i;
        int startIndex = this.currentPage * 72;
        int endIndex = Math.min(startIndex + 72, this.filteredItems.size());
        int gridStartX = this.leftPos + 27;
        int gridStartY = this.topPos + 6 + 20 + 2 + 5;
        for (i = startIndex; i < endIndex; ++i) {
            gridIndex = i - startIndex;
            row = gridIndex / 12;
            col = gridIndex % 12;
            slotX = gridStartX + col * 18;
            slotY = gridStartY + row * 18;
            stack = this.filteredItems.get(i);
            isSelected = stack.equals(this.selectedItem);
            boolean bl = isHovered = mouseX >= slotX && mouseX < slotX + 18 && mouseY >= slotY && mouseY < slotY + 18;
            if (isHovered && !isSelected) {
                this.renderHoveredSlotEffects(guiGraphics, slotX, slotY);
            } else if (!isSelected) {
                this.renderDefaultSlotBackground(guiGraphics, slotX, slotY);
            }
            this.renderSlotBorder(guiGraphics, slotX, slotY, isSelected, isHovered);
            guiGraphics.renderItem(stack, slotX + 1, slotY + 1);
            guiGraphics.renderItemDecorations(this.font, stack, slotX + 1, slotY + 1);
        }
        for (i = startIndex; i < endIndex; ++i) {
            gridIndex = i - startIndex;
            row = gridIndex / 12;
            col = gridIndex % 12;
            slotX = gridStartX + col * 18;
            slotY = gridStartY + row * 18;
            stack = this.filteredItems.get(i);
            isSelected = this.selectedItem != null && ItemStack.isSameItemSameComponents((ItemStack)stack, (ItemStack)this.selectedItem);
            boolean bl = isHovered = mouseX >= slotX && mouseX < slotX + 18 && mouseY >= slotY && mouseY < slotY + 18;
            if (!isSelected) continue;
            this.updateSelectionAnimation();
            this.renderSelectedSlotEffects(guiGraphics, slotX, slotY, isHovered);
        }
    }

    private void renderJeiSlot(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, boolean isSelected) {
        boolean isHovered = mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18;
        this.updateSelectionAnimation();
        if (isSelected) {
            this.renderSelectedSlotEffects(guiGraphics, x, y, isHovered);
        } else if (isHovered) {
            this.renderHoveredSlotEffects(guiGraphics, x, y);
        } else {
            this.renderDefaultSlotBackground(guiGraphics, x, y);
        }
        this.renderSlotBorder(guiGraphics, x, y, isSelected, isHovered);
    }

    private void updateSelectionAnimation() {
        if (this.lastSelectionTime > 0L) {
            long currentTime = System.currentTimeMillis();
            long elapsed = currentTime - this.lastSelectionTime;
            if (elapsed < 200L) {
                this.selectionAnimationProgress = (float)elapsed / 200.0f;
            } else {
                this.selectionAnimationProgress = 1.0f;
                this.lastSelectionTime = 0L;
            }
        }
    }

    private void renderSelectedSlotEffects(GuiGraphics guiGraphics, int x, int y, boolean isHovered) {
        int fillColor = isHovered ? -2130706560 : -2130706535;
        guiGraphics.fill(x, y, x + 18, y + 18, fillColor);
        int borderColor = -10496;
        guiGraphics.fill(x - 1, y - 1, x + 18 + 1, y, borderColor);
        guiGraphics.fill(x - 1, y - 1, x, y + 18 + 1, borderColor);
        guiGraphics.fill(x + 18, y - 1, x + 18 + 1, y + 18 + 1, borderColor);
        guiGraphics.fill(x - 1, y + 18, x + 18 + 1, y + 18 + 1, borderColor);
        if (this.selectionAnimationProgress < 1.0f) {
            int alpha = (int)(80.0f * (1.0f - this.selectionAnimationProgress));
            int glow = alpha << 24 | 0xFFD700;
            guiGraphics.fill(x - 2, y - 2, x + 18 + 2, y - 1, glow);
            guiGraphics.fill(x - 2, y - 2, x - 1, y + 18 + 2, glow);
            guiGraphics.fill(x + 18 + 1, y - 2, x + 18 + 2, y + 18 + 2, glow);
            guiGraphics.fill(x - 2, y + 18 + 1, x + 18 + 2, y + 18 + 2, glow);
        }
    }

    private void renderHoveredSlotEffects(GuiGraphics guiGraphics, int x, int y) {
        guiGraphics.fill(x, y, x + 18, y + 18, -2139029505);
        guiGraphics.fill(x - 1, y - 1, x + 18 + 1, y, -8323073);
        guiGraphics.fill(x - 1, y - 1, x, y + 18 + 1, -8323073);
        guiGraphics.fill(x + 18, y - 1, x + 18 + 1, y + 18 + 1, -8323073);
        guiGraphics.fill(x - 1, y + 18, x + 18 + 1, y + 18 + 1, -8323073);
        guiGraphics.fill(x + 1, y + 1, x + 18 - 1, y + 2, 0x40FFFFFF);
    }

    private void renderDefaultSlotBackground(GuiGraphics guiGraphics, int x, int y) {
        guiGraphics.fill(x, y, x + 18, y + 18, 0x40000000);
    }

    private void renderSelectedBorder(GuiGraphics guiGraphics, int x, int y) {
        int outerBorderColor = -256;
        guiGraphics.fill(x - 1, y - 1, x + 18 + 1, y, outerBorderColor);
        guiGraphics.fill(x - 1, y - 1, x, y + 18 + 1, outerBorderColor);
        guiGraphics.fill(x + 18, y - 1, x + 18 + 1, y + 18 + 1, outerBorderColor);
        guiGraphics.fill(x - 1, y + 18, x + 18 + 1, y + 18 + 1, outerBorderColor);
        int innerBorderColor = -1;
        guiGraphics.fill(x, y, x + 18, y + 1, innerBorderColor);
        guiGraphics.fill(x, y, x + 1, y + 18, innerBorderColor);
        guiGraphics.fill(x + 18 - 1, y, x + 18, y + 18, innerBorderColor);
        guiGraphics.fill(x, y + 18 - 1, x + 18, y + 18, innerBorderColor);
    }

    private void renderSlotBorder(GuiGraphics guiGraphics, int x, int y, boolean isSelected, boolean isHovered) {
        if (!isSelected && !isHovered) {
            guiGraphics.fill(x, y, x + 18, y + 1, -11184811);
            guiGraphics.fill(x, y, x + 1, y + 18, -11184811);
            guiGraphics.fill(x + 18 - 1, y, x + 18, y + 18, -1);
            guiGraphics.fill(x, y + 18 - 1, x + 18, y + 18, -1);
        }
    }

    private void renderItemTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        ItemStack hoveredItem = this.getHoveredItem(mouseX, mouseY);
        if (!hoveredItem.isEmpty()) {
            guiGraphics.renderTooltip(this.font, hoveredItem, mouseX, mouseY);
        }
    }

    private ItemStack getHoveredItem(int mouseX, int mouseY) {
        int col;
        int row;
        int gridIndex;
        int itemIndex;
        int gridStartX = this.leftPos + 27;
        int gridStartY = this.topPos + 6 + 20 + 2 + 5;
        if (mouseX >= gridStartX && mouseX < gridStartX + 216 && mouseY >= gridStartY && mouseY < gridStartY + 108 && (itemIndex = this.currentPage * 72 + (gridIndex = (row = (mouseY - gridStartY) / 18) * 12 + (col = (mouseX - gridStartX) / 18))) < this.filteredItems.size()) {
            return this.filteredItems.get(itemIndex);
        }
        return ItemStack.EMPTY;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0) {
            ItemStack clickedItem = this.getHoveredItem((int)mouseX, (int)mouseY);
            if (!clickedItem.isEmpty()) {
                this.selectedItem = clickedItem.copy();
                this.lastSelectionTime = System.currentTimeMillis();
                this.selectionAnimationProgress = 0.0f;
                this.updatePageButtons();
                LOGGER.info("\u9009\u62e9\u4e86\u7269\u54c1: {}", (Object)this.selectedItem.getHoverName().getString());
                return true;
            }
            this.selectedItem = ItemStack.EMPTY;
            this.lastSelectionTime = 0L;
            this.selectionAnimationProgress = 0.0f;
            LOGGER.info("\u6e05\u9664\u4e86\u7269\u54c1\u9009\u62e9");
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.isInItemGrid(mouseX, mouseY)) {
            if (scrollY < 0.0) {
                if (this.currentPage < this.maxPage) {
                    ++this.currentPage;
                    this.updatePageButtons();
                    return true;
                }
            } else if (scrollY > 0.0 && this.currentPage > 0) {
                --this.currentPage;
                this.updatePageButtons();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean isInItemGrid(double mouseX, double mouseY) {
        int gridStartX = this.leftPos + 27;
        int gridStartY = this.topPos + 6 + 20 + 2 + 5;
        return mouseX >= (double)gridStartX && mouseX < (double)(gridStartX + 216) && mouseY >= (double)gridStartY && mouseY < (double)(gridStartY + 108);
    }

    private void viewRecipes(ItemStack item) {
        LOGGER.info("Attempting to view recipes for item: {}", (Object)item.getDisplayName().getString());
        if (this.minecraft != null) {
            try {
                JeiStyleRecipeScreen recipeScreen = new JeiStyleRecipeScreen(item, this.blockPos, this);
                this.minecraft.setScreen((Screen)recipeScreen);
                LOGGER.info("Successfully opened recipe screen for item: {}", (Object)item.getDisplayName().getString());
            }
            catch (Exception e) {
                LOGGER.error("Failed to open recipe screen for item: {}", (Object)item.getDisplayName().getString(), (Object)e);
            }
        } else {
            LOGGER.error("Minecraft instance is null when trying to view recipes");
        }
    }

    public boolean isPauseScreen() {
        return false;
    }

    public void onClose() {
        super.onClose();
    }

    public void updateItemList(List<ItemStack> items) {
    }
}

