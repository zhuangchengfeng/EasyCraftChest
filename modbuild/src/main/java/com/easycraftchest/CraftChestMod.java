/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.logging.LogUtils
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Direction
 *  net.minecraft.core.registries.Registries
 *  net.minecraft.resources.ResourceKey
 *  net.minecraft.server.MinecraftServer
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.flag.FeatureFlags
 *  net.minecraft.world.inventory.MenuType
 *  net.minecraft.world.inventory.MenuType$MenuSupplier
 *  net.minecraft.world.item.CreativeModeTabs
 *  net.minecraft.world.item.Item
 *  net.minecraft.world.item.Item$Properties
 *  net.minecraft.world.level.ItemLike
 *  net.minecraft.world.level.block.Block
 *  net.neoforged.bus.api.IEventBus
 *  net.neoforged.bus.api.SubscribeEvent
 *  net.neoforged.fml.ModList
 *  net.neoforged.fml.common.Mod
 *  net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
 *  net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
 *  net.neoforged.neoforge.capabilities.Capabilities$ItemHandler
 *  net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent
 *  net.neoforged.neoforge.client.event.EntityRenderersEvent$RegisterRenderers
 *  net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
 *  net.neoforged.neoforge.common.ModConfigSpec
 *  net.neoforged.neoforge.common.ModConfigSpec$BooleanValue
 *  net.neoforged.neoforge.common.ModConfigSpec$Builder
 *  net.neoforged.neoforge.common.ModConfigSpec$IntValue
 *  net.neoforged.neoforge.common.NeoForge
 *  net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
 *  net.neoforged.neoforge.event.server.ServerStartingEvent
 *  net.neoforged.neoforge.event.tick.ServerTickEvent$Post
 *  net.neoforged.neoforge.network.IContainerFactory
 *  net.neoforged.neoforge.registries.DeferredRegister
 *  org.slf4j.Logger
 */
package com.easycraftchest;

import com.mojang.logging.LogUtils;
import com.easycraftchest.block.CraftChestBlock;
import com.easycraftchest.block.entity.ModBlockEntities;
import com.easycraftchest.client.gui.CraftChestScreen;
import com.easycraftchest.config.ModConfigs;
import com.easycraftchest.container.CraftChestContainer;
import com.easycraftchest.item.CraftChestBlockItem;
import com.easycraftchest.server.storage.CraftChestManager;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.IContainerFactory;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(value="easycraftchest")
public class CraftChestMod {
    public static final String MODID = "easycraftchest";
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create((ResourceKey)Registries.BLOCK, (String)"easycraftchest");
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create((ResourceKey)Registries.ITEM, (String)"easycraftchest");
    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create((ResourceKey)Registries.MENU, (String)"easycraftchest");
    public static final Supplier<Block> CRAFT_CHEST_BLOCK = BLOCKS.register("craft_chest", () -> new CraftChestBlock());
    public static final Supplier<Item> CRAFT_CHEST_BLOCK_ITEM = ITEMS.register("craft_chest", () -> new CraftChestBlockItem(CRAFT_CHEST_BLOCK.get(), new Item.Properties()));
    public static final Supplier<MenuType<CraftChestContainer>> CRAFT_CHEST_MENU = MENU_TYPES.register("craft_chest", () -> new MenuType((IContainerFactory<CraftChestContainer>)(containerId, playerInventory, extraData) -> {
        BlockPos pos = extraData.readBlockPos();
        return new CraftChestContainer(containerId, playerInventory, pos);
    }, FeatureFlags.DEFAULT_FLAGS));

