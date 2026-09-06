package com.easycraftchest.client.gui;

import com.easycraftchest.network.NetworkManager;
import com.easycraftchest.network.packet.TrySynthesisPacket;
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
    /** 仓库只读查询(客户端本地镜像,不入服务端):按基础物品 id 返回当前存储数量。 */
    public interface StorageView {
        long countOf(String baseItemId);
    }

    private final BlockPos storagePos;
    private StorageView storageView = null;
    private static final ResourceLocation TEX_BASE = ResourceLocation.fromNamespaceAndPath("easycraftchest", "textures/gui/slot.png");
    private static final ResourceLocation TEX_EXISTING = ResourceLocation.fromNamespaceAndPath("easycraftchest", "textures/gui/slot_existing.png");
    private static final ResourceLocation TEX_CRAFTING = ResourceLocation.fromNamespaceAndPath("easycraftchest", "textures/gui/slot_crafting.png");
    private static final ResourceLocation TEX_NOTEXIT = ResourceLocation.fromNamespaceAndPath("easycraftchest", "textures/gui/slot_notexit.png");
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
    private long resultSetTime = 0L;
    /** 最近查看过配方的物品历史(最多 3 个,新的在前)。 */
    private final List<ItemStack> history = new ArrayList<ItemStack>();

    public RecipeView(BlockPos storagePos) {
        this.storagePos = storagePos;
    }

    /** 注入客户端本地仓库查询(需求4:判断某原料是否够/缺)。 */
    public void setStorageView(StorageView view) {
        this.storageView = view;
    }

    public void setTarget(ItemStack item) {
        this.recipes.clear();
        this.currentRecipeIndex = 0;
        this.clearResult();
        if (item == null || item.isEmpty()) {
            this.targetItem = ItemStack.EMPTY;
        } else {
            this.targetItem = item.copy();
            this.addToHistory(this.targetItem);
            this.findRecipes();
            // 占用原料格多的配方优先显示(如床的羊毛配方 6 格 > 染色配方 2 格),
            // 让"真正合成"的配方排前面,避免染色这类小配方先被看到/被探。
            this.recipes.sort((a, b) -> Integer.compare(RecipeView.recipeUsedSlots(b), RecipeView.recipeUsedSlots(a)));
        }
        this.updateControls();
    }

    /** 配方占用的原料格数量(判定"大配方优先")。工作台数非空格子;其余类型按 1 计。 */
    private static int recipeUsedSlots(Recipe<?> recipe) {
        if (recipe instanceof CraftingRecipe) {
            int n = 0;
            for (Ingredient ing : ((CraftingRecipe)recipe).getIngredients()) {
                if (ing != null && !ing.isEmpty()) ++n;
            }
            return n;
        }
        return 1;
    }

    /** 把查看过配方的物品加入历史(去重、新的在前、最多 3 个)。 */
    private void addToHistory(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return;
        }
        String id = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
        this.history.removeIf(h -> BuiltInRegistries.ITEM.getKey(h.getItem()).toString().equals(id));
        this.history.add(0, item.copy());
        while (this.history.size() > 6) {
            this.history.remove(this.history.size() - 1);
        }
    }

    public ItemStack getHistoryItem(int index) {
        if (index >= 0 && index < this.history.size()) {
            return this.history.get(index);
        }
        return ItemStack.EMPTY;
    }

    /** 导出历史(按序的注册表 ID 列表),用于保存到状态缓存。 */
    public List<String> getHistoryIds() {
        ArrayList<String> ids = new ArrayList<String>();
        for (ItemStack h : this.history) {
            if (h == null || h.isEmpty()) continue;
            ids.add(BuiltInRegistries.ITEM.getKey(h.getItem()).toString());
        }
        return ids;
    }

    /** 从状态缓存恢复历史。 */
    public void restoreHistory(List<String> ids) {
        this.history.clear();
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            if (id == null || id.isEmpty()) continue;
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id));
            if (item == null || item == net.minecraft.world.item.Items.AIR) continue;
            this.history.add(new ItemStack(item));
            if (this.history.size() >= 6) {
                break;
            }
        }
    }

    /** 命中右侧某个历史配方格,返回索引(0~5),未命中返回 -1。 */
    public int getHistorySlotAt(double mouseX, double mouseY, int px, int py) {
        for (int i = 0; i < this.history.size() && i < 6; i++) {
            // 与九宫格 3x3 的行高/列宽(18)对齐
            int sx = px + 116 + (i % 2) * 18;
            int sy = py + 8 + (i / 2) * 18;
            if (mouseX >= (double)sx && mouseX < (double)(sx + 16) && mouseY >= (double)sy && mouseY < (double)(sy + 16)) {
                return i;
            }
        }
        return -1;
    }

    /** 删除某条历史记录(右键删除)。 */
    public void removeHistoryAt(int index) {
        if (index >= 0 && index < this.history.size()) {
            this.history.remove(index);
        }
    }

    /** 命中九宫格内的某个原料格,返回格索引(0~8),未命中返回 -1。 */
    public int getIngredientSlotAt(double mouseX, double mouseY, int px, int py) {
        if (!this.hasRecipes()) {
            return -1;
        }
        int rx = px + 8;
        int ry = py + 8;
        if (mouseX >= (double)rx && mouseX < (double)(rx + 54) && mouseY >= (double)ry && mouseY < (double)(ry + 54)) {
            int col = (int)((mouseX - (double)rx) / 18.0);
            int row = (int)((mouseY - (double)ry) / 18.0);
            return row * 3 + col;
        }
        return -1;
    }

    /** 取九宫格某个原料格代表物品(该原料候选的第一个),便于点击查看其配方。 */
    public ItemStack getIngredientItem(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= 9 || !this.hasRecipes()) {
            return ItemStack.EMPTY;
        }
        Recipe<?> current = this.recipes.get(this.currentRecipeIndex);
        if (!(current instanceof CraftingRecipe)) {
            return ItemStack.EMPTY;
        }
        NonNullList<Ingredient> ingredients = ((CraftingRecipe)current).getIngredients();
        if (slotIndex >= ingredients.size()) {
            return ItemStack.EMPTY;
        }
        Ingredient ingredient = ingredients.get(slotIndex);
        if (ingredient == null || ingredient.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack[] stacks = ingredient.getItems();
        if (stacks == null || stacks.length == 0) {
            return ItemStack.EMPTY;
        }
        return stacks[0].copy();
    }

    /** 取九宫格某原料格的全部候选物品(用于多成员标签,如 #minecraft:beds 的多种床色),可空。 */
    public List<ItemStack> getIngredientOptions(int slotIndex) {
        ArrayList<ItemStack> out = new ArrayList<ItemStack>();
        if (slotIndex < 0 || slotIndex >= 9 || !this.hasRecipes()) {
            return out;
        }
        Recipe<?> current = this.recipes.get(this.currentRecipeIndex);
        if (!(current instanceof CraftingRecipe)) {
            return out;
        }
        NonNullList<Ingredient> ingredients = ((CraftingRecipe)current).getIngredients();
        if (slotIndex >= ingredients.size()) {
            return out;
        }
        Ingredient ingredient = ingredients.get(slotIndex);
        if (ingredient == null || ingredient.isEmpty()) {
            return out;
        }
        for (ItemStack s : ingredient.getItems()) {
            if (s == null || s.isEmpty()) continue;
            out.add(s.copy());
        }
        return out;
    }

    /** 命中结果栏(箭头右侧的成品格),返回 0 或 -1。 */
    public int getResultSlotIndex(double mouseX, double mouseY, int px, int py) {
        if (!this.hasRecipes()) {
            return -1;
        }
        int rx = px + 94;
        int ry = py + 26;
        if (mouseX >= (double)rx && mouseX < (double)(rx + 16) && mouseY >= (double)ry && mouseY < (double)(ry + 16)) {
            return 0;
        }
        return -1;
    }

    /** 当前配方的成品物品。 */
    public ItemStack getResultItem() {
        if (!this.hasRecipes()) {
            return ItemStack.EMPTY;
        }
        Recipe<?> current = this.recipes.get(this.currentRecipeIndex);
        if (current == null) {
            return ItemStack.EMPTY;
        }
        return current.getResultItem((HolderLookup.Provider)Minecraft.getInstance().level.registryAccess()).copy();
    }

    /** 显示合成结果:成功则清空;失败则在配方栏内显示缺失材料或错误信息。 */
    public void showResult(boolean success, String message, Map<String, Long> missing) {
        this.resultLines.clear();
        this.resultIsError = !success;
        if (!success && missing != null && !missing.isEmpty()) {
            String targetName = this.targetItem == null || this.targetItem.isEmpty() ? "" : this.targetItem.getHoverName().getString();
            this.resultLines.add(Component.translatable("gui.easycraftchest.missing_header", targetName).getString());
            for (Map.Entry<String, Long> e : missing.entrySet()) {
                if (this.resultLines.size() >= 7) {
                    break;
                }
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(e.getKey()));
                ItemStack stack = item == null ? ItemStack.EMPTY : new ItemStack(item);
                String name = stack.isEmpty() ? e.getKey() : stack.getHoverName().getString();
                this.resultLines.add(name + " x" + e.getValue());
            }
        } else if (message != null && !message.isEmpty()) {
            // 若是 lang 键则翻译(如合成成功),否则原样显示
            this.resultLines.add(Component.translatable(message).getString());
        }
        this.resultSetTime = System.currentTimeMillis();
    }

    public void clearResult() {
        this.resultLines.clear();
        this.resultSetTime = 0L;
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
        int ry = py + 8 + 54 + 16;
        // 点击次数框获得焦点时自动全选,方便直接输入新数字(参考原版数量框习惯)。
        this.synthesisCountField = new EditBox(font, px + 8, ry, 36, 16, Component.translatable("gui.easycraftchest.count_field")) {
            public void setFocused(boolean focused) {
                super.setFocused(focused);
                if (focused) {
                    RecipeView.selectAll(RecipeView.this.synthesisCountField);
                }
            }
        };
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
        this.trySynthesisButton = new TechButton(px + 66, ry, 56, 16, Component.translatable("gui.easycraftchest.synthesize"), b -> this.onTrySynthesis());
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
        // 合成成功/失败提示 3 秒后自动复原为配方显示
        if (!this.resultLines.isEmpty() && this.resultSetTime > 0L && System.currentTimeMillis() - this.resultSetTime > 3000L) {
            this.clearResult();
        }
        // 右侧 6 个历史配方格(2列×3行,与九宫格 3x3 行高/列宽 18 对齐,始终显示,点击查看、右键删除)
        for (int i = 0; i < 6; i++) {
            int sx = px + 116 + (i % 2) * 18;
            int sy = py + 8 + (i / 2) * 18;
            if (i < this.history.size()) {
                this.renderHistorySlot(graphics, sx, sy, this.history.get(i), mouseX, mouseY);
            } else {
                this.renderEmptyHistorySlot(graphics, sx, sy);
            }
        }
        if (!this.hasRecipes() || this.targetItem.isEmpty()) {
            Component hint = Component.translatable("gui.easycraftchest.recipe_hint");
            int hx = px + (162 - font.width(hint)) / 2;
            graphics.drawString(font, hint, hx, py + 45, 0xFFFFFF, false);
            return;
        }
        if (!this.resultLines.isEmpty()) {
            // 缺料/提示用 0.75 缩放渲染:字体更小,能放更多行、长文案也不易被截断。
            graphics.pose().pushPose();
            graphics.pose().translate(px + 8, py + 4, 0.0f);
            graphics.pose().scale(0.75f, 0.75f, 1.0f);
            int lineY = 6;
            for (String line : this.resultLines) {
                // 带默认阴影(MC 标准),错误用深红
                graphics.drawString(font, line, 0, lineY, this.resultIsError ? 0xFF5555 : 0xFFFFFF);
                lineY += 10;
                if (lineY > 88) {
                    break;
                }
            }
            graphics.pose().popPose();
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
        // 页码位于数量输入框与合成按钮之间(随控件下移)
        graphics.drawString(font, idx, px + 55 - idxWidth / 2, py + 86, 0xFFFFFF, false);
        if (!this.hoveredStack.isEmpty()) {
            graphics.renderTooltip(font, this.hoveredStack, mouseX, mouseY);
        }
    }

    private void renderCraftingRecipe(GuiGraphics graphics, Font font, CraftingRecipe recipe, int x, int y, int mouseX, int mouseY) {
        NonNullList ingredients = recipe.getIngredients();
        // 需求4:逐格计算原料格的状态(空白/仓库有/缺但可合成/缺且不可合成),客户端本地判断,不上服务端。
        // 同一原料(同一组候选物)在九宫格出现多次时,按左上→右下的阅读顺序,先被仓库数量覆盖到的格子算"有"。
        Ingredient[] grid = new Ingredient[9];
        for (int i = 0; i < 9; ++i) {
            grid[i] = i < ingredients.size() ? (Ingredient)ingredients.get(i) : Ingredient.EMPTY;
        }
        int[] state = this.computeGridSlotStates(grid);
        for (int i = 0; i < 9; ++i) {
            int row = i / 3;
            int col = i % 3;
            int slotX = x + col * 18;
            int slotY = y + row * 18;
            ResourceLocation tex = RecipeView.gridSlotTexture(state[i]);
            if (grid[i].isEmpty()) {
                this.renderCraftingBackground(graphics, tex, slotX, slotY);
            } else {
                this.renderIngredientSlotWithTexture(graphics, font, grid[i], slotX, slotY, tex, mouseX, mouseY);
            }
        }
        this.renderArrow(graphics, font, x + 60, y + 18);
        ItemStack result = recipe.getResultItem((HolderLookup.Provider)Minecraft.getInstance().level.registryAccess());
        this.renderSlot(graphics, font, x + 86, y + 18, result, mouseX, mouseY);
    }

    /** 状态码 → 贴图:空白用基础 slot,其余按需求4分级。 */
    private static ResourceLocation gridSlotTexture(int state) {
        if (state == 1) {
            return RecipeView.TEX_EXISTING;
        }
        if (state == 2) {
            return RecipeView.TEX_CRAFTING;
        }
        if (state == 3) {
            return RecipeView.TEX_NOTEXIT;
        }
        return RecipeView.TEX_BASE;
    }

    /**
     * 计算 3x3 配方 9 格各自状态:
     * 0=空白;1=仓库有货(existing);2=缺、该原料可用工作台合成(crafting);3=缺、无法工作台合成(notexit)。
     * 同候选组(如三格甘蔗、三格羊毛 tag)合并计数:按阅读顺序先覆盖前 N 格为 existing,超出部分判缺。
     */
    private int[] computeGridSlotStates(Ingredient[] grid) {
        int[] state = new int[9];
        java.util.Map<String, java.util.List<Integer>> groups = new java.util.LinkedHashMap<String, java.util.List<Integer>>();
        for (int i = 0; i < 9; ++i) {
            state[i] = 0;
            Ingredient ing = grid[i];
            if (ing == null || ing.isEmpty()) {
                continue;
            }
            String sig = RecipeView.ingredientSignature(ing);
            if (sig == null || sig.isEmpty()) {
                continue;
            }
            groups.computeIfAbsent(sig, k -> new ArrayList<Integer>()).add(i);
        }
        if (this.storageView == null) {
            return state;
        }
        for (java.util.Map.Entry<String, java.util.List<Integer>> entry : groups.entrySet()) {
            java.util.List<Integer> cells = entry.getValue();
            String sig = entry.getKey();
            long available = 0L;
            boolean craftable = false;
            for (String baseId : sig.split(",")) {
                if (baseId == null || baseId.isEmpty()) {
                    continue;
                }
                available += Math.max(0L, this.storageView.countOf(baseId));
                if (!craftable) {
                    craftable = RecipeView.isWorkbenchCraftable(baseId);
                }
            }
            int covered = (int)Math.min((long)cells.size(), available);
            for (int k = 0; k < cells.size(); ++k) {
                int idx = cells.get(k).intValue();
                state[idx] = k < covered ? 1 : (craftable ? 2 : 3);
            }
        }
        return state;
    }

    /** 原料的稳定签名:候选物品的基础注册 id 排序后逗号拼接(用于区分"同一种原料"出现几格)。 */
    private static String ingredientSignature(Ingredient ing) {
        if (ing == null || ing.isEmpty()) {
            return "";
        }
        java.util.TreeSet<String> ids = new java.util.TreeSet<String>();
        for (ItemStack s : ing.getItems()) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            ids.add(BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        }
        return String.join(",", ids);
    }

    /** 该原料是否能由工作台配方产出(能被一键合成到);用于缺料时选 crafting / notexit 贴图。 */
    private static boolean isWorkbenchCraftable(String baseItemId) {
        try {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(baseItemId));
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                return false;
            }
            return ItemCatalog.buildCraftableItems().contains(item);
        }
        catch (Exception e) {
            return false;
        }
    }

    /** 在 slot 上铺一层 18×18 贴图作底。 */
    private void renderCraftingBackground(GuiGraphics graphics, ResourceLocation tex, int x, int y) {
        RecipeView.drawTex(graphics, tex == null ? RecipeView.TEX_BASE : tex, x - 1, y - 1, 18, 18);
    }

    /** 有原料的格子:先铺分级底图,再画物品图标与悬停高亮。 */
    private void renderIngredientSlotWithTexture(GuiGraphics graphics, Font font, Ingredient ingredient, int x, int y, ResourceLocation tex, int mouseX, int mouseY) {
        this.renderCraftingBackground(graphics, tex, x, y);
        ItemStack[] stacks = ingredient.getItems();
        if (stacks == null || stacks.length == 0) {
            return;
        }
        ItemStack displayStack = stacks[(int)(System.currentTimeMillis() / 1000L % (long)stacks.length)];
        boolean hovered = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
        if (hovered) {
            graphics.fill(x + 1, y + 1, x + 15, y + 15, -2130706433);
        }
        if (!displayStack.isEmpty()) {
            graphics.renderItem(displayStack, x, y);
            if (hovered) {
                this.hoveredStack = displayStack;
            }
        }
    }

    private static void drawTex(GuiGraphics graphics, ResourceLocation tex, int x, int y, int w, int h) {
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
        graphics.fill(x, y, x + 16, y + 16, -6250336);
    }

    private void renderHistorySlot(GuiGraphics graphics, int x, int y, ItemStack stack, int mouseX, int mouseY) {
        graphics.fill(x, y, x + 16, y + 16, -8749702);
        this.drawHistoryBorder(graphics, x, y);
        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
            graphics.fill(x + 1, y + 1, x + 15, y + 15, -2130706433);
        }
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, x, y);
        }
    }

    private void renderEmptyHistorySlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 16, y + 16, -6250336);
        this.drawHistoryBorder(graphics, x, y);
    }

    private void drawHistoryBorder(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y, -11184811);
        graphics.fill(x - 1, y - 1, x, y + 17, -11184811);
        graphics.fill(x + 16, y, x + 17, y + 17, -1);
        graphics.fill(x, y + 16, x + 17, y + 17, -1);
    }

    private void renderArrow(GuiGraphics graphics, Font font, int x, int y) {
        // JEI 风格右箭头:横杆 + 三角头,尖端朝右(从原料指向产物)
        int color = 0xFF3F3F3F;
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

    /** 次数框为空/非法(非 1~999 数字)时,自动设回 1。 */
    public void ensureCountValid() {
        if (this.synthesisCountField == null) {
            return;
        }
        String v = this.synthesisCountField.getValue();
        if (v != null && !v.isEmpty()) {
            try {
                int parsed = Integer.parseInt(v);
                if (parsed > 0 && parsed <= 999) {
                    return;
                }
            }
            catch (NumberFormatException e) {
                // fall through to reset
            }
        }
        this.synthesisCountField.setValue("1");
    }

    /** 选中次数框全部文本,方便直接覆盖输入新数字。 */
    private static void selectAll(EditBox field) {
        if (field == null) {
            return;
        }
        String value = field.getValue();
        if (value == null) {
            value = "";
        }
        field.setCursorPosition(value.length());
        field.setHighlightPos(0);
    }

    /** 右键快捷合成:直接按当前合成次数输入框的值发起一次合成(失败也会显示提示)。 */
    public void quickSynthesize() {
        this.onTrySynthesis();
    }

    private void onTrySynthesis() {
        try {
            this.ensureCountValid();
            if (!this.hasRecipes() || this.recipes.get(this.currentRecipeIndex).getType() != RecipeType.CRAFTING) {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(Component.translatable("message.synthesis.only_crafting"), true);
                }
                return;
            }
            int count = Integer.parseInt(this.synthesisCountField.getValue());
            if (count <= 0 || this.targetItem.isEmpty() || this.storagePos == null) {
                return;
            }
            boolean depositToPlayer = CraftChestScreen.getCurrentInstance() != null && CraftChestScreen.getCurrentInstance().shouldDepositToPlayer();
            TrySynthesisPacket packet = new TrySynthesisPacket(this.targetItem, this.storagePos, count, depositToPlayer);
            NetworkManager.sendToServer(packet);
        }
        catch (NumberFormatException e) {
            // ignore invalid input
        }
    }
}
