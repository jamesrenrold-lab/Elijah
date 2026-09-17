package com.jamesrenrold.elijah;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerXpEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid = ElijahPirate.MOD_ID)
public final class PirateAbilities {
    public static final int DIRTY_COOLDOWN_TICKS = 18 * 20;
    public static final int DIRTY_SLOWNESS_TICKS = 2 * 20;
    private static final java.util.UUID FRAILTY_ARMOR_ID = java.util.UUID.fromString("9e11d2a4-16e1-4dd7-9f7e-4c4c3a6e0a10");
    private static final java.util.UUID FRAILTY_HEALTH_ID = java.util.UUID.fromString("d9b7dc7d-386f-47a3-9d73-4ed0849be7ee");
    private static final java.util.UUID LAND_LEGS_ID = java.util.UUID.fromString("54de0d69-2e24-4d71-a7d8-7f8c3c7b0b36");
    private static final java.util.UUID SEA_SWIM_ID = java.util.UUID.fromString("9ab2d3df-a6f6-45ec-955f-1aa9f61e9a53");

    public static int armDirtyTactics(CommandSourceStack source) throws CommandSyntaxException {
        return armDirtyTactics(source.getPlayerOrException());
    }

    public static int armDirtyTactics(ServerPlayer player) {
        if (!ElijahPirate.isPirate(player)) return 0;
        if (!player.isAlive() || player.isSpectator() || BloodAbilities.isHuntActive(player)) return 0;
        PowderPouch state = ElijahPirate.state(player);
        if (state.dirtyTacticsArmed) {
            message(player, "Dirty Tactics is already ready for your next melee hit.");
            return 0;
        }
        long now = player.serverLevel().getServer().overworld().getGameTime();
        if (now < state.dirtyTacticsReadyAt) {
            long seconds = (state.dirtyTacticsReadyAt - now + 19) / 20;
            message(player, "Dirty Tactics: " + seconds + "s remaining");
            return 0;
        }
        state.dirtyTacticsArmed = true;
        message(player, "Dirty Tactics ready — land your next melee hit!");
        return 1;
    }

    /** Records the latest mob involved in the pirate's combat, for crew targeting. */
    public static void rememberCombatTarget(ServerPlayer player, LivingEntity target) {
        if (target == null || target == player || target instanceof ServerPlayer) return;
        PowderPouch state = ElijahPirate.state(player);
        state.lastCombatTarget = target.getUUID();
        state.lastCombatTargetTick = player.serverLevel().getGameTime();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerAttacked(LivingAttackEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer attacker
                && event.getEntity() != attacker && event.getEntity().isAlive()) {
            // This early event also catches Epic Fight's attack path when the
            // normal vanilla hurt event is bypassed or reduced to zero damage.
            rememberCombatTarget(attacker, event.getEntity());
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.isAlive()) return;
        Entity attacker = event.getSource().getEntity();
        if (attacker instanceof LivingEntity living && attacker != player) rememberCombatTarget(player, living);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onMeleeHit(LivingHurtEvent event) {
        // This event runs after the normal hit/shield checks, but before absorption,
        // so an actual melee hit can debuff a shielded-by-absorption target too.
        if (event.getAmount() <= 0 || !(event.getSource().getEntity() instanceof ServerPlayer player)
                || event.getSource().getDirectEntity() != player
                || !event.getSource().is(DamageTypes.PLAYER_ATTACK)
                || !player.isAlive() || player.isSpectator()) return;
        LivingEntity target = event.getEntity();
        if (target == player || !target.isAlive()) return;
        rememberCombatTarget(player, target);
        PowderPouch state = ElijahPirate.state(player);
        if (!state.dirtyTacticsArmed) return;
        // Consume before applying anything: one target per activation, including sweep attacks.
        state.dirtyTacticsArmed = false;
        state.dirtyTacticsReadyAt = player.serverLevel().getServer().overworld().getGameTime() + DIRTY_COOLDOWN_TICKS;
        event.setAmount(event.getAmount() + 3.0F);
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, DIRTY_SLOWNESS_TICKS, 2), player);
        player.serverLevel().playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.SKELETON_DEATH, SoundSource.PLAYERS, 1.0F, 1.0F);
        message(player, "Dirty Tactics! 18s cooldown");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerDamagesMob(LivingHurtEvent event) {
        if (event.getAmount() <= 0 || !(event.getSource().getEntity() instanceof ServerPlayer player)
                || !player.isAlive() || player.isSpectator()) return;
        rememberCombatTarget(player, event.getEntity());
    }

    @SubscribeEvent
    public static void onPirateTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)
                || !ElijahPirate.isPirate(player)) return;
        addModifier(player.getAttribute(Attributes.ARMOR), FRAILTY_ARMOR_ID,
                "Pirate Frailty armor", -0.15D, AttributeModifier.Operation.MULTIPLY_TOTAL);
        AttributeInstance health = player.getAttribute(Attributes.MAX_HEALTH);
        addModifier(health, FRAILTY_HEALTH_ID, "Pirate Frailty health", -4.0D,
                AttributeModifier.Operation.ADDITION);
        if (health != null && player.getHealth() > health.getValue()) {
            player.setHealth((float) health.getValue());
        }

        if (player.getFluidHeight(FluidTags.WATER) > 0.0F) {
            removeModifier(player.getAttribute(Attributes.MOVEMENT_SPEED), LAND_LEGS_ID);
        } else {
            addModifier(player.getAttribute(Attributes.MOVEMENT_SPEED), LAND_LEGS_ID,
                    "Land Legs", -0.05D, AttributeModifier.Operation.MULTIPLY_TOTAL);
        }

        Attribute swimAttribute = ForgeRegistries.ATTRIBUTES.getValue(
                new ResourceLocation("forge", "swim_speed"));
        addModifier(swimAttribute == null ? null : player.getAttribute(swimAttribute), SEA_SWIM_ID,
                "Wisdom of the Sea", 0.25D, AttributeModifier.Operation.MULTIPLY_TOTAL);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFallDamage(LivingHurtEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && ElijahPirate.isPirate(player)
                && event.getSource().is(DamageTypeTags.IS_FALL)) {
            event.setAmount(event.getAmount() * 0.15F);
        }
    }

    public static int setWisdom(CommandSourceStack source, boolean enabled) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ElijahPirate.state(player).wisdomOfTheSea = enabled;
        return 1;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExperience(PlayerXpEvent.XpChange event) {
        if (event.getAmount() <= 0 || !(event.getEntity() instanceof ServerPlayer player)) return;
        PowderPouch state = ElijahPirate.state(player);
        if (!state.pirateOrigin) return;
        ExperienceBonus.Result bonus = ExperienceBonus.apply(event.getAmount(), state.xpBonusRemainder);
        event.setAmount(bonus.amount());
        state.xpBonusRemainder = bonus.remainder();
    }

    private static void message(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.GOLD), true);
    }

    private static void addModifier(AttributeInstance instance, java.util.UUID id, String name,
                                    double value, AttributeModifier.Operation operation) {
        if (instance != null && instance.getModifier(id) == null) {
            instance.addTransientModifier(new AttributeModifier(id, name, value, operation));
        }
    }

    private static void removeModifier(AttributeInstance instance, java.util.UUID id) {
        if (instance != null) instance.removeModifier(id);
    }

    private PirateAbilities() {}
}
