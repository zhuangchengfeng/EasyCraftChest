package com.stroeud.client.gui;

import com.stroeud.util.PinyinHelper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * 客户端静态工具:构建"全部物品"目录列表,并提供名称/拼音搜索过滤。
 * 逻辑源自 ItemManagerScreen,供新存储主界面的右侧合成目录与左侧仓库共用。
 */
public final class ItemCatalog {
    private ItemCatalog() {
    }

    /** 全物品目录静态缓存:整个游戏会话只构建一次,之后直接复用,避免每次打开存储界面都重扫创造物品栏。 */
    private static List<ItemStack> cachedAllItems = null;

    /** 扫描创造物品栏 CATEGORY 页 + ITEM/BLOCK 注册表,去重、过滤,按显示名排序。首次调用构建并缓存,之后返回缓存。 */
    public static List<ItemStack> buildAllItems() {
        if (cachedAllItems != null) {
            return cachedAllItems;
        }
        cachedAllItems = ItemCatalog.buildAllItemsInternal();
        return cachedAllItems;
    }

    private static List<ItemStack> buildAllItemsInternal() {
        ArrayList<ItemStack> allItems = new ArrayList<ItemStack>();
        HashSet<Object> itemUidSet = new HashSet<Object>();
        Minecraft minecraft = Minecraft.getInstance();
        FeatureFlagSet features = Optional.ofNullable(minecraft.player).map(p -> p.connection).map(ClientPacketListener::enabledFeatures).orElse(FeatureFlagSet.of());
        boolean hasOperatorItemsTabPermissions = (Boolean)minecraft.options.operatorItemsTab().get() != false || Optional.of(minecraft).map(m -> m.player).map(Player::canUseGameMasterBlocks).orElse(false) != false;
        if (minecraft.level == null) {
            return allItems;
        }
        RegistryAccess registryAccess = minecraft.level.registryAccess();
        CreativeModeTab.ItemDisplayParameters displayParameters = new CreativeModeTab.ItemDisplayParameters(features, hasOperatorItemsTabPermissions, (HolderLookup.Provider)registryAccess);
        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
            if (tab.getType() != CreativeModeTab.Type.CATEGORY) continue;
            try {
                tab.buildContents(displayParameters);
                Collection displayItems = tab.getDisplayItems();
                Collection searchTabDisplayItems = tab.getSearchTabDisplayItems();
                ItemCatalog.addItemsFromCollection(displayItems, allItems, itemUidSet);
                if (displayItems.equals(searchTabDisplayItems)) continue;
                ItemCatalog.addItemsFromCollection(searchTabDisplayItems, allItems, itemUidSet);
            }
            catch (Exception exception) {
                // skip problematic creative tabs
            }
        }
        ItemCatalog.addItemsFromRegistries(allItems, itemUidSet, features);
        allItems.sort((a, b) -> a.getHoverName().getString().compareToIgnoreCase(b.getHoverName().getString()));
        return allItems;
    }

    private static void addItemsFromCollection(Collection<ItemStack> items, List<ItemStack> allItems, Set<Object> itemUidSet) {
        for (ItemStack stack : items) {
            String uid;
            if (stack.isEmpty() || !ItemCatalog.isItemVisible(stack) || (uid = ItemCatalog.getItemUid(stack)) == null || !itemUidSet.add(uid)) continue;
            allItems.add(stack.copy());
        }
    }

    private static void addItemsFromRegistries(List<ItemStack> allItems, Set<Object> itemUidSet, FeatureFlagSet features) {
        BuiltInRegistries.ITEM.asLookup().filterFeatures(features).listElements().map(ItemStack::new).filter(stack -> !stack.isEmpty() && ItemCatalog.isItemVisible(stack)).forEach(stack -> {
            String uid = ItemCatalog.getItemUid(stack);
            if (uid != null && itemUidSet.add(uid)) {
                allItems.add(stack);
            }
        });
        BuiltInRegistries.BLOCK.asLookup().filterFeatures(features).listElements().map(Holder.Reference::value).map(ItemStack::new).filter(stack -> !stack.isEmpty() && ItemCatalog.isItemVisible(stack)).forEach(stack -> {
            String uid = ItemCatalog.getItemUid(stack);
            if (uid != null && itemUidSet.add(uid)) {
                allItems.add(stack);
            }
        });
    }

    /** "有配方的物品"集合静态缓存:遍历全部配方收集产物,整个会话只算一次。 */
    private static Set<Item> cachedCraftableItems = null;

    /** 返回有配方(工作台/熔炉/切石/锻造等)的物品集合。只遍历配方取产物,O(配方数),毫秒级,之后查集合 O(1)。 */
    public static Set<Item> buildCraftableItems() {
        if (cachedCraftableItems != null) {
            return cachedCraftableItems;
        }
        HashSet<Item> craftable = new HashSet<Item>();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            RecipeManager recipeManager = minecraft.level.getRecipeManager();
            HolderLookup.Provider registry = minecraft.level.registryAccess();
            ItemCatalog.addRecipeResults(recipeManager.getAllRecipesFor(RecipeType.CRAFTING), craftable, registry);
        }
        cachedCraftableItems = craftable;
        return craftable;
    }

    private static void addRecipeResults(List<? extends RecipeHolder<?>> holders, Set<Item> craftable, HolderLookup.Provider registry) {
        for (RecipeHolder<?> holder : holders) {
            try {
                craftable.add(holder.value().getResultItem(registry).getItem());
            }
            catch (Exception e) {
                // skip recipes that cannot be resolved
            }
        }
    }

    private static String getItemUid(ItemStack stack) {
        try {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString() + (!stack.getComponents().isEmpty() ? stack.getComponents().toString() : "");
        }
        catch (Exception e) {
            return null;
        }
    }

    private static boolean isItemVisible(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() == Items.AIR) {
            return false;
        }
        return stack.getItem() != Items.LIGHT;
    }

    /** 右侧目录搜索:名称 + 拼音全拼/首字母 + modid + @mod 前缀。 */
    public static boolean matchesSearchFilter(ItemStack stack, String filter) {
        String lowerFilter = filter == null ? "" : filter.toLowerCase().trim();
        if (lowerFilter.isEmpty()) {
            return true;
        }
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
            String modId = itemId.getNamespace().toLowerCase();
            if (modId.contains(lowerFilter)) {
                return true;
            }
            if (lowerFilter.startsWith("@")) {
                return modId.contains(lowerFilter.substring(1));
            }
        }
        return false;
    }

    /** 左侧仓库过滤:基于物品的本地化显示名(中文)做名称/拼音匹配。 */
    public static boolean matchesItemName(String itemName, String filter) {
        String lowerFilter = filter == null ? "" : filter.toLowerCase().trim();
        if (lowerFilter.isEmpty()) {
            return true;
        }
        if (itemName == null) {
            return false;
        }
        String lowerName = itemName.toLowerCase();
        if (lowerName.contains(lowerFilter)) {
            return true;
        }
        return PinyinHelper.toPinyin(lowerName).toLowerCase().contains(lowerFilter) || PinyinHelper.getFirstLetters(lowerName).toLowerCase().contains(lowerFilter);
    }
}
