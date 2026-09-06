package com.easycraftchest.client.gui;

import com.easycraftchest.client.ModKeyBindings;
import com.easycraftchest.container.CraftChestContainer;
import com.easycraftchest.network.NetworkManager;
import com.easycraftchest.network.StorageNetworkHandler;
import com.easycraftchest.network.packet.SynthesisResultPacket;
import com.easycraftchest.storage.CraftChestData;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
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
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

public class CraftChestScreen
extends AbstractContainerScreen<CraftChestContainer> {
    // 仍在使用的绘制常量
    private static final int BACKGROUND_COLOR = -6250336;
    private static final int BORDER_COLOR = -13158601;
    private static final int SLOT_COLOR = -8749702;
    // 搜索框
    private static final int SEARCH_BOX_X = 8;
    private static final int SEARCH_BOX_Y = 152;
    private static final int SEARCH_BOX_WIDTH = 110;

    // ---- RS 式左侧模式按钮:搜索范围 / 自动聚焦 / 排序(偏好持久化到 config/easycraftchest_ui_prefs.json) ----
    private enum SearchScope { BOTH, CATALOG_ONLY, STORAGE_ONLY }
    private enum SortMode { COUNT, NAME, TIME }
    private static final int RAIL_W = 16;
    private static final int RAIL_GAP = 2;
    private static final net.minecraft.resources.ResourceLocation SLOT_TEX = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("easycraftchest", "textures/gui/slot.png");
    private static final java.io.File UI_PREFS_FILE = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("easycraftchest_ui_prefs.json").toFile();
    private int railLeft = 2;
    private int railTop = 0;
    private SearchScope searchScope = SearchScope.BOTH;
    private boolean autoFocusSearch = false;
    private SortMode sortMode = SortMode.COUNT;
    /** 排序方向:true=降序(↓),false=升序(↑)。 */
    private boolean sortDesc = true;
    /** 合成产物去向:false=进仓库(I),true=优先进玩家背包(O,装不下回落仓库)。 */
    private boolean depositToPlayer = false;
    /** 右上目录是否处于"合成历史"模式(true 时目录显示合成过的物品,按最近成功合成时间倒序)。 */
    private boolean catalogHistoryMode = false;

    private int leftPos;
    private int topPos;
    private Map<String, Long> storageData = new HashMap<String, Long>();
    private final Map<String, Long> lastModifiedData = new HashMap<String, Long>();
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
    /** 服务端下发的本方块合成历史(每物品一条,最新在前);仅 history 模式使用。 */
    private List<CraftChestData.SynthesisHistoryEntry> serverHistory = new ArrayList<CraftChestData.SynthesisHistoryEntry>();
    /** 与 catalogFilteredItems 对齐的历史条目(history 模式时),供 slot tooltip 显示玩家/时间/头像。 */
    private final List<CraftChestData.SynthesisHistoryEntry> catalogHistoryEntries = new ArrayList<CraftChestData.SynthesisHistoryEntry>();
    private int catalogPage = 0;
    private int catalogMaxPage = 0;
    private long lastClientUpdateTime = 0L;
    private String storageSizeText = "";
    private static final Map<BlockPos, StoredState> STATE_CACHE = new HashMap<BlockPos, StoredState>();
    private static CraftChestScreen currentInstance = null;

    public static CraftChestScreen getCurrentInstance() {
        return currentInstance;
    }

    /** 合成产物是否优先进玩家背包(O);false=进仓库(I)。 */
    public boolean shouldDepositToPlayer() {
        return this.depositToPlayer;
    }
    private final BlockPos blockPos;
    private final RecipeView recipeView;
    private int syncTicker = 0;
    private final Map<String, ItemStack> itemStackCache = new HashMap<String, ItemStack>();
    /** tag 原料浏览状态:记录"上次点击的是九宫格第几格 + 轮换到该 tag 第几个成员",
        使多成员标签原料(如任意色床)点击后逐次浏览不同成员,而不是永远固定第一个(如红床)。 */
    private int lastTagIngredientSlot = -1;
    private int tagIngredientOffset = 0;
    private int lastMouseX = -1;
    private int lastMouseY = -1;
    private boolean tooltipCacheValid = false;
    private List<Component> cachedTooltip = null;

    public CraftChestScreen(CraftChestContainer container, Inventory playerInventory, Component title) {
        super(container, playerInventory, title);
        this.imageWidth = 354;
        this.imageHeight = 260;
        this.blockPos = container.getBlockPos();
        this.recipeView = new RecipeView(this.blockPos);
        // 需求4:九宫格原料格分级贴图基于客户端已同步的仓库镜像判断,不再请求服务端。
        this.recipeView.setStorageView(baseId -> CraftChestScreen.this.getLocalStorageCount(baseId));
        currentInstance = this;
    }

    protected void init() {
        super.init();
        this.leftPos = (this.width - 354) / 2;
        CraftChestScreen.loadUiPrefs(this);
        this.railLeft = Math.max(0, this.leftPos - RAIL_W - RAIL_GAP - 3);
        this.railTop = this.topPos + 34;
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
        if (this.autoFocusSearch) {
            this.searchBox.setFocused(true);
            // 让 Screen 的焦点路径也指向搜索框,否则字符输入不会路由进来
            this.setFocused(this.searchBox);
        }
        this.clearSearchButton = new TechButton(this.leftPos + 122, this.topPos + 151, 16, 16, Component.literal(""), button -> this.clearSearch()).icon(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("easycraftchest", "textures/gui/deletex.png"), 9, 7);
        this.addRenderableWidget(this.clearSearchButton);
        this.recipeView.createControls(this.font, this.leftPos + 184, this.topPos + 152);
        if (saved != null) {
            if (!saved.lastTargetItemId.isEmpty()) {
                Item restoredItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(saved.lastTargetItemId));
                if (restoredItem != null && restoredItem != Items.AIR) {
                    this.recipeView.setTarget(new ItemStack(restoredItem));
                }
            }
            // 合成次数不持久化:每次打开 GUI 都默认 1,不恢复上次值。
            this.recipeView.setSynthesisCount("1");
            this.recipeView.restoreHistory(saved.historyItemIds);
        }
        this.recipeView.getSynthesisCountField().setResponder(text -> this.saveState());
        this.addRenderableWidget(this.recipeView.getTrySynthesisButton());
        this.addRenderableWidget(this.recipeView.getSynthesisCountField());
        this.updatePageButtons();
        if (this.minecraft != null) {
            this.minecraft.execute(() -> this.requestStorageData());
        }
        // 若上次离开时停在合成历史模式,打开即拉一次方块历史。
        if (this.catalogHistoryMode) {
            this.requestSynthesisHistory();
        }
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.drawModeRail(graphics, mouseX, mouseY);
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
        this.renderRailTooltip(graphics, mouseX, mouseY);
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

    /** 画 vanilla 式凹槽贴图(18×18),占整格;物品图标随后在 (x,y) 画。 */
    private void drawSlotBackground(GuiGraphics graphics, int x, int y) {
        CraftChestScreen.drawTex(graphics, CraftChestScreen.SLOT_TEX, x - 1, y - 1, 18, 18);
    }

    private void renderItemSlot(GuiGraphics graphics, int x, int y, String itemKey, long count, int mouseX, int mouseY) {
        ItemStack stack;
        this.drawSlotBackground(graphics, x, y);
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
        this.drawSlotBackground(graphics, x, y);
        boolean bl = isHovered = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
        if (isHovered) {
            ItemStack carriedItem = ((CraftChestContainer)this.menu).getCarried();
            if (!carriedItem.isEmpty()) {
                graphics.fill(x + 1, y + 1, x + 15, y + 15, -2147418368);
            } else {
                graphics.fill(x + 1, y + 1, x + 15, y + 15, -2130706433);
            }
        }
    }

    private void renderCatalogSlot(GuiGraphics graphics, int x, int y, ItemStack stack, int mouseX, int mouseY) {
        // 置顶物品用亮蓝色边框;合成过的金色边框功能暂时注释(冗余),先只用置顶蓝框。
        boolean pinned = !stack.isEmpty() && SynthesisStats.isPinned(stack.getItem());
        this.drawSlotBackground(graphics, x, y);
        if (pinned) {
            this.drawBorder(graphics, x - 1, y - 1, 18, 18, -16722433);
        }
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
        this.drawSlotBackground(graphics, x, y);
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
        CraftChestScreen.drawTex(graphics, CraftChestScreen.SLOT_TEX, x - 1, y - 1, 18, 18);
    }

    /** 把一张贴图拉伸画到 (x,y,w,h)。 */
    private static void drawTex(GuiGraphics graphics, net.minecraft.resources.ResourceLocation tex, int x, int y, int w, int h) {
        if (tex == null) {
            return;
        }
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, tex);
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        com.mojang.blaze3d.vertex.Tesselator tess = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        com.mojang.blaze3d.vertex.BufferBuilder buffer = tess.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(x, y, 0.0f).setUv(0.0f, 0.0f);
        buffer.addVertex(x, y + h, 0.0f).setUv(0.0f, 1.0f);
        buffer.addVertex(x + w, y + h, 0.0f).setUv(1.0f, 1.0f);
        buffer.addVertex(x + w, y, 0.0f).setUv(1.0f, 0.0f);
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(buffer.build());
    }

    private void renderStatistics(GuiGraphics graphics) {
        Component stats = Component.translatable("gui.easycraftchest.stats", this.totalTypes, this.formatCount(this.totalItems));
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
            graphics.drawString(this.font, countText, (int)textX, (int)textY, 0xFFFFFF, false);
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
            if (stack.isEmpty()) {
                return;
            }
            // 合成历史模式:额外显示"合成者 + 相对时间 + 头像"(数据来自服务端下发的方块历史)。
            if (this.catalogHistoryMode && catalogIndex < this.catalogHistoryEntries.size()) {
                CraftChestData.SynthesisHistoryEntry entry = this.catalogHistoryEntries.get(catalogIndex);
                if (entry != null) {
                    this.renderHistoryTooltip(graphics, mouseX, mouseY, stack, entry);
                    return;
                }
            }
            graphics.renderTooltip(this.font, stack, mouseX, mouseY);
        }
    }

    /**
     * 渲染"合成历史"slot 的自定义 tooltip(仿 refinedstorage 的紧凑多行风格,自绘以规避 1.21 私有 internal API):
     * 第1行:合成物品名(白,正常字号)
     * 第2行(小字):<头像8> X前 · 由 玩家名 合成
     * 第3行(小字):合成数量: <物品图标> 图标右下角显示那次合成的次数
     * 头像固定 8×8,小字 0.75× 缩放,避免遮挡文字。
     */
    private void renderHistoryTooltip(GuiGraphics graphics, int mouseX, int mouseY, ItemStack stack, CraftChestData.SynthesisHistoryEntry entry) {
        float smallScale = 0.75f;
        String name = stack.getHoverName().getString();
        String rel = this.formatRelativeTime(entry.timeMs);
        String who = (entry.playerName == null || entry.playerName.isEmpty()) ? "?" : entry.playerName;
        String metaA = rel + " · 由 ";
        String metaB = who;
        String metaC = " 合成";
        String countLabel = "合成数量: ";
        // 尺寸预算(小字按 0.75 缩放计宽)
        int avatarSize = 8;
        int pad = 4;
        int lineGap = 2;
        int nameW = this.font.width(name);
        int row2W = avatarSize + 3 + (int)(this.font.width(metaA + metaB + metaC) * smallScale);
        int countLabelW = (int)(this.font.width(countLabel) * smallScale);
        int row3W = countLabelW + 4 + 16;
        int contentW = Math.max(nameW, Math.max(row2W, row3W));
        // 行高:第1行正常字高;第2行取头像与小字较高的;第3行以 16px 图标为主(再留一点给右下角数量)
        int h1 = this.font.lineHeight;
        int h2 = Math.max(avatarSize, (int)(this.font.lineHeight * smallScale));
        int h3 = 18;
        int boxH = pad + h1 + lineGap + h2 + lineGap + h3 + pad;
        int boxW = contentW + pad * 2;
        // 定位在鼠标右下方;超屏则左/上回退
        int tx = mouseX + 12;
        int ty = mouseY + 8;
        if (tx + boxW > this.width - 2) {
            tx = Math.max(2, mouseX - 12 - boxW);
        }
        if (ty + boxH > this.height - 2) {
            ty = Math.max(2, mouseY - 8 - boxH);
        }
        this.drawTooltipBox(graphics, tx, ty, boxW, boxH);
        int x = tx + pad;
        int y = ty + pad;
        // 第1行:物品名
        graphics.drawString(this.font, name, x, y, 0xFFFFFF, true);
        y += h1 + lineGap;
        // 第2行:头像 + 小字
        ResourceLocation skin = this.getPlayerSkin(entry.playerUuid);
        int textY = y + (h2 - (int)(this.font.lineHeight * smallScale)) / 2;
        if (skin != null) {
            this.drawPlayerFace(graphics, skin, x, y + (h2 - avatarSize) / 2, avatarSize);
        }
        int txx = x + avatarSize + 3;
        this.drawSmallText(graphics, metaA, txx, textY, 0xA0A0A0, smallScale);
        txx += (int)(this.font.width(metaA) * smallScale);
        this.drawSmallText(graphics, metaB, txx, textY, 0x7FFFD4, smallScale);
        txx += (int)(this.font.width(metaB) * smallScale);
        this.drawSmallText(graphics, metaC, txx, textY, 0xA0A0A0, smallScale);
        y += h2 + lineGap;
        // 第3行:小标签 + 物品图标;数量按 RS ResourceSlotRendering.renderAmount 的方式自绘
        // (白色带阴影、右对齐、z≈300),而不是依赖 MC renderItemDecorations(count==1 不画/自定义 tooltip 里不可见)。
        int iconY = y + Math.max(0, (h3 - 16) / 2);
        this.drawSmallText(graphics, countLabel, x, iconY + 4, 0xA0A0A0, smallScale);
        int iconX = x + countLabelW + 4;
        ItemStack countStack = stack.copy();
        countStack.setCount(Math.max(1, entry.count));
        graphics.renderItem(countStack, iconX, iconY);
        this.drawAmountOnIcon(graphics, String.valueOf(entry.count), iconX, iconY);
    }

    /** RS 风格:在物品图标上画数量(白色带阴影),数量很小也强制显示。 */
    private void drawAmountOnIcon(GuiGraphics graphics, String amount, int iconX, int iconY) {
        if (amount == null || amount.isEmpty()) {
            return;
        }
        int stringWidth = this.font.width(amount);
        graphics.pose().pushPose();
        graphics.pose().translate(iconX, iconY, 300.0f);
        // 数字能在 16px 内放下就不缩放;放不下再按 0.5 缩放,保证始终可见
        if (stringWidth > 14) {
            graphics.pose().scale(0.5f, 0.5f, 1.0f);
            graphics.drawString(this.font, amount, (float)((30 - stringWidth) * 1), 22, 0xFFFFFF, true);
        } else {
            graphics.drawString(this.font, amount, 17 - stringWidth, 8, 0xFFFFFF, true);
        }
        graphics.pose().popPose();
    }

    /** MC/RS 风格 tooltip 底色 + 边框。 */
    private void drawTooltipBox(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0xF0100010);
        graphics.fill(x, y, x + w, y + 1, 0x505000FF);
        graphics.fill(x, y + h - 1, x + w, y + h, 0x5028007F);
        graphics.fill(x, y, x + 1, y + h, 0x505000FF);
        graphics.fill(x + w - 1, y, x + w, y + h, 0x5028007F);
    }

    /** 缩放画小字(缩到 smallScale×,自带阴影)。 */
    private void drawSmallText(GuiGraphics graphics, String text, int x, int y, int color, float smallScale) {
        if (text == null || text.isEmpty()) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0f);
        graphics.pose().scale(smallScale, smallScale, 1.0f);
        graphics.drawString(this.font, text, 0, 0, color, true);
        graphics.pose().popPose();
    }

    /** 取玩家皮肤贴图(本地 PlayerInfo 缓存,拿不到返回 null,不请求网络)。 */
    private ResourceLocation getPlayerSkin(String playerUuid) {
        try {
            UUID uuid = playerUuid == null || playerUuid.isEmpty() ? null : UUID.fromString(playerUuid);
            if (uuid == null || this.minecraft == null || this.minecraft.getConnection() == null) {
                return null;
            }
            PlayerInfo info = this.minecraft.getConnection().getPlayerInfo(uuid);
            if (info == null || info.getSkin() == null) {
                return null;
            }
            return info.getSkin().texture();
        }
        catch (Exception e) {
            return null;
        }
    }

    /** 画玩家皮肤正脸 8×8 子区域(缩放至 size×size);贴图在 64×64 中的 (8,8)-(16,16)。 */
    private void drawPlayerFace(GuiGraphics graphics, ResourceLocation skin, int x, int y, int size) {
        if (skin == null) {
            return;
        }
        graphics.blit(skin, x, y, 8.0f, 8.0f, size, size, 64, 64);
    }

    /** 把毫秒时间差格式化为"X秒前/X分钟前/X小时前/X天前"。 */
    private String formatRelativeTime(long timeMs) {
        long diff = System.currentTimeMillis() - timeMs;
        if (diff < 0L) {
            diff = 0L;
        }
        long sec = diff / 1000L;
        if (sec < 60L) {
            return sec + " 秒前";
        }
        long min = sec / 60L;
        if (min < 60L) {
            return min + " 分钟前";
        }
        long hr = min / 60L;
        if (hr < 24L) {
            return hr + " 小时前";
        }
        long day = hr / 24L;
        return day + " 天前";
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
        if (!textFocused && ModKeyBindings.TOGGLE_HISTORY.matches(keyCode, scanCode)) {
            this.toggleCatalogHistory();
            CraftChestScreen.saveUiPrefs(this);
            this.refreshAfterModeChange();
            this.saveState();
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
        // 左侧模式按钮轨(左键)
        if (button == 0 && this.handleModeRailClick(mouseX, mouseY)) {
            return true;
        }
        // 点击输入框以外区域时,取消输入框聚焦(否则后续按键会被输入框吞掉,A 键置顶失效)
        boolean onSearchBox = this.searchBox != null && this.searchBox.isMouseOver(mouseX, mouseY);
        boolean onCountField = this.recipeView.getSynthesisCountField() != null && this.recipeView.getSynthesisCountField().isMouseOver(mouseX, mouseY);
        if (!onSearchBox && !onCountField) {
            if (this.searchBox != null) {
                this.searchBox.setFocused(false);
            }
            if (this.recipeView.getSynthesisCountField() != null) {
                boolean wasCountFocused = this.recipeView.getSynthesisCountField().isFocused();
                this.recipeView.getSynthesisCountField().setFocused(false);
                // 次数框失焦且内容为空/非法时,自动设回 1
                if (wasCountFocused) {
                    this.recipeView.ensureCountValid();
                }
            }
        }
        if (this.searchBox.isMouseOver(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int catalogIndex = this.getCatalogSlotAt((int)mouseX, (int)mouseY);
        if (catalogIndex >= 0) {
            if (button == 0) {
                this.lastTagIngredientSlot = -1;
                this.recipeView.setTarget(this.catalogFilteredItems.get(catalogIndex));
                this.saveState();
            } else if (button == 1) {
                // 右键快捷合成:按输入框次数直接合成
                this.lastTagIngredientSlot = -1;
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
                    this.lastTagIngredientSlot = -1;
                    this.recipeView.setTarget(hist);
                    this.saveState();
                }
            } else if (button == 1) {
                // 右键删除该条历史记录
                this.recipeView.removeHistoryAt(historyIndex);
                this.saveState();
            }
            return true;
        }
        int ingredientSlot = this.recipeView.getIngredientSlotAt(mouseX, mouseY, this.leftPos + 184, this.topPos + 152);
        if (ingredientSlot >= 0) {
            if (button == 0) {
                // 多成员标签原料(如"任意色床"):记录该格的 tag,每次点击轮换浏览不同成员,
                // 仓库里已有的颜色排最前(贴近实际能合成的),而不是永远固定第一个成员(如红床)。
                ItemStack ing = this.cycleTagIngredient(ingredientSlot, this.recipeView.getIngredientOptions(ingredientSlot));
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
        if (clickType == ClickType.QUICK_MOVE && slot != null && !(stackToMove = slot.getItem()).isEmpty() && slotId >= 0 && slotId <= 35 && CraftChestScreen.handleQuickMoveToStorage(stackToMove, slotId)) {
            slot.set(ItemStack.EMPTY);
            return;
        }
        super.slotClicked(slot, slotId, mouseButton, clickType);
    }

    /** 点击九宫格原料格查看配方的入口:单成员原料直接用该成员;
        多成员(标签/选择列表)则"记住该格是 tag",每次点击轮换浏览下一个成员,
        库存里已有的颜色排最前(更贴近实际能合成的),避免永远固定第一个成员(如红床)。
        若没轮到库存成员,点几下也能看到你要的那个颜色的配方。 */
    private ItemStack cycleTagIngredient(int slotIndex, List<ItemStack> options) {
        if (options == null || options.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (options.size() == 1) {
            return options.get(0);
        }
        if (slotIndex != this.lastTagIngredientSlot) {
            this.lastTagIngredientSlot = slotIndex;
            this.tagIngredientOffset = 0;
        } else {
            ++this.tagIngredientOffset;
        }
        ArrayList<ItemStack> ordered = new ArrayList<ItemStack>(options);
        ordered.sort((a, b) -> Integer.compare(this.optionStockRank(a), this.optionStockRank(b)));
        return ordered.get(this.tagIngredientOffset % ordered.size());
    }

    /** 该原料成员若在仓库有货 → 0(排最前),否则 → 1;排序稳定,余下维持配方顺序。 */
    private int optionStockRank(ItemStack s) {
        if (s == null || s.isEmpty() || this.itemStackCache == null || this.itemStackCache.isEmpty()) {
            return 1;
        }
        String base = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        for (String key : this.itemStackCache.keySet()) {
            String kb = this.stripCacheKeySuffix(key);
            if (kb.equals(base)) {
                return 0;
            }
            // 同"颜色前缀"家族也算可支持:仓库有 minecraft:white_wool / white_bed / white_dye 时,
            // 点"任意色床"tag 会优先落到白色床,而不是永远红床(与仓库实际能合成的一致)。
            int lastUnderscore = base.lastIndexOf('_');
            int colon = base.indexOf(':');
            if (lastUnderscore > colon) {
                String colorPrefix = base.substring(0, lastUnderscore);
                if (kb.startsWith(colorPrefix + "_")) {
                    return 0;
                }
            }
        }
        return 1;
    }

    /** 存储条目键去掉 #NBT / @NBT 后缀,还原成基础注册 id。 */
    private String stripCacheKeySuffix(String key) {
        if (key == null) {
            return "";
        }
        String k = key;
        int hash = k.indexOf('#');
        int at = k.indexOf('@');
        if (hash >= 0) k = k.substring(0, hash);
        if (at >= 0) k = k.substring(0, at);
        return k;
    }

    /** 客户端本地查询:某基础物品 id 在仓库镜像中的总量(需求4用;把带 NBT/变体后缀的条目累加到基础 id)。 */
    private long getLocalStorageCount(String baseItemId) {
        if (baseItemId == null || this.storageData == null || this.storageData.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (Map.Entry<String, Long> entry : this.storageData.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (this.stripCacheKeySuffix(entry.getKey()).equals(baseItemId)) {
                total += entry.getValue();
            }
        }
        return total;
    }

    private void handleStorageClick(int slotIndex, int button) {
        List<Map.Entry<String, Long>> pageItems = this.getCurrentPageItems();
        if (slotIndex < pageItems.size()) {
            Map.Entry<String, Long> entry = pageItems.get(slotIndex);
            String itemKey = entry.getKey();
            long count = entry.getValue();
            if (button == 0) {
                boolean isShiftPressed = CraftChestScreen.hasShiftDown();
                this.handleStorageLeftClick(itemKey, count, isShiftPressed);
            } else if (button == 1) {
                this.handleStorageRightClick(itemKey, count);
            }
        } else if (!((CraftChestContainer)this.menu).getCarried().isEmpty()) {
            this.handleStoragePutItem();
        }
    }

    private void handleStorageLeftClick(String itemKey, long count, boolean isShiftPressed) {
        ItemStack carriedItem = ((CraftChestContainer)this.menu).getCarried();
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
            String carriedKey = CraftChestData.getItemKey(carried);
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
        ItemStack carriedItem = ((CraftChestContainer)this.menu).getCarried();
        if (carriedItem.isEmpty()) {
            long takeAmount = 1L;
            this.sendItemOperation(StorageNetworkHandler.OperationType.TAKE, itemKey, takeAmount);
        } else {
            ItemStack storageStack;
            int maxStackSize;
            long currentStorageCount;
            ItemStack carried = carriedItem.copy();
            String carriedKey = CraftChestData.getItemKey(carried);
            if (carriedKey.equals(itemKey) && (currentStorageCount = count) < (long)(maxStackSize = (storageStack = this.getCachedItemStack(itemKey)).getMaxStackSize())) {
                this.sendItemOperation(StorageNetworkHandler.OperationType.PUT, itemKey, 1L);
            }
        }
    }

    private void handleStoragePutItem() {
        ItemStack carriedItem = ((CraftChestContainer)this.menu).getCarried();
        if (!carriedItem.isEmpty()) {
            String itemKey = CraftChestData.getItemKey(carriedItem);
            long putAmount = carriedItem.getCount();
            this.sendItemOperation(StorageNetworkHandler.OperationType.PUT, itemKey, putAmount);
        }
    }

    private void sendItemOperation(StorageNetworkHandler.OperationType type, String itemKey, long amount) {
        StorageNetworkHandler.ItemOperationPacket packet = new StorageNetworkHandler.ItemOperationPacket(type, itemKey, amount, this.currentPage, this.searchFilter, ((CraftChestContainer)this.menu).getCarried());
        NetworkManager.sendToServer(packet);
    }

    private void sendItemOperation(StorageNetworkHandler.OperationType type, String itemKey, long amount, boolean isShiftClick) {
        StorageNetworkHandler.ItemOperationPacket packet = new StorageNetworkHandler.ItemOperationPacket(type, itemKey, amount, this.currentPage, this.searchFilter, ((CraftChestContainer)this.menu).getCarried(), isShiftClick, -1);
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

    private void refreshAfterModeChange() {
        this.cachedFilteredStorage = null;
        this.currentPage = 0;
        this.applyStorageFilter();
        this.applyCatalogFilter();
        this.updatePageButtons();
    }

    /** 左侧按钮轨点击:0=搜索范围循环,1=自动聚焦切换,2=排序方式循环,3=升/降序切换,4=产物去向切换,5=目录/合成历史切换。 */
    private boolean handleModeRailClick(double mouseX, double mouseY) {
        if (mouseX < (double)this.railLeft || mouseX >= (double)(this.railLeft + RAIL_W)) {
            return false;
        }
        if (mouseY < (double)this.railTop || mouseY >= (double)(this.railTop + 120)) {
            return false;
        }
        int idx = (int)(mouseY - (double)this.railTop) / 20;
        if (idx == 0) {
            this.searchScope = SearchScope.values()[(this.searchScope.ordinal() + 1) % SearchScope.values().length];
        } else if (idx == 1) {
            this.autoFocusSearch = !this.autoFocusSearch;
        } else if (idx == 2) {
            this.sortMode = SortMode.values()[(this.sortMode.ordinal() + 1) % SortMode.values().length];
        } else if (idx == 3) {
            this.sortDesc = !this.sortDesc;
        } else if (idx == 4) {
            this.depositToPlayer = !this.depositToPlayer;
        } else if (idx == 5) {
            // 目录 ↔ 合成历史 视图切换:换数据源并回到第一页
            this.toggleCatalogHistory();
        } else {
            return false;
        }
        CraftChestScreen.saveUiPrefs(this);
        this.refreshAfterModeChange();
        this.saveState();
        return true;
    }

    /** 目录 ↔ 合成历史 视图切换(D 键与 rail 按钮共用):换数据源并回到第一页。 */
    private void toggleCatalogHistory() {
        this.catalogHistoryMode = !this.catalogHistoryMode;
        this.catalogPage = 0;
        this.catalogMaxPage = 0;
        if (this.catalogHistoryMode) {
            // 进入历史面板时向服务端拉取一次本方块合成历史(每物品一条,最新在前)。
            this.requestSynthesisHistory();
        }
    }

    /** 请求本方块合成历史(服务端权威)。 */
    private void requestSynthesisHistory() {
        if (this.blockPos == null) {
            return;
        }
        NetworkManager.sendToServer(new StorageNetworkHandler.SynthesisHistoryRequestPacket());
    }

    /** 收到服务端下发合成历史:缓存并刷新目录。 */
    public void receiveSynthesisHistory(List<CraftChestData.SynthesisHistoryEntry> entries) {
        this.serverHistory = entries != null ? new ArrayList<CraftChestData.SynthesisHistoryEntry>(entries) : new ArrayList<CraftChestData.SynthesisHistoryEntry>();
        this.applyCatalogFilter();
        this.updatePageButtons();
    }

    /** 绘制 RS 式左侧按钮轨(背板 + 模式按钮)。 */
    private void drawModeRail(GuiGraphics graphics, int mouseX, int mouseY) {
        if (this.railLeft < 0) {
            return;
        }
        int hover = this.railHoverIndex(mouseX, mouseY);
        // 背板:科技感深蓝半透明
        graphics.fill(this.railLeft - 2, this.railTop - 3, this.railLeft + RAIL_W + 2, this.railTop + 123, 0xC0121622);
        graphics.fill(this.railLeft - 2, this.railTop - 3, this.railLeft + RAIL_W + 2, this.railTop - 2, -11184811);
        graphics.fill(this.railLeft - 2, this.railTop + 122, this.railLeft + RAIL_W + 2, this.railTop + 123, -11184811);
        for (int i = 0; i < 6; i++) {
            int y = this.railTop + i * 20;
            boolean active = (i == 0 && this.searchScope != SearchScope.BOTH) || (i == 1 && this.autoFocusSearch) || i == 2 || i == 3 || (i == 4 && this.depositToPlayer) || (i == 5 && this.catalogHistoryMode);
            boolean hovered = hover == i;
            int inner = hovered ? 0x77224488 : (active ? 0x552266FF : 0x55101014);
            graphics.fill(this.railLeft, y, this.railLeft + RAIL_W, y + 16, inner);
            int border = hovered ? -1 : (active ? -16722433 : -11184811);
            this.drawBorder(graphics, this.railLeft, y, RAIL_W, 17, border);
            if (active) {
                graphics.fill(this.railLeft, y, this.railLeft + RAIL_W, y + 1, -16722433);
            }
            this.drawRailButtonIcon(graphics, i, this.railLeft, y, hovered ? -1 : -16722433);
        }
    }

    /** 画单个按钮的图标:0=搜索范围(L/L+R/R),1=自动聚焦,2=排序(# / ID / 时钟),3=升/降序,4=产物去向(I/O),5=合成历史(H)。 */
    private void drawRailButtonIcon(GuiGraphics graphics, int idx, int x, int y, int col) {
        if (idx == 0) {
            String t = this.searchScope == SearchScope.BOTH ? "L+R" : (this.searchScope == SearchScope.CATALOG_ONLY ? "R" : "L");
            this.drawRailText(graphics, t, x, y, col);
            return;
        }
        if (idx == 1) {
            String t = this.autoFocusSearch ? "A" : "-";
            graphics.drawString(this.font, t, x + (RAIL_W - this.font.width(t)) / 2, y + 5, col);
            return;
        }
        if (idx == 3) {
            // 升/降序箭头
            this.drawRailText(graphics, this.sortDesc ? "↓" : "↑", x, y, col);
            return;
        }
        if (idx == 4) {
            // 合成产物去向:I=进仓库,O=进玩家背包
            this.drawRailText(graphics, this.depositToPlayer ? "O" : "I", x, y, col);
            return;
        }
        if (idx == 5) {
            // 目录 ↔ 合成历史切换
            this.drawRailText(graphics, this.catalogHistoryMode ? "H+" : "H", x, y, col);
            return;
        }
        if (this.sortMode == SortMode.COUNT) {
            graphics.drawString(this.font, "#", x + (RAIL_W - this.font.width("#")) / 2, y + 5, col);
        } else if (this.sortMode == SortMode.NAME) {
            this.drawRailText(graphics, "ID", x, y, col);
        } else {
            // 直接渲染"时钟"物品图标,清晰且不会被缩放糊掉
            graphics.renderItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CLOCK), x, y);
        }
    }

    /** 在 16×16 按钮里画文字;太长自动缩小,保证放得下。 */
    private void drawRailText(GuiGraphics graphics, String text, int x, int y, int col) {
        int w = this.font.width(text);
        if (w <= RAIL_W - 2) {
            graphics.drawString(this.font, text, x + (RAIL_W - w) / 2, y + 5, col);
            return;
        }
        float scale = (float)(RAIL_W - 2) / (float)w;
        float sw = w * scale;
        graphics.pose().pushPose();
        graphics.pose().translate((double)(x + (RAIL_W - sw) / 2.0f), (double)(y + 5), 0.0);
        graphics.pose().scale(scale, scale, 1.0f);
        graphics.drawString(this.font, text, 0, 0, col);
        graphics.pose().popPose();
    }

    private int railHoverIndex(double mouseX, double mouseY) {
        if (mouseX < (double)this.railLeft || mouseX >= (double)(this.railLeft + RAIL_W)) {
            return -1;
        }
        if (mouseY < (double)this.railTop || mouseY >= (double)(this.railTop + 120)) {
            return -1;
        }
        int idx = (int)(mouseY - (double)this.railTop) / 20;
        return idx >= 0 && idx <= 5 ? idx : -1;
    }

    /** 悬停左侧按钮时给出中英 tooltip。 */
    private void renderRailTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int idx = this.railHoverIndex(mouseX, mouseY);
        if (idx < 0) {
            return;
        }
        java.util.List<Component> lines = new ArrayList<Component>();
        if (idx == 0) {
            lines.add(Component.translatable("gui.easycraftchest.rail.search_scope"));
            lines.add(Component.translatable("gui.easycraftchest.rail.search." + this.searchScope.name().toLowerCase()));
        } else if (idx == 1) {
            lines.add(Component.translatable("gui.easycraftchest.rail.auto_focus"));
            lines.add(Component.translatable(this.autoFocusSearch ? "gui.easycraftchest.rail.on" : "gui.easycraftchest.rail.off"));
        } else if (idx == 2) {
            lines.add(Component.translatable("gui.easycraftchest.rail.sort"));
            lines.add(Component.translatable("gui.easycraftchest.rail.sort." + this.sortMode.name().toLowerCase()));
        } else if (idx == 3) {
            lines.add(Component.translatable("gui.easycraftchest.rail.sort_direction"));
            lines.add(Component.translatable(this.sortDesc ? "gui.easycraftchest.rail.desc" : "gui.easycraftchest.rail.asc"));
        } else if (idx == 4) {
            lines.add(Component.translatable("gui.easycraftchest.rail.output"));
            lines.add(Component.translatable(this.depositToPlayer ? "gui.easycraftchest.rail.out" : "gui.easycraftchest.rail.in"));
        } else {
            lines.add(Component.translatable("gui.easycraftchest.rail.history"));
            lines.add(Component.translatable(this.catalogHistoryMode ? "gui.easycraftchest.rail.history.on" : "gui.easycraftchest.rail.history.off"));
        }
        graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    private static void loadUiPrefs(CraftChestScreen screen) {
        try {
            if (CraftChestScreen.UI_PREFS_FILE.exists()) {
                com.google.gson.Gson gson = new com.google.gson.Gson();
                java.io.FileReader reader = new java.io.FileReader(CraftChestScreen.UI_PREFS_FILE);
                UiPrefsData data = gson.fromJson(reader, UiPrefsData.class);
                reader.close();
                if (data != null) {
                    if (data.scope != null) {
                        try {
                            screen.searchScope = SearchScope.valueOf(data.scope);
                        }
                        catch (Exception e) {
                            // ignore
                        }
                    }
                    screen.autoFocusSearch = data.autoFocus;
                    if (data.sort != null) {
                        try {
                            screen.sortMode = SortMode.valueOf(data.sort);
                        }
                        catch (Exception e) {
                            // ignore
                        }
                    }
                    screen.sortDesc = data.desc;
                    screen.depositToPlayer = data.deposit;
                    screen.catalogHistoryMode = data.history;
                }
            }
        }
        catch (Exception e) {
            // ignore corrupt prefs
        }
    }

    private static void saveUiPrefs(CraftChestScreen screen) {
        try {
            UiPrefsData data = new UiPrefsData();
            data.scope = screen.searchScope.name();
            data.autoFocus = screen.autoFocusSearch;
            data.sort = screen.sortMode.name();
            data.desc = screen.sortDesc;
            data.deposit = screen.depositToPlayer;
            data.history = screen.catalogHistoryMode;
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.io.File parent = CraftChestScreen.UI_PREFS_FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            java.io.FileWriter writer = new java.io.FileWriter(CraftChestScreen.UI_PREFS_FILE);
            gson.toJson(data, writer);
            writer.close();
        }
        catch (Exception e) {
            // ignore save errors
        }
    }

    private static final class UiPrefsData {
        String scope = "BOTH";
        boolean autoFocus = false;
        String sort = "COUNT";
        boolean desc = true;
        boolean deposit = false;
        boolean history = false;
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
        this.catalogHistoryEntries.clear();
        // 合成历史模式:右上目录换成"本方块合成过的物品"(服务端权威),按最近成功合成时间倒序,最新在前。
        // 注意:历史面板不受搜索词影响,始终显示全部(最多一页54)。
        if (this.catalogHistoryMode) {
            for (CraftChestData.SynthesisHistoryEntry entry : this.serverHistory) {
                String id = entry == null ? null : entry.itemKey;
                if (id == null || id.isEmpty()) continue;
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id));
                if (item == null || item == Items.AIR) continue;
                this.catalogFilteredItems.add(new ItemStack(item));
                this.catalogHistoryEntries.add(entry);
            }
        } else {
            Set<Item> craftable = ItemCatalog.buildCraftableItems();
            for (ItemStack stack : this.catalogAllItems) {
                if (!craftable.contains(stack.getItem())) continue;
                // 搜索范围:仅仓库栏时,目录(配方栏)不过滤
                if (this.searchScope != SearchScope.STORAGE_ONLY && !ItemCatalog.matchesSearchFilter(stack, this.searchFilter)) continue;
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
        }
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
            // 搜索范围:仅配方栏时,仓库栏不过滤
            if (!this.searchFilter.isEmpty() && this.searchScope != SearchScope.CATALOG_ONLY) {
                ItemStack stack = this.getCachedItemStack(entry.getKey());
                String name = stack.getHoverName().getString();
                if (!ItemCatalog.matchesItemName(name, this.searchFilter)) continue;
            }
            result.add(new AbstractMap.SimpleEntry<String, Long>(entry.getKey(), entry.getValue()));
        }
        // 排序:一律先按"升序"排好,再视 sortDesc 决定是否反转
        if (this.sortMode == SortMode.COUNT) {
            result.sort((a, b) -> {
                int cmp = Long.compare(a.getValue(), b.getValue());
                return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
            });
        } else if (this.sortMode == SortMode.NAME) {
            result.sort((a, b) -> {
                String na = this.getCachedItemStack(a.getKey()).getHoverName().getString();
                String nb = this.getCachedItemStack(b.getKey()).getHoverName().getString();
                int cmp = na.compareToIgnoreCase(nb);
                return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
            });
        } else if (this.sortMode == SortMode.TIME) {
            result.sort((a, b) -> {
                long ta = this.getLastModified(a.getKey());
                long tb = this.getLastModified(b.getKey());
                int cmp = Long.compare(ta, tb);
                return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
            });
        }
        if (this.sortDesc) {
            java.util.Collections.reverse(result);
        }
        this.cachedFilteredStorage = result;
        return result;
    }

    /** 某物品最后修改时间(服务端下发;无则为 0,视为最旧)。 */
    private long getLastModified(String key) {
        Long v = key == null ? null : this.lastModifiedData.get(key);
        return v == null ? 0L : v;
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
        this.lastModifiedData.clear();
        Map<String, Long> mods = packet.getModified();
        if (mods != null) {
            this.lastModifiedData.putAll(mods);
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
        this.storageSizeText = CraftChestScreen.formatBytes(bytes);
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
                    // 合成记录改由服务端权威落盘(方块数据);若正开着历史面板,刷新一次以把新条目排到最前。
                    if (this.catalogHistoryMode) {
                        this.requestSynthesisHistory();
                    }
                    this.applyCatalogFilter();
                    this.updatePageButtons();
                }
            }
            this.recipeView.showResult(packet.success(), packet.message(), packet.missing());
        }
    }

    public static boolean handleQuickMoveToStorage(ItemStack itemStack, int slotId) {
        if (currentInstance != null && !itemStack.isEmpty()) {
            String itemKey = CraftChestData.getItemKey(itemStack);
            long putAmount = itemStack.getCount();
            StorageNetworkHandler.ItemOperationPacket packet = new StorageNetworkHandler.ItemOperationPacket(StorageNetworkHandler.OperationType.PUT, itemKey, putAmount, CraftChestScreen.currentInstance.currentPage, CraftChestScreen.currentInstance.searchFilter, itemStack, true, slotId);
            NetworkManager.sendToServer(packet);
            return true;
        }
        return false;
    }

    public static boolean handleQuickMoveToStorage(ItemStack itemStack) {
        return CraftChestScreen.handleQuickMoveToStorage(itemStack, -1);
    }

    public void onClose() {
        this.saveState();
        // 需求2:合成历史为客户端本地持久化;关屏时把内存中的"最近合成时间/次数"写盘。
        SynthesisStats.flush();
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
