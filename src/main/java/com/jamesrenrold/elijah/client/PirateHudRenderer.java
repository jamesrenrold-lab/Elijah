package com.jamesrenrold.elijah.client;

import com.jamesrenrold.elijah.ElijahPirate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Draws the Java-owned resources and timers without feeding anything back into gameplay. */
@Mod.EventBusSubscriber(modid = ElijahPirate.MOD_ID, value = Dist.CLIENT)
public final class PirateHudRenderer {
    private static final int PANEL_SIZE = 154;
    private static final int CELL_WIDTH = 72;
    private static final int CELL_HEIGHT = 25;
    private static final int WHITE = 0xFFF5E7C2;
    private static final int GOLD = 0xFFFFC43D;
    private static final int GOLD_DARK = 0xFF7A4B18;
    private static final int READY = 0xFF7FE39A;
    private static final int ACTIVE = 0xFFFF756B;
    private static final int MUTED = 0xFFB9B09A;
    private static final int SEA = 0xFF163B45;
    private static final int SEA_DARK = 0xFF0B2028;

    @SubscribeEvent
    public static void render(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null || !ClientHudState.shouldRender()) return;

        GuiGraphics gui = event.getGuiGraphics();
        int x = minecraft.getWindow().getGuiScaledWidth() - PANEL_SIZE - 6;
        int y = minecraft.getWindow().getGuiScaledHeight() - PANEL_SIZE - 7;

        drawPanel(gui, minecraft, x, y);
        drawResources(gui, minecraft, x + 6, y + 22);

