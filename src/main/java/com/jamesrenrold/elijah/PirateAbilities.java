package com.jamesrenrold.elijah;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerXpEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ElijahPirate.MOD_ID)
public final class PirateAbilities {
    public static final int DIRTY_COOLDOWN_TICKS = 18 * 20;
    public static final int DIRTY_SLOWNESS_TICKS = 2 * 20;

    public static int armDirtyTactics(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!player.isAlive() || player.isSpectator()) return 0;
        PowderPouch state = player.getCapability(PowderPouch.CAPABILITY).orElse(null);
        if (state == null) return 0;
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
        if (target == null || target == player || !(target instanceof net.minecraft.world.entity.Mob)) return;
        player.getCapability(PowderPouch.CAPABILITY).ifPresent(state -> {
            state.lastCombatTarget = target.getUUID();
            state.lastCombatTargetTick = player.serverLevel().getGameTime();
        });
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
        PowderPouch state = player.getCapability(PowderPouch.CAPABILITY).orElse(null);
        if (state == null || !state.dirtyTacticsArmed) return;
        // Consume before applying anything: one target per activation, including sweep attacks.
        state.dirtyTacticsArmed = false;
        state.dirtyTacticsReadyAt = player.serverLevel().getServer().overworld().getGameTime() + DIRTY_COOLDOWN_TICKS;
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

    public static int setWisdom(CommandSourceStack source, boolean enabled) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        player.getCapability(PowderPouch.CAPABILITY).ifPresent(state -> state.wisdomOfTheSea = enabled);
        return 1;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExperience(PlayerXpEvent.XpChange event) {
        if (event.getAmount() <= 0 || !(event.getEntity() instanceof ServerPlayer player)) return;
        player.getCapability(PowderPouch.CAPABILITY).ifPresent(state -> {
            if (!state.wisdomOfTheSea) return;
            ExperienceBonus.Result bonus = ExperienceBonus.apply(event.getAmount(), state.xpBonusRemainder);
            event.setAmount(bonus.amount());
            state.xpBonusRemainder = bonus.remainder();
        });
    }

    private static void message(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.GOLD), true);
    }

    private PirateAbilities() {}
}