    public CraftChestMod(IEventBus modEventBus) {
        // 开发调试开关:runClient 加 -Decc.trace=true → 把 com.easycraftchest 日志提到 TRACE(IDE 终端可见内部明细);
        // 正式游玩不加该属性 → 保持默认 INFO,那些 TRACE 明细既不打印也不写进日志文件。
        CraftChestMod.applyTraceIfRequested();
        // 旧配置检测:文件里若没有/不是当前版本标记(easycraftchest-config-version=N),
        // 说明是升级前生成的旧配置,删掉让它按新默认重新生成,避免旧默认值残留。
        try {
            java.io.File dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().toFile();
            java.io.File[] candidates = new java.io.File[]{
                new java.io.File(dir, "easycraftchest-server.toml"),
                new java.io.File(dir, "easycraftchest.toml")
            };
            String marker = "easycraftchest-config-version=" + ModConfigs.CONFIG_VERSION;
            for (java.io.File f : candidates) {
                if (f.isFile()) {
                    String content = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                    if (!content.contains(marker)) {
                        f.delete();
                    }
                }
            }
        } catch (Exception e) {
            // 配置检测失败不应阻止加载,忽略
        }
        ModList.get().getModContainerById("easycraftchest").ifPresent(c -> c.registerConfig(ModConfig.Type.SERVER, ModConfigs.SPEC, "easycraftchest.toml"));
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(this::registerMenuScreens);
        modEventBus.addListener(this::registerRenderers);
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        MENU_TYPES.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        NeoForge.EVENT_BUS.register((Object)this);
    }