        int gridY = y + 48;
        drawCell(gui, minecraft, x + 4, gridY,
                Items.BONE, "Dirty", dirtyStatus(), dirtyColor());
        drawCell(gui, minecraft, x + 78, gridY,
                Items.ENDER_EYE, "Curse", cursedStatus(), cursedColor());
        drawCell(gui, minecraft, x + 4, gridY + CELL_HEIGHT,
                Items.FLINT_AND_STEEL, "Flint", timerStatus(ClientHudState.shotCooldown()),
                timerColor(ClientHudState.shotCooldown()));
        drawCell(gui, minecraft, x + 78, gridY + CELL_HEIGHT,
                Items.REDSTONE, "Hunt", huntStatus(), huntColor());
        drawCell(gui, minecraft, x + 4, gridY + CELL_HEIGHT * 2,
                Items.GUNPOWDER, "Pouch", "OPEN", READY);
        drawCell(gui, minecraft, x + 78, gridY + CELL_HEIGHT * 2,
                Items.FEATHER, "Wings", wingsStatus(), wingsColor());
        drawCell(gui, minecraft, x + 4, gridY + CELL_HEIGHT * 3,
                Items.SKELETON_SKULL, "Crew", crewStatus(), crewColor());
        drawCell(gui, minecraft, x + 78, gridY + CELL_HEIGHT * 3,
                Items.COMPASS, "Domain", domainStatus(), domainColor());
    }

    private static void drawPanel(GuiGraphics gui, Minecraft minecraft, int x, int y) {
        // A dark sea-blue wood-like panel with a gold rail and small corner studs.
        gui.fill(x, y, x + PANEL_SIZE, y + PANEL_SIZE, GOLD_DARK);
        gui.fill(x + 2, y + 2, x + PANEL_SIZE - 2, y + PANEL_SIZE - 2, SEA_DARK);
        gui.fill(x + 4, y + 4, x + PANEL_SIZE - 4, y + PANEL_SIZE - 4, SEA);
        gui.fill(x + 4, y + 4, x + PANEL_SIZE - 4, y + 5, GOLD);
        gui.fill(x + 4, y + PANEL_SIZE - 6, x + PANEL_SIZE - 4, y + PANEL_SIZE - 5, GOLD_DARK);
        gui.fill(x + 5, y + 19, x + PANEL_SIZE - 5, y + 20, 0x663A7D83);
        gui.fill(x + 4, y + 4, x + 7, y + 7, GOLD);
        gui.fill(x + PANEL_SIZE - 7, y + 4, x + PANEL_SIZE - 4, y + 7, GOLD);
        gui.fill(x + 4, y + PANEL_SIZE - 8, x + 7, y + PANEL_SIZE - 5, GOLD_DARK);
        gui.fill(x + PANEL_SIZE - 7, y + PANEL_SIZE - 8, x + PANEL_SIZE - 4,
                y + PANEL_SIZE - 5, GOLD_DARK);

        gui.renderItem(new ItemStack(Items.SPYGLASS), x + 7, y + 5);
        gui.drawString(minecraft.font, Component.literal("ELIJAH"), x + 27, y + 6, GOLD, true);
        gui.drawString(minecraft.font, Component.literal("POWERS"), x + 93, y + 7, MUTED, false);
    }

    private static void drawResources(GuiGraphics gui, Minecraft minecraft, int x, int y) {
        gui.drawString(minecraft.font, Component.literal("CURSE"), x, y + 1, WHITE, true);
        int barX = x + 38;
        int barWidth = 77;
        gui.fill(barX, y + 2, barX + barWidth, y + 10, 0xFF351119);
        int fill = Math.round(barWidth * ClientHudState.bloodResource() / 100.0F);
        gui.fill(barX, y + 2, barX + fill, y + 10,
                ClientHudState.overdriveActive() ? 0xFFB71C35 : 0xFF7A1D32);
        gui.fill(barX, y + 2, barX + barWidth, y + 3, 0xFFDC5262);
        gui.drawString(minecraft.font, Component.literal(ClientHudState.bloodResource() + "%"),
                x + 119, y + 1, MUTED, false);

        gui.drawString(minecraft.font, Component.literal("CREW"), x, y + 13, WHITE, true);
        int crewX = x + 38;
        for (int i = 0; i < 4; i++) {
            int segmentX = crewX + i * 19;
            gui.fill(segmentX, y + 15, segmentX + 16, y + 22, 0xFF252525);
            if (i < ClientHudState.crewResource()) {
                gui.fill(segmentX + 1, y + 16, segmentX + 15, y + 21, 0xFFB8872F);
            }
        }
        if (ClientHudState.crewResource() < 4 && ClientHudState.crewRecharge() > 0) {
            gui.drawString(minecraft.font, Component.literal(seconds(ClientHudState.crewRecharge()) + "s"),
                    x + 119, y + 13, MUTED, false);
        }
    }

    private static void drawCell(GuiGraphics gui, Minecraft minecraft, int x, int y,
                                 Item item, String name, String status, int statusColor) {
        gui.fill(x, y, x + CELL_WIDTH, y + CELL_HEIGHT - 1, 0x553A6970);
        gui.fill(x, y, x + 2, y + CELL_HEIGHT - 1, 0x664F9295);
        gui.renderItem(new ItemStack(item), x + 3, y + 3);
        gui.drawString(minecraft.font, Component.literal(name), x + 21, y + 2, WHITE, true);
        gui.drawString(minecraft.font, Component.literal(status), x + 21, y + 13, statusColor, false);
    }

    private static String dirtyStatus() {
        if (ClientHudState.dirtyTacticsArmed()) return "READY";
        return timerStatus(ClientHudState.dirtyCooldown());
    }

    private static int dirtyColor() {
        return ClientHudState.dirtyTacticsArmed() ? READY : timerColor(ClientHudState.dirtyCooldown());
    }

    private static String cursedStatus() {
        if (ClientHudState.cursedFormActive()) return "ON " + ClientHudState.bloodResource() + "%";
        if (ClientHudState.overdriveActive()) return "OVER " + seconds(ClientHudState.overdriveRemaining()) + "s";
        if (ClientHudState.exhaustedActive()) return "REC " + seconds(ClientHudState.exhaustedRemaining()) + "s";
        return timerStatus(ClientHudState.cursedCooldown());
    }

    private static int cursedColor() {
        if (ClientHudState.cursedFormActive()) return ACTIVE;
        if (ClientHudState.overdriveActive() || ClientHudState.exhaustedActive()) return ACTIVE;
        return timerColor(ClientHudState.cursedCooldown());
    }

    private static String huntStatus() {
        if (ClientHudState.huntActive()) return "ON " + seconds(ClientHudState.huntRemaining()) + "s";
        return timerStatus(ClientHudState.huntCooldown());
    }

    private static int huntColor() {
        return ClientHudState.huntActive() ? ACTIVE : timerColor(ClientHudState.huntCooldown());
    }

    private static String wingsStatus() {
        if (ClientHudState.flightActive()) return "ON " + seconds(ClientHudState.flightRemaining()) + "s";
        return timerStatus(ClientHudState.flightCooldown());
    }

    private static int wingsColor() {
        return ClientHudState.flightActive() ? ACTIVE : timerColor(ClientHudState.flightCooldown());
    }

    private static String crewStatus() {
        return ClientHudState.crewResource() + "/4";
    }

    private static int crewColor() {
        return ClientHudState.crewResource() > 0 ? READY : ACTIVE;
    }

    private static String domainStatus() {
        if (ClientHudState.domainRemaining() > 0) return "ON " + seconds(ClientHudState.domainRemaining()) + "s";
        return timerStatus(ClientHudState.domainCooldown());
    }

    private static int domainColor() {
        return ClientHudState.domainRemaining() > 0 ? ACTIVE : timerColor(ClientHudState.domainCooldown());
    }

    private static String timerStatus(int ticks) {
        return ticks > 0 ? seconds(ticks) + "s" : "READY";
    }

    private static int timerColor(int ticks) {
        return ticks > 0 ? GOLD : READY;
    }

    private static int seconds(int ticks) {
        return Math.max(1, (ticks + 19) / 20);
    }

    private PirateHudRenderer() {}
}
