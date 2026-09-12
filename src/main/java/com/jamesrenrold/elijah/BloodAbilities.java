package com.jamesrenrold.elijah;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3f;

import java.util.UUID;

/** Server-side state and actions for the pirate's toggleable Cursed Form powers. */
@Mod.EventBusSubscriber(modid = ElijahPirate.MOD_ID)
public final class BloodAbilities {
    private static final int BLOOD_COOLDOWN_TICKS = 20 * 20;
    private static final int HUNT_TICKS = 15 * 20;
    private static final int FLIGHT_TICKS = 20 * 20;
    private static final int OVERDRIVE_TICKS = 25 * 20;
    private static final int EXHAUSTED_TICKS = 30 * 20;
    private static final UUID BLOOD_SPEED_ID = UUID.fromString("75c9a1d0-8840-4a6b-b3d9-8fa4c3f88a11");
    private static final UUID BLOOD_ATTACK_SPEED_ID = UUID.fromString("b3a7c750-c8c5-4a9a-8c67-5ce2ef6cb4f9");
    private static final UUID BLOOD_LIFESTEAL_ID = UUID.fromString("f7ad2d3e-3e63-4da4-9f77-8fc6c9eb4e35");
    private static final UUID HUNT_LIFESTEAL_ID = UUID.fromString("9e8b1f7e-2f5f-4f84-a4e9-1b9b2a6d4c61");
    private static final UUID OVERDRIVE_LIFESTEAL_ID = UUID.fromString("d7d2fb45-b19a-4f90-8fdb-8ce8b7a8c1c0");
    private static final UUID EXHAUSTED_SPEED_ID = UUID.fromString("f6a8cf7f-9193-4c4b-b8ed-12b0e2b2c8a7");
    private static final UUID EXHAUSTED_ATTACK_SPEED_ID = UUID.fromString("8b865b66-a5e1-4ff5-8e63-5df1486b12d9");
    private static final UUID EXHAUSTED_ATTACK_DAMAGE_ID = UUID.fromString("2c8bba32-fc53-46ab-9bda-ec9d9264dc6d");
    private static final DustParticleOptions RED_EYE =
            new DustParticleOptions(new Vector3f(0.95F, 0.02F, 0.02F), 0.65F);