    /** 读 -Decc.trace=true 则把 com.easycraftchest 提到 TRACE;否则不动(默认 INFO)。 */
    private static void applyTraceIfRequested() {
        if (Boolean.parseBoolean(System.getProperty("ecc.trace", "false"))) {
            try {
                org.apache.logging.log4j.core.config.Configurator.setLevel("com.easycraftchest", org.apache.logging.log4j.Level.TRACE);
            }
            catch (Throwable t) {
                // log4j-core 缺失时静默忽略
            }
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // 等 FML 日志配置完全就绪后再设一次,避免启动早期被覆盖
        CraftChestMod.applyTraceIfRequested();
        event.enqueueWork(() -> {});
    }

    private void clientSetup(FMLClientSetupEvent event) {
        CraftChestMod.applyTraceIfRequested();
        event.enqueueWork(() -> {});
    }

    private void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(CRAFT_CHEST_MENU.get(), CraftChestScreen::new);
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.ItemHandler.BLOCK, (level, pos, state, blockEntity, context) -> {
            Block patt0$temp = state.getBlock();
            if (patt0$temp instanceof CraftChestBlock) {
                CraftChestBlock block = (CraftChestBlock)patt0$temp;
                Direction side = context instanceof Direction ? context : null;
                return block.getItemHandler(level, pos, side);
            }
            return null;
        }, new Block[]{CRAFT_CHEST_BLOCK.get()});
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept((ItemLike)CRAFT_CHEST_BLOCK_ITEM.get());
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        Debug.log("Custom Storage Mod server starting...");
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 40 == 0) {
            this.syncStorageDataForAllPlayers(event.getServer());
        }
    }

    private void syncStorageDataForAllPlayers(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            CraftChestManager manager = CraftChestManager.get(level);
            HashSet<UUID> onlinePlayers = new HashSet<UUID>();
            for (ServerPlayer player : level.players()) {
                onlinePlayers.add(player.getUUID());
                if (!manager.isPlayerStorageOpen(player.getUUID())) continue;
                // 周期同步:只在容器数据变化时重推(sendStorageDataToPlayer 内部有 changeCounter 守卫),
                // 不再每 2 秒盲目重发整份玩家背包。
                manager.syncStorageIfChanged(player);
            }
            manager.pruneDisconnectedPlayers(onlinePlayers);
        }
    }

    public static class Debug {
        private static final Logger LOGGER = LogUtils.getLogger();
        private static final boolean DEBUG_MODE = false;

        public static void log(String message) {
        }

        public static void logError(String message, Throwable throwable) {
        }

        public static void logWarning(String message) {
        }

        public static boolean isDebugMode() {
            return false;
        }
    }

    public static class Migration {
        public static void migrateFromOldVersion() {
            Debug.log("Checking for data migration...");
            Debug.log("Data migration completed");
        }
    }

    public static class Compatibility {
        public static boolean checkModCompatibility() {
            boolean compatible = true;
            if (Compatibility.isModLoaded("jei")) {
                Debug.log("JEI detected, enabling integration");
            }
            if (Compatibility.isModLoaded("roughlyenoughitems")) {
                Debug.log("REI detected, enabling integration");
            }
            if (Compatibility.isModLoaded("appliedenergistics2")) {
                Debug.logWarning("Applied Energistics 2 detected, some features may conflict");
            }
            return compatible;
        }

        private static boolean isModLoaded(String modId) {
            try {
                return ModList.get().isLoaded(modId);
            }
            catch (Exception e) {
                return false;
            }
        }
    }

    public static class Performance {
        private static long lastGCTime = System.currentTimeMillis();
        private static int operationCount = 0;

        public static void recordOperation() {
            long currentTime;
            if (++operationCount % 1000 == 0 && (currentTime = System.currentTimeMillis()) - lastGCTime > 30000L) {
                Performance.suggestGC();
                lastGCTime = currentTime;
            }
        }

        private static void suggestGC() {
            if (Debug.isDebugMode()) {
                Debug.log("Suggesting garbage collection after " + operationCount + " operations");
            }
            System.gc();
        }

        public static int getOperationCount() {
            return operationCount;
        }

        public static void resetCounters() {
            operationCount = 0;
            lastGCTime = System.currentTimeMillis();
        }
    }

    public static class ModInfo {
        public static final String NAME = "Custom Storage System";
        public static final String VERSION = "1.0.0";
        public static final String DESCRIPTION = "A completely custom storage system with unlimited capacity";
        public static final String AUTHOR = "YourName";

        public static String getFullInfo() {
            return String.format("%s v%s by %s", NAME, VERSION, AUTHOR);
        }
    }

    public static class ConfigHelper {
        public static int getItemsPerPage() {
            return (Integer)Config.ITEMS_PER_PAGE.get();
        }

        public static boolean isAutoSortEnabled() {
            return (Boolean)Config.ENABLE_AUTO_SORT.get();
        }

        public static boolean isQuickStackEnabled() {
            return (Boolean)Config.ENABLE_QUICK_STACK.get();
        }

        public static int getMaxSearchResults() {
            return (Integer)Config.MAX_SEARCH_RESULTS.get();
        }

        public static boolean areItemTooltipsEnabled() {
            return (Boolean)Config.ENABLE_ITEM_TOOLTIPS.get();
        }

        public static boolean areSoundEffectsEnabled() {
            return (Boolean)Config.ENABLE_SOUND_EFFECTS.get();
        }
    }

    public static class Config {
        public static final ModConfigSpec SPEC;
        public static final ModConfigSpec.IntValue ITEMS_PER_PAGE;
        public static final ModConfigSpec.BooleanValue ENABLE_AUTO_SORT;
        public static final ModConfigSpec.BooleanValue ENABLE_QUICK_STACK;
        public static final ModConfigSpec.IntValue MAX_SEARCH_RESULTS;
        public static final ModConfigSpec.BooleanValue ENABLE_ITEM_TOOLTIPS;
        public static final ModConfigSpec.BooleanValue ENABLE_SOUND_EFFECTS;

        static {
            ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
            builder.comment("Custom Storage System Configuration").push("storage");
            ITEMS_PER_PAGE = builder.comment("Number of items displayed per page in storage GUI").defineInRange("itemsPerPage", 105, 45, 200);
            ENABLE_AUTO_SORT = builder.comment("Enable automatic sorting of items in storage").define("enableAutoSort", true);
            ENABLE_QUICK_STACK = builder.comment("Enable quick stack functionality").define("enableQuickStack", true);
            MAX_SEARCH_RESULTS = builder.comment("Maximum number of search results to display").defineInRange("maxSearchResults", 1000, 100, 10000);
            ENABLE_ITEM_TOOLTIPS = builder.comment("Enable enhanced item tooltips in storage GUI").define("enableItemTooltips", true);
            ENABLE_SOUND_EFFECTS = builder.comment("Enable sound effects for storage operations").define("enableSoundEffects", true);
            builder.pop();
            SPEC = builder.build();
        }
    }
}

