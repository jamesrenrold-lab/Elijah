package com.jamesrenrold.elijah.client;

import com.jamesrenrold.elijah.ElijahPirate;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = ElijahPirate.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(ElijahPirate.POWDER_MENU.get(), PowderScreen::new));
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ElijahPirate.FLINTLOCK_BALL.get(), FlintlockRenderer::new);
        event.registerEntityRenderer(ElijahPirate.UNDEAD_CREWMATE.get(), UndeadCrewmateRenderer::new);
    }
}
