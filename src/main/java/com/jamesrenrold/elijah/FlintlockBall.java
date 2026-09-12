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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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
    private boolean hasGuaranteedImpact;
    private double impactX;
    private double impactY;
    private double impactZ;

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

    /** Detonates a domain shell at its traced block impact even if another mod disrupts collision. */
    public void setGuaranteedImpact(Vec3 impact) {
        hasGuaranteedImpact = true;
        impactX = impact.x;
        impactY = impact.y;
        impactZ = impact.z;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(CANNONBALL_DATA, false);
    }

    @Override
    protected float getGravity() { return 0.008F; }

    @Override
    protected boolean canHitEntity(Entity target) {
        if (!super.canHitEntity(target)) return false;
        if (getOwner() instanceof Player shooter && target instanceof UndeadCrewmate crew
                && shooter.getUUID().equals(crew.getOwnerId())) return false;
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
        if (!cannonball && target.hurt(source, shotDamage) && target instanceof LivingEntity living) {
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
        DamageSource source = new DamageSource(
                level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(DAMAGE_TYPE), this, getOwner());
        double radiusSquared = blastRadius * blastRadius;
        for (Entity entity : server.getEntities(this, getBoundingBox().inflate(blastRadius),
                candidate -> candidate instanceof LivingEntity living && living.isAlive() && candidate != getOwner())) {
            double distanceSquared = entity.distanceToSqr(getX(), getY(), getZ());
            if (distanceSquared > radiusSquared) continue;
            double falloff = 1.0D - Math.sqrt(distanceSquared) / blastRadius;
            float damage = (float) (shotDamage * (0.45D + 0.55D * falloff));
            if (entity.hurt(source, damage)) {
                Vec3 away = entity.position().subtract(position());
                if (away.lengthSqr() > 1.0E-4D) {
                    double resistance = entity instanceof LivingEntity living
                            ? living.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) : 0.0D;
                    double strength = 0.55D * falloff * Math.max(0.0D, 1.0D - resistance);
                    Vec3 push = away.normalize().scale(strength);
                    entity.push(push.x, Math.max(0.08D, 0.18D * falloff), push.z);
                    entity.hurtMarked = true;
                }
            }
        }
        // A low-packet smoke outline marks the exact four-block damage volume.
        // Earlier versions sent over 170 individual particle packets per hit.
        int groundPoints = 16;
        for (int point = 0; point < groundPoints; point++) {
            double angle = Math.PI * 2.0D * point / groundPoints;
            double px = getX() + Math.cos(angle) * blastRadius;
            double pz = getZ() + Math.sin(angle) * blastRadius;
            server.sendParticles(ParticleTypes.LARGE_SMOKE, px, getY() + 0.18D, pz, 1,
                    0.04D, 0.06D, 0.04D, 0.003D);
        }
        int shellPoints = 18;
        double goldenAngle = Math.PI * (3.0D - Math.sqrt(5.0D));
        for (int point = 0; point < shellPoints; point++) {
            double y = 1.0D - (2.0D * point + 1.0D) / shellPoints;
            double horizontal = Math.sqrt(Math.max(0.0D, 1.0D - y * y));
            double angle = goldenAngle * point;
            server.sendParticles(ParticleTypes.LARGE_SMOKE,
                    getX() + Math.cos(angle) * horizontal * blastRadius,
                    getY() + y * blastRadius,
                    getZ() + Math.sin(angle) * horizontal * blastRadius,
                    1, 0.035D, 0.035D, 0.035D, 0.002D);
        }
        server.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.25D, getZ(), 10,
                0.45D, 0.22D, 0.45D, 0.025D);
        server.sendParticles(ParticleTypes.SMOKE, getX(), getY() + 0.35D, getZ(), 16,
                1.25D, 0.65D, 1.25D, 0.012D);
        server.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE,
                SoundSource.HOSTILE, 0.45F, 0.85F);
    }

    @Override
    public void tick() {
        Vec3 previousPosition = position();
        super.tick();
        if (!level().isClientSide && !isRemoved() && isCannonball() && hasGuaranteedImpact) {
            Vec3 impact = new Vec3(impactX, impactY, impactZ);
            Vec3 travelled = position().subtract(previousPosition);
            double travelledSquared = travelled.lengthSqr();
            if (travelledSquared > 1.0E-6D) {
                double progress = Math.max(0.0D, Math.min(1.0D,
                        impact.subtract(previousPosition).dot(travelled) / travelledSquared));
                Vec3 closest = previousPosition.add(travelled.scale(progress));
                if (closest.distanceToSqr(impact) <= 0.64D) {
                    setPos(impact.x, impact.y, impact.z);
                    detonate();
                    discard();
                    return;
                }
            }
        }
        if (!level().isClientSide && isCannonball() && level() instanceof ServerLevel server
                && (tickCount & 1) == 0) {
            // Server-driven tracer particles make every volley visible even if
            // a client resource pack or renderer suppresses the black model.
            server.sendParticles(ParticleTypes.SMOKE, getX(), getY(), getZ(), 2,
                    0.06D, 0.06D, 0.06D, 0.01D);
            if ((tickCount & 3) == 0) {
                server.sendParticles(ParticleTypes.FLAME, getX(), getY(), getZ(), 1,
                        0.02D, 0.02D, 0.02D, 0.0D);
            }
        }
        // Domain cannonballs remain alive until they touch an entity or real
        // block. The longer safety timeout only prevents permanently orphaned
        // projectiles if another mod removes collision; ordinary flintlock
        // rounds keep their original short lifetime.
        if (!level().isClientSide && tickCount >= (isCannonball() ? 120 : 30)) discard();
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
        tag.putBoolean("HasGuaranteedImpact", hasGuaranteedImpact);
        if (hasGuaranteedImpact) {
            tag.putDouble("ImpactX", impactX);
            tag.putDouble("ImpactY", impactY);
            tag.putDouble("ImpactZ", impactZ);
        }
        tag.putInt("Life", tickCount);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        shotDamage = tag.getFloat("ShotDamage");
        effectTicks = tag.contains("EffectTicks") ? tag.getInt("EffectTicks") : 20;
        cannonball = tag.getBoolean("Cannonball");
        blastRadius = tag.contains("BlastRadius") ? tag.getFloat("BlastRadius") : 0.0F;
        hasGuaranteedImpact = tag.getBoolean("HasGuaranteedImpact");
        impactX = tag.getDouble("ImpactX");
        impactY = tag.getDouble("ImpactY");
        impactZ = tag.getDouble("ImpactZ");
        entityData.set(CANNONBALL_DATA, cannonball);
        tickCount = tag.getInt("Life");
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
