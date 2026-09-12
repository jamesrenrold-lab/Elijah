package com.jamesrenrold.elijah;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.network.NetworkHooks;

public final class FlintlockBall extends ThrowableProjectile {
    private static final EntityDataAccessor<Boolean> CANNONBALL_DATA =
            SynchedEntityData.defineId(FlintlockBall.class, EntityDataSerializers.BOOLEAN);
    private static final ResourceKey<DamageType> DAMAGE_TYPE = ResourceKey.create(
            Registries.DAMAGE_TYPE, new ResourceLocation(ElijahPirate.MOD_ID, "flintlock"));
    private float shotDamage = 3.0F;
    private int effectTicks = 20;
    private boolean cannonball;
    private float blastRadius;
    private boolean detonated;

    public FlintlockBall(EntityType<? extends FlintlockBall> type, Level level) {
        super(type, level);
    }

    public FlintlockBall(Level level, LivingEntity owner, float damage, int duration) {
        super(ElijahPirate.FLINTLOCK_BALL.get(), owner, level);
        this.shotDamage = damage;
        this.effectTicks = duration;
    }

    public static FlintlockBall cannonball(Level level, LivingEntity owner, float damage, float blastRadius) {
        FlintlockBall ball = new FlintlockBall(level, owner, damage, 0);
        ball.cannonball = true;
        ball.blastRadius = blastRadius;
        ball.entityData.set(CANNONBALL_DATA, true);
        return ball;
    }

    public boolean isCannonball() { return cannonball || entityData.get(CANNONBALL_DATA); }

    @Override
    protected void defineSynchedData() {
        entityData.define(CANNONBALL_DATA, false);
    }

    @Override
    protected float getGravity() { return 0.008F; }

    @Override
    protected boolean canHitEntity(Entity target) {
        if (!super.canHitEntity(target)) return false;
        if (getOwner() instanceof Player shooter && target instanceof Player victim) {
            return shooter.canHarmPlayer(victim);
        }
        return true;
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        if (level().isClientSide) return;
        Entity target = hit.getEntity();
        DamageSource source = new DamageSource(
                level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(DAMAGE_TYPE), this, getOwner());
        // Respect shields, invulnerability and other mods cancelling damage.
        if (target.hurt(source, shotDamage) && target instanceof LivingEntity living && !cannonball) {
            living.addEffect(new MobEffectInstance(MobEffects.DARKNESS, effectTicks, 0), getOwner());
            living.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, effectTicks, 0), getOwner());
            living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, effectTicks, 0), getOwner());
        }
        if (cannonball) detonate();
    }

    @Override
    protected void onHit(HitResult hit) {
        super.onHit(hit);
        if (level() instanceof ServerLevel server) {
            if (cannonball) detonate();
            server.sendParticles(ParticleTypes.SMOKE, getX(), getY(), getZ(), 4, 0.08, 0.08, 0.08, 0.01);
            discard();
        }
    }

    private void detonate() {
        if (detonated || !(level() instanceof ServerLevel server)) return;
        detonated = true;
        server.explode(getOwner(), getX(), getY(), getZ(), blastRadius,
                Level.ExplosionInteraction.NONE);
        server.sendParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY(), getZ(), 1,
                0.0D, 0.0D, 0.0D, 0.0D);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && tickCount >= 30) discard();
        if (level().isClientSide && tickCount % 2 == 0) {
            level().addParticle(ParticleTypes.SMOKE, getX(), getY(), getZ(), 0, 0, 0);
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("ShotDamage", shotDamage);
        tag.putInt("EffectTicks", effectTicks);
        tag.putBoolean("Cannonball", cannonball);
        tag.putFloat("BlastRadius", blastRadius);
        tag.putInt("Life", tickCount);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        shotDamage = tag.getFloat("ShotDamage");
        effectTicks = tag.contains("EffectTicks") ? tag.getInt("EffectTicks") : 20;
        cannonball = tag.getBoolean("Cannonball");
        blastRadius = tag.contains("BlastRadius") ? tag.getFloat("BlastRadius") : 0.0F;
        entityData.set(CANNONBALL_DATA, cannonball);
        tickCount = tag.getInt("Life");
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