    public static int activateBloodRush(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!player.isAlive() || player.isSpectator() || isHuntActive(player)) return 0;
        PowderPouch state = pouch(player);
        if (state == null) return 0;
        long now = serverTime(player);
        if (state.cursedFormActive) {
            state.cursedFormActive = false;
            state.bloodBuffUntil = 0L;
            state.bloodCooldownUntil = now + BLOOD_COOLDOWN_TICKS;
            ElijahPirate.setOriginResource(player, "elijah:blood_active_window", 0);
            removeRushModifiers(player);
            message(player, "Cursed Form released — cooldown: 20s");
            return 1;
        }
        if (state.bloodOverdriveUntil > now || state.bloodExhaustedUntil > now) {
            message(player, "Cursed Form is locked while your body recovers.");
            return 0;
        }
        if (now < state.bloodCooldownUntil) {
            long seconds = (state.bloodCooldownUntil - now + 19L) / 20L;
            message(player, "Cursed Form cooldown: " + seconds + "s");
            return 0;
        }
        state.cursedFormActive = true;
        state.bloodBuffUntil = 0L;
        state.bloodCooldownUntil = 0L;
        ElijahPirate.changeOriginResource(player, "elijah:blood_resource", 20);
        ElijahPirate.setOriginResource(player, "elijah:blood_active_window", 1);
        ensureBloodModifiers(player);
        message(player, "Cursed Form active — +20 Curse");
        return 1;
    }

    public static int triggerOverdrive(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!player.isAlive() || player.isSpectator()) return 0;
        PowderPouch state = pouch(player);
        if (state == null) return 0;
        long now = serverTime(player);
        // Overflow replaces Cursed Form. Clearing both the Java toggle and the
        // Origins boolean prevents an extra 10% lifesteal and
        // post-overflow charge growth from leaking into this state.
        state.cursedFormActive = false;
        state.bloodBuffUntil = 0L;
        state.bloodOverdriveUntil = now + OVERDRIVE_TICKS;
        state.bloodExhaustedUntil = 0L;
        state.bloodLastDegenerationTick = now;
        state.bloodLastEnemyHitTick = now;
        ElijahPirate.setOriginResource(player, "elijah:blood_resource", 0);
        ElijahPirate.setOriginResource(player, "elijah:blood_active_window", 0);
        AttributeInstance rushLifeSteal = lifeStealAttribute(player);
        if (rushLifeSteal != null) rushLifeSteal.removeModifier(BLOOD_LIFESTEAL_ID);
        AttributeInstance rushSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (rushSpeed != null) rushSpeed.removeModifier(BLOOD_SPEED_ID);
        AttributeInstance rushAttackSpeed = player.getAttribute(Attributes.ATTACK_SPEED);
        if (rushAttackSpeed != null) rushAttackSpeed.removeModifier(BLOOD_ATTACK_SPEED_ID);
        ensureOverdriveLifeSteal(player);
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, OVERDRIVE_TICKS, 0, false, true, true));
        player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, OVERDRIVE_TICKS, 0, false, true, true));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, OVERDRIVE_TICKS, 0, false, true, true));
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, OVERDRIVE_TICKS, 1, false, true, true));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, OVERDRIVE_TICKS, 0, false, true, true));
        message(player, "Curse overflow — the hunger takes hold!");
        return 1;
    }

    public static int activateHunt(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!requireBuff(player)) return 0;
        PowderPouch state = pouch(player);
        if (state == null || isHuntActive(player)) return 0;
        long now = serverTime(player);
        state.bloodHuntUntil = now + HUNT_TICKS;
        state.bloodLastDegenerationTick = now;
        state.bloodLastEnemyHitTick = now;
        state.bloodLockedTarget = null;
        player.closeContainer();
        message(player, "Blood Hunt active — weapon swings only; hunger grows when you stop hitting");
        return 1;
    }

    public static int activateWings(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!requireBuff(player)) return 0;
        PowderPouch state = pouch(player);
        if (state == null || isFlightActive(player)) return 0;
        long now = serverTime(player);
        state.bloodFlightUntil = now + FLIGHT_TICKS;
        state.bloodFlightWasMayFly = false;
        // Start the vanilla fall-flying state directly. No Elytra item is
        // inserted; the temporary power simply supplies the gliding state.
        if (player.onGround()) {
            player.setDeltaMovement(player.getDeltaMovement().add(0.0D, 0.42D, 0.0D));
            player.hasImpulse = true;
        }
        player.startFallFlying();
        player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, FLIGHT_TICKS, 2, false, true, true));
        message(player, "Blood Wings — flight and Dolphin's Grace III");
        return 1;
    }

    public static boolean isHuntActive(ServerPlayer player) {
        PowderPouch state = pouch(player);
        return state != null && state.bloodHuntUntil > serverTime(player);
    }

    private static boolean isFlightActive(ServerPlayer player) {
        PowderPouch state = pouch(player);
        return state != null && state.bloodFlightUntil > serverTime(player);
    }

    public static void clearTransient(ServerPlayer player) {
        PowderPouch state = pouch(player);
        if (state == null) return;
        endHunt(player, state);
        endFlight(player, state);
        state.cursedFormActive = false;
        state.bloodBuffUntil = 0L;
        state.bloodCooldownUntil = 0L;
        state.bloodOverdriveUntil = 0L;
        state.bloodExhaustedUntil = 0L;
        state.bloodLastEnemyHitTick = 0L;
        state.bloodLastDegenerationTick = 0L;
        state.bloodAllowLifestealUntil = 0L;
        removeBloodModifiers(player);
        removeOwnedEffect(player, MobEffects.BLINDNESS, 0, OVERDRIVE_TICKS + 5);
        removeOwnedEffect(player, MobEffects.DARKNESS, 0, OVERDRIVE_TICKS + 5);
        removeOwnedEffect(player, MobEffects.DAMAGE_BOOST, 0, OVERDRIVE_TICKS + 5);
        removeOwnedEffect(player, MobEffects.WEAKNESS, 1, OVERDRIVE_TICKS + 5);
        removeOwnedEffect(player, MobEffects.MOVEMENT_SLOWDOWN, 0, OVERDRIVE_TICKS + 5);
    }

    private static boolean requireBuff(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || isHuntActive(player)) return false;
        PowderPouch state = pouch(player);
        long now = serverTime(player);
        if (state != null && (state.bloodOverdriveUntil > now || state.bloodExhaustedUntil > now)) {
            message(player, "That ability is locked while the blood overfill runs its course.");
            return false;
        }
        if (state == null || !state.cursedFormActive) {
            message(player, "That ability is only available during Cursed Form.");
            return false;
        }
        return true;
    }

    private static PowderPouch pouch(ServerPlayer player) {
        return player.getCapability(PowderPouch.CAPABILITY).orElse(null);
    }

    /** One authoritative clock keeps timers valid across domain dimension transfers. */
    private static long serverTime(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server == null ? player.serverLevel().getGameTime() : server.overworld().getGameTime();
    }

    private static void ensureBloodModifiers(ServerPlayer player) {
        addModifier(player.getAttribute(Attributes.MOVEMENT_SPEED), BLOOD_SPEED_ID,
                "Cursed Form movement speed", 0.10D);
        addModifier(player.getAttribute(Attributes.ATTACK_SPEED), BLOOD_ATTACK_SPEED_ID,
                "Cursed Form attack speed", 0.10D);
        addModifier(lifeStealAttribute(player), BLOOD_LIFESTEAL_ID,
                "Cursed Form life steal", 0.10D, AttributeModifier.Operation.ADDITION);
    }

    private static void addModifier(AttributeInstance instance, UUID id, String name, double value) {
        addModifier(instance, id, name, value, AttributeModifier.Operation.MULTIPLY_TOTAL);
    }

    private static void addModifier(AttributeInstance instance, UUID id, String name, double value,
                                    AttributeModifier.Operation operation) {
        if (instance != null && instance.getModifier(id) == null) {
            instance.addTransientModifier(new AttributeModifier(id, name, value,
                    operation));
        }
    }

    private static AttributeInstance lifeStealAttribute(ServerPlayer player) {
        net.minecraft.world.entity.ai.attributes.Attribute attribute =
                ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation("attributeslib", "life_steal"));
        return attribute == null ? null : player.getAttribute(attribute);
    }

    private static void ensureHuntLifeSteal(ServerPlayer player) {
        addModifier(lifeStealAttribute(player), HUNT_LIFESTEAL_ID,
                "Blood Hunt additional life steal", 0.20D, AttributeModifier.Operation.ADDITION);
    }

    private static void ensureOverdriveLifeSteal(ServerPlayer player) {
        addModifier(lifeStealAttribute(player), OVERDRIVE_LIFESTEAL_ID,
                "Blood overflow life steal", 0.50D, AttributeModifier.Operation.ADDITION);
    }

    private static void removeBloodModifiers(ServerPlayer player) {
        removeRushModifiers(player);
        AttributeInstance lifeSteal = lifeStealAttribute(player);
        if (lifeSteal != null) lifeSteal.removeModifier(HUNT_LIFESTEAL_ID);
        if (lifeSteal != null) lifeSteal.removeModifier(OVERDRIVE_LIFESTEAL_ID);
        AttributeInstance exhaustedSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeInstance exhaustedAttackSpeed = player.getAttribute(Attributes.ATTACK_SPEED);
        AttributeInstance exhaustedAttackDamage = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (exhaustedSpeed != null) exhaustedSpeed.removeModifier(EXHAUSTED_SPEED_ID);
        if (exhaustedAttackSpeed != null) exhaustedAttackSpeed.removeModifier(EXHAUSTED_ATTACK_SPEED_ID);
        if (exhaustedAttackDamage != null) exhaustedAttackDamage.removeModifier(EXHAUSTED_ATTACK_DAMAGE_ID);
    }

    private static void removeRushModifiers(ServerPlayer player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeInstance attackSpeed = player.getAttribute(Attributes.ATTACK_SPEED);
        if (speed != null) speed.removeModifier(BLOOD_SPEED_ID);
        if (attackSpeed != null) attackSpeed.removeModifier(BLOOD_ATTACK_SPEED_ID);
        AttributeInstance lifeSteal = lifeStealAttribute(player);
        if (lifeSteal != null) lifeSteal.removeModifier(BLOOD_LIFESTEAL_ID);
    }

    private static void ensureExhaustionModifiers(ServerPlayer player) {
        addModifier(player.getAttribute(Attributes.MOVEMENT_SPEED), EXHAUSTED_SPEED_ID,
                "Exsanguinated movement penalty", -0.25D);
        addModifier(player.getAttribute(Attributes.ATTACK_SPEED), EXHAUSTED_ATTACK_SPEED_ID,
                "Exsanguinated attack speed penalty", -0.30D);
        addModifier(player.getAttribute(Attributes.ATTACK_DAMAGE), EXHAUSTED_ATTACK_DAMAGE_ID,
                "Exsanguinated attack damage penalty", -0.20D);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        PowderPouch state = pouch(player);
        if (state == null) return;
        long now = serverTime(player);

        if (state.cursedFormActive && state.bloodOverdriveUntil <= now) {
            ensureBloodModifiers(player);
            if (player.tickCount % 2 == 0) spawnEyeParticle(player);
            // Connector can reconstruct Origins powers while crossing a
            // dimension. Reassert the small data-driven toggle periodically
            // so Curse growth always resumes after entering the domain.
            if (player.tickCount % 20 == 0) {
                ElijahPirate.setOriginResource(player, "elijah:blood_active_window", 1);
                // The ordinary Origins timer grants +1 Curse each second.
                // Blood Hunt contributes one additional point on the same
                // cadence, doubling growth to +2 per second while hunting.
                if (state.bloodHuntUntil > now) {
                    ElijahPirate.changeOriginResource(player, "elijah:blood_resource", 1);
                }
            }
        } else {
            removeRushModifiers(player);
            if (player.tickCount % 20 == 0) {
                ElijahPirate.setOriginResource(player, "elijah:blood_active_window", 0);
            }
        }

        if (state.bloodHuntUntil > now) ensureHuntLifeSteal(player);
        else {
            AttributeInstance lifeSteal = lifeStealAttribute(player);
            if (lifeSteal != null) lifeSteal.removeModifier(HUNT_LIFESTEAL_ID);
        }

        if (state.bloodOverdriveUntil > now) ensureOverdriveLifeSteal(player);
        else {
            AttributeInstance lifeSteal = lifeStealAttribute(player);
            if (lifeSteal != null) lifeSteal.removeModifier(OVERDRIVE_LIFESTEAL_ID);
        }

        if (state.bloodExhaustedUntil > now) ensureExhaustionModifiers(player);
        else {
            AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
            AttributeInstance attackSpeed = player.getAttribute(Attributes.ATTACK_SPEED);
            AttributeInstance attackDamage = player.getAttribute(Attributes.ATTACK_DAMAGE);
            if (speed != null) speed.removeModifier(EXHAUSTED_SPEED_ID);
            if (attackSpeed != null) attackSpeed.removeModifier(EXHAUSTED_ATTACK_SPEED_ID);
            if (attackDamage != null) attackDamage.removeModifier(EXHAUSTED_ATTACK_DAMAGE_ID);
        }

        if (state.bloodHuntUntil <= now && state.bloodHuntUntil != 0L) endHunt(player, state);

        if (state.bloodFlightUntil > now) {
            if (!player.isFallFlying() && !player.onGround()) player.startFallFlying();
            player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, 30, 2, false, true, true));
            // Blood Wings is deliberately Elytra-free: the fall-flying state
            // supplies gliding while these red motes visibly stream off the player.
            if (player.tickCount % 2 == 0) spawnFlightParticles(player);
        } else if (state.bloodFlightUntil != 0L) {
            endFlight(player, state);
        }

        boolean bloodDamagePhase = state.bloodHuntUntil > now || state.bloodOverdriveUntil > now;
        if (bloodDamagePhase && now - state.bloodLastDegenerationTick >= 20L) {
            state.bloodLastDegenerationTick = now;
            long idleTicks = Math.max(0L, now - state.bloodLastEnemyHitTick);
            float damage = Math.min(6.0F, 1.0F + (idleTicks / 100L));
            // Blood Hunt and Overdrive are dangerous, but their degeneration
            // must never be the thing that kills the player. Leave one health
            // point and stop ticking once that floor is reached.
            if (player.isAlive()) {
                float safeDamage = Math.min(damage, Math.max(0.0F, player.getHealth() - 1.0F));
                if (safeDamage > 0.0F) player.hurt(player.damageSources().magic(), safeDamage);
            }
        }
        if (state.bloodOverdriveUntil != 0L && state.bloodOverdriveUntil <= now) {
            state.bloodOverdriveUntil = 0L;
            state.bloodExhaustedUntil = now + EXHAUSTED_TICKS;
        }
    }

    private static void spawnEyeParticle(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 side = new Vec3(-look.z, 0.0D, look.x);
        if (side.lengthSqr() < 0.001D) side = new Vec3(1.0D, 0.0D, 0.0D);
        side = side.normalize();
        Vec3 eye = player.getEyePosition().add(look.scale(0.10D)).add(side.scale(0.13D)).add(0.0D, -0.04D, 0.0D);
        level.sendParticles(RED_EYE, eye.x, eye.y, eye.z, 1, 0.015D, 0.015D, 0.015D, 0.0D);
    }

    private static void spawnFlightParticles(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 velocity = player.getDeltaMovement();
        Vec3 origin = player.position().add(0.0D, player.getBbHeight() * 0.55D, 0.0D);
        // Spread and speed make the particles visibly peel away instead of
        // forming a static cloud; the player's own motion carries the trail.
        level.sendParticles(RED_EYE, origin.x, origin.y, origin.z, 4,
                0.22D, 0.42D, 0.22D, 0.075D);
        if (velocity.lengthSqr() > 0.01D) {
            Vec3 trail = origin.subtract(velocity.normalize().scale(0.35D));
            level.sendParticles(RED_EYE, trail.x, trail.y, trail.z, 2,
                    0.10D, 0.18D, 0.10D, 0.04D);
        }
    }

    private static void endHunt(ServerPlayer player, PowderPouch state) {
        state.bloodHuntUntil = 0L;
        state.bloodLockedTarget = null;
    }

    private static void endFlight(ServerPlayer player, PowderPouch state) {
        state.bloodFlightUntil = 0L;
        if (player.isFallFlying()) player.stopFallFlying();
        removeOwnedEffect(player, MobEffects.DOLPHINS_GRACE, 2, FLIGHT_TICKS + 5);
        state.bloodFlightWasMayFly = false;
    }

    private static void removeOwnedEffect(ServerPlayer player,
                                          net.minecraft.world.effect.MobEffect effect,
                                          int amplifier, int maximumDuration) {
        MobEffectInstance instance = player.getEffect(effect);
        if (instance != null && instance.getAmplifier() == amplifier
                && instance.getDuration() <= maximumDuration) {
            player.removeEffect(effect);
        }
    }

    @SubscribeEvent
    public static void onEnemyHit(LivingHurtEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)
                || event.getEntity() == player || event.getAmount() <= 0.0F) return;
        PowderPouch state = pouch(player);
        if (state == null) return;
        long now = serverTime(player);
        if (state.bloodHuntUntil > now || state.bloodOverdriveUntil > now) {
            state.bloodLastEnemyHitTick = now;
            // AttributesLib applies life_steal in its post-damage hook. Permit
            // that heal through the Hunt natural-regeneration gate.
            state.bloodAllowLifestealUntil = now + 2L;
        }
    }

    @SubscribeEvent
    public static void onNaturalHeal(LivingHealEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PowderPouch state = pouch(player);
        long now = serverTime(player);
        if (state == null || (state.bloodHuntUntil <= now && state.bloodOverdriveUntil <= now
                && state.bloodExhaustedUntil <= now)) return;
        if (state != null && state.bloodAllowLifestealUntil >= now) {
            state.bloodAllowLifestealUntil = 0L;
            return;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player && isHuntActive(player)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && isHuntActive(player)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer player && isHuntActive(player)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getEntity() instanceof ServerPlayer player && isHuntActive(player)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && isHuntActive(player)) event.setCanceled(true);
    }

    private static void message(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.RED), true);
    }

    private BloodAbilities() {}
}
