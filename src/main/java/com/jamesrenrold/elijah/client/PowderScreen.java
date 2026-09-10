package com.jamesrenrold.elijah.client;

import com.jamesrenrold.elijah.PowderMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class PowderScreen extends AbstractContainerScreen<PowderMenu> {
    public PowderScreen(PowderMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 220;
        imageHeight = 206;
        inventoryLabelY = 112;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        g.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, 0xFFB8924C);
        g.fill(x, y, x + imageWidth, y + imageHeight, 0xFF17232A);
        g.fill(x + 4, y + 4, x + 216, y + 21, 0xFF253842);
        g.fill(x + 19, y + 98, x + 211, y + 99, 0xFF665C43);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) slot(g, x + 20 + column * 18, y + 124 + row * 18, false);
        }
        for (int column = 0; column < 9; column++) slot(g, x + 20 + column * 18, y + 182, false);
        // Chamber.
        slot(g, x + 80, y + 31, true);
        // 4x2 reserve grid.
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 4; column++) {
                slot(g, x + 20 + column * 18, y + 45 + row * 18, true);
            }
        }
        // Automatic generator output.
        slot(g, x + 122, y + 54, true);
        for (int i = 0; i < 9; i++) {
            int color = i < menu.powderCount() ? 0xFFDEC07E : 0xFF3A4548;
            g.fill(x + 58 + i * 7, y + 54, x + 63 + i * 7, y + 58, color);
        }
    }

    private void slot(GuiGraphics g, int x, int y, boolean powder) {
        g.fill(x - 1, y - 1, x + 17, y + 17, powder ? 0xFFB8924C : 0xFF607078);
        g.fill(x, y, x + 16, y + 16, 0xFF0D161C);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawCenteredString(font, title, imageWidth / 2, 9, 0xFFE6CE98);
        g.drawString(font, "Load", 78, 23, 0xFFBFC7C8, false);
        g.drawString(font, menu.powderCount() + "/9", 105, 35, 0xFFE6CE98, false);
        g.drawString(font, "Reserve 4x2", 18, 23, 0xFFBFC7C8, false);
        g.drawString(font, menu.reserveCount() + "/72", 18, 82, 0xFFE6CE98, false);
        g.drawString(font, "Generator", 116, 35, 0xFFBFC7C8, false);
        g.drawString(font, menu.generatorCount() + "/5", 143, 57, 0xFFE6CE98, false);
        g.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFFBFC7C8, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }
}

