package com.jamesrenrold.elijah;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.EnumSet;
import java.util.UUID;

/**
 * A short-lived helper owned by the pirate. It deliberately extends Zombie so
 * Epic Fight can use its biped mob patch and vanilla clients still have a safe
 * humanoid fallback when Epic Fight is absent.
 */
public final class UndeadCrewmate extends Zombie {
    private UUID ownerId;
    private int attackCooldown;

    public UndeadCrewmate(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCanPickUpLoot(false);
    }

    public static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder createAttributes() {
        return Zombie.createAttributes()
                .add(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED, 0.30D)
                .add(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Epic Fight installs its AnimatedAttackGoal from the mob-patch JSON.
        // On a plain Forge install, use the compact fallback goal below.
        if (!ModList.get().isLoaded("epicfight")) {
            this.goalSelector.addGoal(2, new CrewCombatGoal(this));
        }
    }

    @Override
    public boolean isSunSensitive() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public ServerPlayer getOwnerPlayer() {
        if (ownerId == null || !(level() instanceof ServerLevel server)) return null;
        return server.getServer().getPlayerList().getPlayer(ownerId);
    }

    public void equipHonshu() {
        Item honshu = resolveHonshu();
        ItemStack stack = new ItemStack(honshu);
        this.setItemInHand(InteractionHand.MAIN_HAND, stack);
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
    }

    private static Item resolveHonshu() {
        String configured = PirateConfig.GOLDEN_HONSHU_ITEM.get();
        String[] candidates = {
                configured,
                "dungeons_and_combat:golden_honshu",
                "dungeonsandcombat:golden_honshu",
                "dungeons_combat:golden_honshu",
                "dungeons_and_combat:honshu_gold",
                "dungeonsandcombat:honshu_gold"
        };
        for (String id : candidates) {
            if (id == null || id.isBlank()) continue;
            net.minecraft.resources.ResourceLocation key = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (key == null) continue;
            Item item = ForgeRegistries.ITEMS.getValue(key);
            if (item != null && item != Items.AIR) return item;
        }
        return Items.GOLDEN_SWORD;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        if (tickCount >= PirateConfig.CREW_LIFETIME_TICKS.get()) {
            discard();
            return;
        }

        ServerPlayer owner = getOwnerPlayer();
        if (owner == null || !owner.isAlive() || owner.isSpectator() || owner.level() != level()) {
            discard();
            return;
        }

        LivingEntity target = findNearestCombatTarget(owner);
        if (target == null || !target.isAlive() || isAlliedTo(target)) {
            setTarget(null);
        } else {
            setTarget(target);
        }
        if (attackCooldown > 0) attackCooldown--;
    }

    private LivingEntity findNearestCombatTarget(ServerPlayer owner) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        LivingEntity[] candidates = {owner.getLastHurtMob(), owner.getLastHurtByMob()};
        for (LivingEntity candidate : candidates) {
            if (isValidTarget(candidate, owner)) {
                double distance = owner.distanceToSqr(candidate);
                if (distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        final UUID[] rememberedId = new UUID[1];
        owner.getCapability(PowderPouch.CAPABILITY).ifPresent(state -> {
            rememberedId[0] = state.lastCombatTarget;
        });
        if (rememberedId[0] != null) {
            Entity entity = ((ServerLevel) level()).getEntity(rememberedId[0]);
            if (entity instanceof LivingEntity candidate && isValidTarget(candidate, owner)) {
                double distance = owner.distanceToSqr(candidate);
                if (distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private boolean isValidTarget(LivingEntity candidate, ServerPlayer owner) {
        return candidate != null && candidate != owner && candidate.isAlive()
                && candidate instanceof Mob && !candidate.isSpectator()
                && owner.distanceToSqr(candidate) <= 48.0D * 48.0D;
    }

    private static final class CrewCombatGoal extends Goal {
        private final UndeadCrewmate crew;

        private CrewCombatGoal(UndeadCrewmate crew) {
            this.crew = crew;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = crew.getTarget();
            return target != null && target.isAlive();
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void tick() {
            LivingEntity target = crew.getTarget();
            if (target == null || !target.isAlive()) return;
            crew.getLookControl().setLookAt(target, 30.0F, 30.0F);
            double range = PirateConfig.CREW_ATTACK_RANGE.get();
            if (crew.distanceToSqr(target) > range * range) {
                crew.getNavigation().moveTo(target, PirateConfig.CREW_MOVE_SPEED.get());
                return;
            }
            crew.getNavigation().stop();
            if (crew.attackCooldown <= 0) {
                crew.swing(InteractionHand.MAIN_HAND);
                crew.doHurtTarget(target);
                crew.attackCooldown = 20;
            }
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerId != null) tag.putUUID("Owner", ownerId);
        tag.putInt("AttackCooldown", attackCooldown);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        ownerId = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        attackCooldown = tag.getInt("AttackCooldown");
    }

    @Override
    public boolean isAlliedTo(Entity entity) {
        if (entity instanceof UndeadCrewmate other && ownerId != null && ownerId.equals(other.ownerId)) return true;
        return ownerId != null && ownerId.equals(entity.getUUID()) || super.isAlliedTo(entity);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

}
