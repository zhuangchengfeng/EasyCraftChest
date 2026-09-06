package com.easycraftchest.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 模组按键绑定(客户端)。
 */
@EventBusSubscriber(modid = "easycraftchest", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ModKeyBindings {
    /** 在目录物品上按此键置顶/取消置顶。默认 A,可在按键设置里改。 */
    public static final KeyMapping PIN_ITEM = new KeyMapping(
        "key.easycraftchest.pin",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_A,
        "key.categories.easycraftchest"
    );

    /** 打开/关闭"合成历史"面板开关(等同点击左侧 rail 的合成历史按钮)。默认 D,可在按键设置里改。 */
    public static final KeyMapping TOGGLE_HISTORY = new KeyMapping(
        "key.easycraftchest.history",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_D,
        "key.categories.easycraftchest"
    );

    private ModKeyBindings() {
    }

    @SubscribeEvent
    public static void registerKeybindings(RegisterKeyMappingsEvent event) {
        event.register(PIN_ITEM);
        event.register(TOGGLE_HISTORY);
    }
}
