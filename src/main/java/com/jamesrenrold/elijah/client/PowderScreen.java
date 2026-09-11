package com.jamesrenrold.elijah.client;

import com.jamesrenrold.elijah.PowderMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
        // 2x2 reserve grid.
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 2; column++) {
                slot(g, x + 20 + column * 18, y + 45 + row * 18, true);
            }
        }
        // Generator moved left; load chamber moved farther right.
        slot(g, x + 80, y + 54, true);
        slot(g, x + 160, y + 54, true);
        drawCrossedFlintlocks(g, x + 101, y + 20);
    }

    private void slot(GuiGraphics g, int x, int y, boolean powder) {
        g.fill(x - 1, y - 1, x + 17, y + 17, powder ? 0xFFB8924C : 0xFF607078);
        g.fill(x, y, x + 16, y + 16, 0xFF0D161C);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawCenteredString(font, title, imageWidth / 2, 9, 0xFFE6CE98);
        g.drawCenteredString(font, "Reserve", 38, 84, 0xFFBFC7C8);
        g.drawCenteredString(font, menu.reserveCount() + "/256", 38, 97, 0xFFE6CE98);
        g.drawCenteredString(font, "Generator", 88, 34, 0xFFBFC7C8);
        g.drawCenteredString(font, menu.generatorSeconds() <= 0 ? "Ready" : menu.generatorSeconds() + "s", 88, 74, 0xFFE6CE98);
        g.drawCenteredString(font, menu.generatorCount() + "/5", 88, 87, 0xFFE6CE98);
        g.drawCenteredString(font, "Load", 168, 34, 0xFFBFC7C8);
        g.drawCenteredString(font, menu.powderCount() + "/9", 168, 74, 0xFFE6CE98);
    }

    /** Small pixel-art crossed flintlocks for the upper-middle of the pouch. */
    private void drawCrossedFlintlocks(GuiGraphics g, int x, int y) {
        int dark = 0xFF34251A;
        int wood = 0xFF8A5A2B;
        int metal = 0xFFD1B16B;
        // Diagonal one: barrel up-right, stock down-left.
        pixel(g, x - 11, y + 11, dark); pixel(g, x - 9, y + 9, wood);
        pixel(g, x - 7, y + 7, wood); pixel(g, x - 5, y + 5, metal);
        pixel(g, x - 3, y + 3, metal); pixel(g, x - 1, y + 1, metal);
        pixel(g, x + 1, y - 1, dark); pixel(g, x + 3, y - 3, dark);
        pixel(g, x + 5, y - 5, dark); pixel(g, x + 7, y - 7, dark);
        // Diagonal two: barrel up-left, stock down-right.
        pixel(g, x + 11, y + 11, dark); pixel(g, x + 9, y + 9, wood);
        pixel(g, x + 7, y + 7, wood); pixel(g, x + 5, y + 5, metal);
        pixel(g, x + 3, y + 3, metal); pixel(g, x + 1, y + 1, metal);
        pixel(g, x - 1, y - 1, dark); pixel(g, x - 3, y - 3, dark);
        pixel(g, x - 5, y - 5, dark); pixel(g, x - 7, y - 7, dark);
    }

    private void pixel(GuiGraphics g, int x, int y, int color) {
        g.fill(x, y, x + 3, y + 3, color);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        // Capability-only output can arrive before the vanilla slot snapshot.
        // Draw a client-side fallback icon from the synchronized count so the
        // generator never looks empty until it is clicked.
        if (menu.generatorCount() > 0 && menu.generatorClientSlotEmpty()) {
            ItemStack powder = new ItemStack(Items.GUNPOWDER, menu.generatorCount());
            Minecraft.getInstance().getItemRenderer().renderAndDecorateItem(
                    powder, leftPos + 80, topPos + 54);
        }
        renderTooltip(g, mouseX, mouseY);
    }
}
