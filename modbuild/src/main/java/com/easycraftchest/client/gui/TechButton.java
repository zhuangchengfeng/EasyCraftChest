package com.easycraftchest.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 科技感按钮(自绘色块,填满按钮区域,无贴图、无白边):
 * - 状态:常态 / 悬停 / 按下(按住保持)/ 禁用;
 * - 交互:按住显示按压态,鼠标松开才触发(与 MC/JEI 一致);松开后叠一层蓝闪反馈;
 * - 可选"图标贴图"(如清除 ✕ deletex)按像素尺寸居中画,替代文字。
 */
public class TechButton extends Button {
    private static final int C_DISABLED = 0xFF14181F;
    private static final int C_IDLE = 0xFF22314A;
    private static final int C_HOVER = 0xFF2C3E61;
    private static final int C_PRESSED = 0xFF16202C;
    private static final int C_PRESSED_HOVER = 0xFF1F2C44;
    private static final int BORDER = 0xFF46566E;
    private static final int BORDER_DISABLED = 0xFF3A4758;
    private static final int TOP_IDLE = 0xFF4A6A9A;
    private static final int TOP_DOWN = 0xFF2E4C72;

    private ResourceLocation iconTexture = null;
    private int iconWidth = 9;
    private int iconHeight = 9;

    private boolean down = false;
    private long releasedAt = -1L;

    public TechButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, Button.DEFAULT_NARRATION);
    }

    /** 设置图标贴图(像素宽高),按钮正中按该尺寸绘制、替代文字。 */
    public TechButton icon(ResourceLocation texture, int w, int h) {
        this.iconTexture = texture;
        this.iconWidth = Math.max(1, w);
        this.iconHeight = Math.max(1, h);
        return this;
    }

    /** 按住:只记录按压,不触发;等 mouseReleased 再触发(松开才激活)。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.active && this.visible && button == 0 && this.isMouseOver(mouseX, mouseY)) {
            this.down = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.down) {
            this.down = false;
            this.releasedAt = System.currentTimeMillis();
            if (this.active && this.visible && this.isMouseOver(mouseX, mouseY)) {
                this.onPress();
            }
            return true;
        }
        return false;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        boolean hover = this.isHoveredOrFocused() || this.down;
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();

        int base;
        if (!this.active) {
            base = TechButton.C_DISABLED;
        } else if (this.down && hover) {
            base = TechButton.C_PRESSED_HOVER;
        } else if (this.down) {
            base = TechButton.C_PRESSED;
        } else if (hover) {
            base = TechButton.C_HOVER;
        } else {
            base = TechButton.C_IDLE;
        }
        int py = this.down ? 1 : 0;
        graphics.fill(x, y + py, x + w, y + py + h, base);
        // 上沿细线(柔和,不常亮青色)
        graphics.fill(x, y + py, x + w, y + py + 1, !this.active ? TechButton.TOP_IDLE : (this.down ? TechButton.TOP_DOWN : TechButton.TOP_IDLE));
        // 四边描边(始终灰色,不做常亮青色边)
        int border = this.active ? TechButton.BORDER : TechButton.BORDER_DISABLED;
        graphics.fill(x, y + py, x + w, y + py + 1, border);
        graphics.fill(x, y + py + h - 1, x + w, y + py + h, border);
        graphics.fill(x, y + py, x + 1, y + py + h, border);
        graphics.fill(x + w - 1, y + py, x + w, y + py + h, border);

        Font font = Minecraft.getInstance().font;
        if (this.iconTexture != null) {
            int dw = Math.min(this.iconWidth, w);
            int dh = Math.min(this.iconHeight, h);
            TechButton.drawTexture(graphics, this.iconTexture, x + (w - dw) / 2, y + py + (h - dh) / 2, dw, dh);
        } else {
            String label = this.getMessage().getString();
            int color = !this.active ? 0xFF7A8BA0 : (hover ? 0xFFFFFFFF : 0xFFE6ECF6);
            int tw = font.width(label);
            graphics.drawString(font, label, x + (w - tw) / 2, y + py + (h - 9) / 2, color);
        }

        // 松开后的蓝闪(250ms 淡出,不常亮)
        if (this.active && this.releasedAt >= 0L && now - this.releasedAt < 250L) {
            double fe = (double)(now - this.releasedAt) / 250.0;
            int alpha = (int)(150.0 * (1.0 - fe));
            if (alpha > 0) {
                graphics.fill(x, y + py, x + w, y + py + h, (alpha << 24) | 0x22CCFF);
            }
        } else if (now - this.releasedAt >= 250L) {
            this.releasedAt = -1L;
        }
    }

    /** 把一张贴图拉伸画到 (x,y,w,h)。 */
    private static void drawTexture(GuiGraphics graphics, ResourceLocation tex, int x, int y, int w, int h) {
        if (tex == null) {
            return;
        }
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, tex);
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        com.mojang.blaze3d.vertex.Tesselator tesselator = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        com.mojang.blaze3d.vertex.BufferBuilder buffer = tesselator.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(x, y, 0.0f).setUv(0.0f, 0.0f);
        buffer.addVertex(x, y + h, 0.0f).setUv(0.0f, 1.0f);
        buffer.addVertex(x + w, y + h, 0.0f).setUv(1.0f, 1.0f);
        buffer.addVertex(x + w, y, 0.0f).setUv(1.0f, 0.0f);
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(buffer.build());
    }
}
