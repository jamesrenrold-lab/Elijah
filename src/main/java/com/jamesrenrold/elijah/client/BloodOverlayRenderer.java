package com.jamesrenrold.elijah.client;

import com.jamesrenrold.elijah.ElijahPirate;
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Adds the red overdrive tint while the 100-charge state is active. */
@Mod.EventBusSubscriber(modid = ElijahPirate.MOD_ID, value = Dist.CLIENT)
public final class BloodOverlayRenderer {
    @SubscribeEvent
    public static void renderHuntTint(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.player.hasEffect(MobEffects.DARKNESS)) return;
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        event.getGuiGraphics().fill(0, 0, width, height, 0x2A9B0000);
    }

    private BloodOverlayRenderer() {}
}
