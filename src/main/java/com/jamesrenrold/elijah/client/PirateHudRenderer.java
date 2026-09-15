package com.jamesrenrold.elijah.client;

import com.jamesrenrold.elijah.ElijahPirate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Draws the Java-owned resources and timers without feeding anything back into gameplay. */
@Mod.EventBusSubscriber(modid = ElijahPirate.MOD_ID, value = Dist.CLIENT)
public final class PirateHudRenderer {
    private static final int PANEL_WIDTH = 268;
    private static final int PANEL_HEIGHT = 116;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GOLD = 0xFFFFC43D;
    private static final int READY = 0xFF6FE58A;
    private static final int ACTIVE = 0xFFFF6B6B;
    private static final int MUTED = 0xFFB4B4B4;

    @SubscribeEvent
    public static void render(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null || !ClientHudState.shouldRender()) return;

        GuiGraphics gui = event.getGuiGraphics();
        int x = 7;
        int y = 7;
        gui.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xA8000000);
        gui.fill(x, y, x + PANEL_WIDTH, y + 1, GOLD);
        gui.fill(x, y + PANEL_HEIGHT - 1, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xFF5A4212);
        gui.drawString(minecraft.font, Component.literal("Pirate Powers"), x + 6, y + 4, GOLD, true);

        drawResourceBars(gui, minecraft, x + 6, y + 17);

        int rowsY = y + 48;
        drawRow(gui, minecraft, x + 6, rowsY, "Dirty Tactics", dirtyStatus(), dirtyColor());
        drawRow(gui, minecraft, x + 137, rowsY, "Cursed Form", cursedStatus(), cursedColor());
        drawRow(gui, minecraft, x + 6, rowsY + 15, "Flintlock", timerStatus(ClientHudState.shotCooldown()),
                timerColor(ClientHudState.shotCooldown()));
        drawRow(gui, minecraft, x + 137, rowsY + 15, "Blood Hunt", huntStatus(), huntColor());
        drawRow(gui, minecraft, x + 6, rowsY + 30, "Powder Pouch", "OPEN", READY);
        drawRow(gui, minecraft, x + 137, rowsY + 30, "Blood Wings", wingsStatus(), wingsColor());
        drawRow(gui, minecraft, x + 6, rowsY + 45, "Crew", crewStatus(), crewColor());
        drawRow(gui, minecraft, x + 137, rowsY + 45, "Domain", domainStatus(), domainColor());
    }

    private static void drawResourceBars(GuiGraphics gui, Minecraft minecraft, int x, int y) {
        gui.drawString(minecraft.font, Component.literal("Curse"), x, y, WHITE, true);
        gui.drawString(minecraft.font, Component.literal(ClientHudState.bloodResource() + "/100"), x + 224, y, MUTED, true);
        int barX = x + 36;
        int barY = y + 1;
        int barWidth = 184;
        gui.fill(barX, barY, barX + barWidth, barY + 8, 0xFF351119);
        int fill = Math.round(barWidth * ClientHudState.bloodResource() / 100.0F);
        gui.fill(barX, barY, barX + fill, barY + 8,
                ClientHudState.overdriveActive() ? 0xFFB71C35 : 0xFF7A1D32);
        gui.fill(barX, barY, barX + barWidth, barY + 1, 0xFFDC5262);

        gui.drawString(minecraft.font, Component.literal("Crew"), x, y + 13, WHITE, true);
        int crewX = x + 36;
        for (int i = 0; i < 4; i++) {
            int segmentX = crewX + i * 46;
            gui.fill(segmentX, y + 14, segmentX + 42, y + 22, 0xFF252525);
            if (i < ClientHudState.crewResource()) {
                gui.fill(segmentX + 1, y + 15, segmentX + 41, y + 21, 0xFFB8872F);
            }
        }
        if (ClientHudState.crewResource() < 4 && ClientHudState.crewRecharge() > 0) {
            gui.drawString(minecraft.font, Component.literal("+1 in " + seconds(ClientHudState.crewRecharge()) + "s"),
                    x + 222, y + 13, MUTED, true);
        }
    }

    private static void drawRow(GuiGraphics gui, Minecraft minecraft, int x, int y,
                                String name, String status, int statusColor) {
        gui.drawString(minecraft.font, Component.literal(name), x, y, WHITE, true);
        int statusX = x + 91;
        gui.drawString(minecraft.font, Component.literal(status), statusX, y, statusColor, true);
    }

    private static String dirtyStatus() {
        if (ClientHudState.dirtyTacticsArmed()) return "READY";
        return timerStatus(ClientHudState.dirtyCooldown());
    }

    private static int dirtyColor() {
        return ClientHudState.dirtyTacticsArmed() ? READY : timerColor(ClientHudState.dirtyCooldown());
    }

    private static String cursedStatus() {
        if (ClientHudState.cursedFormActive()) return "ACTIVE";
        if (ClientHudState.overdriveActive()) return "OVERDRIVE " + seconds(ClientHudState.overdriveRemaining()) + "s";
        if (ClientHudState.exhaustedActive()) return "RECOVER " + seconds(ClientHudState.exhaustedRemaining()) + "s";
        return timerStatus(ClientHudState.cursedCooldown());
    }

    private static int cursedColor() {
        if (ClientHudState.cursedFormActive()) return ACTIVE;
        if (ClientHudState.overdriveActive() || ClientHudState.exhaustedActive()) return ACTIVE;
        return timerColor(ClientHudState.cursedCooldown());
    }

    private static String huntStatus() {
        if (ClientHudState.huntActive()) return "ACTIVE " + seconds(ClientHudState.huntRemaining()) + "s";
        return timerStatus(ClientHudState.huntCooldown());
    }

    private static int huntColor() {
        return ClientHudState.huntActive() ? ACTIVE : timerColor(ClientHudState.huntCooldown());
    }

    private static String wingsStatus() {
        if (ClientHudState.flightActive()) return "ACTIVE " + seconds(ClientHudState.flightRemaining()) + "s";
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
        if (ClientHudState.domainRemaining() > 0) return "ACTIVE " + seconds(ClientHudState.domainRemaining()) + "s";
        return timerStatus(ClientHudState.domainCooldown());
    }

    private static int domainColor() {
        return ClientHudState.domainRemaining() > 0 ? ACTIVE : timerColor(ClientHudState.domainCooldown());
    }

    private static String timerStatus(int ticks) {
        return ticks > 0 ? "CD " + seconds(ticks) + "s" : "READY";
    }

    private static int timerColor(int ticks) {
        return ticks > 0 ? GOLD : READY;
    }

    private static int seconds(int ticks) {
        return Math.max(1, (ticks + 19) / 20);
    }

    private PirateHudRenderer() {}
}
