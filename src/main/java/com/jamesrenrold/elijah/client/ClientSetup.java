package com.jamesrenrold.elijah.client;

import com.jamesrenrold.elijah.DomainAbilities;
import com.jamesrenrold.elijah.ElijahPirate;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
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
    private static final boolean[] KEY_WAS_DOWN = new boolean[8];
    private static final int DOMAIN_MUSIC_DELAY_TICKS = 5 * 20;
    private static boolean wasInDomain;
    private static int domainMusicDelay;
    private static SoundInstance domainMusic;

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
            java.util.Arrays.fill(KEY_WAS_DOWN, false);
            stopDomainMusic(minecraft);
            wasInDomain = false;
            domainMusicDelay = 0;
            return;
        }
        ClientHudState.tick();
        tickDomainMusic(minecraft, player);
        if (minecraft.screen != null) {
            java.util.Arrays.fill(KEY_WAS_DOWN, false);
            return;
        }
        for (int ability = 0; ability < ORIGIN_KEYS.length; ability++) {
            KeyMapping mapping = findMapping(minecraft.options, ORIGIN_KEYS[ability]);
            if (mapping == null) mapping = FALLBACK_MAPPINGS[ability];
            boolean down = mapping != null && mapping.isDown();
            // Read the physical key state directly instead of consuming the
            // Origins click queue. Connector can consume that queue while it
            // rebuilds an Origin during a dimension transfer, which made the
            // Java abilities appear to stop working after Domain.
            if (down && !KEY_WAS_DOWN[ability]) {
                com.jamesrenrold.elijah.AbilityNetwork.send(ability);
            }
            KEY_WAS_DOWN[ability] = down;
        }
    }

    private static void tickDomainMusic(Minecraft minecraft, LocalPlayer player) {
        boolean inDomain = DomainAbilities.DOMAIN_DIMENSION.equals(player.level().dimension());
        if (!inDomain) {
            if (wasInDomain || domainMusic != null) stopDomainMusic(minecraft);
            wasInDomain = false;
            domainMusicDelay = 0;
            return;
        }

        if (!wasInDomain) {
            wasInDomain = true;
            domainMusicDelay = DOMAIN_MUSIC_DELAY_TICKS;
            stopDomainMusic(minecraft);
        }
        if (domainMusicDelay > 0) {
            domainMusicDelay--;
            if (domainMusicDelay == 0) {
                minecraft.getMusicManager().stopPlaying();
                domainMusic = SimpleSoundInstance.forMusic(ElijahPirate.REQUIEM.get());
                minecraft.getSoundManager().play(domainMusic);
            }
        }
    }

    private static void stopDomainMusic(Minecraft minecraft) {
        if (domainMusic != null) {
            minecraft.getSoundManager().stop(domainMusic);
            domainMusic = null;
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
