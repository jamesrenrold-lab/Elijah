package com.jamesrenrold.elijah.client;

import com.jamesrenrold.elijah.ElijahPirate;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = ElijahPirate.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private static final String[] ORIGIN_KEYS = {
            "key.origins.primary_active", "key.origins.secondary_active",
            "key.origins.tertiary_active", "key.origins.quaternary_active",
            "key.origins.quinary_active", "key.origins.senary_active",
            "key.origins.septenary_active", "key.origins.octonary_active"
    };
    private static final String[] FALLBACK_KEYS = {
            "key.elijah.primary_active", "key.elijah.secondary_active",
            "key.elijah.tertiary_active", "key.elijah.quaternary_active",
            "key.elijah.quinary_active", "key.elijah.senary_active",
            "key.elijah.septenary_active", "key.elijah.octonary_active"
    };
    private static final KeyMapping[] FALLBACK_MAPPINGS = new KeyMapping[8];

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(ElijahPirate.POWDER_MENU.get(), PowderScreen::new));
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::clientTick);
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        for (int i = 0; i < FALLBACK_MAPPINGS.length; i++) {
            FALLBACK_MAPPINGS[i] = new KeyMapping(FALLBACK_KEYS[i],
                    InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN,
                    "category.elijah");
            event.register(FALLBACK_MAPPINGS[i]);
        }
    }

    public static void clientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            ClientHudState.reset();
            return;
        }
        ClientHudState.tick();
        if (minecraft.screen != null) return;
        for (int ability = 0; ability < ORIGIN_KEYS.length; ability++) {
            KeyMapping mapping = findMapping(minecraft.options, ORIGIN_KEYS[ability]);
            if (mapping == null) mapping = FALLBACK_MAPPINGS[ability];
            if (mapping == null) continue;
            while (mapping.consumeClick()) com.jamesrenrold.elijah.AbilityNetwork.send(ability);
        }
    }

    private static KeyMapping findMapping(Options options, String name) {
        for (KeyMapping mapping : options.keyMappings) {
            if (name.equals(mapping.getName())) return mapping;
        }
        return null;
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ElijahPirate.FLINTLOCK_BALL.get(), FlintlockRenderer::new);
        event.registerEntityRenderer(ElijahPirate.UNDEAD_CREWMATE.get(), UndeadCrewmateRenderer::new);
    }
}
