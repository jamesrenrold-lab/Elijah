package com.jamesrenrold.elijah;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The pirate's island domain. The dimension itself is supplied by the data
 * files; this class builds the arena, moves the combatants, and owns the
 * temporary buffs and cannonball sure-hit effect.
 */
@Mod.EventBusSubscriber(modid = ElijahPirate.MOD_ID)
public final class DomainAbilities {
    public static final ResourceKey<Level> DOMAIN_DIMENSION = ResourceKey.create(
            Registries.DIMENSION, new ResourceLocation(ElijahPirate.MOD_ID, "drowned_domain"));

    private static final int DOMAIN_TICKS = 40 * 20;
    private static final int CANNON_DELAY_TICKS = 5 * 20;
    private static final int CANNON_INTERVAL_TICKS = 10;
    private static final double WATER_SPAWN_Y = 65.0D;
    private static final double BEACH_SPAWN_Y = 67.0D;
    private static final UUID DOMAIN_SPEED_ID = UUID.fromString("c01c5046-3b27-49c5-9384-95f1f1cdb5db");
    private static final UUID DOMAIN_LIFESTEAL_ID = UUID.fromString("6e39eb13-5420-4de8-bf2d-5895e1cbbd7c");
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Vector3f CANNON_DUST = new Vector3f(0.08F, 0.08F, 0.08F);
    private static boolean arenaGeometryMigrated;

    private DomainAbilities() {}

    public static int activate(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!player.isAlive() || player.isSpectator() || BloodAbilities.isHuntActive(player)) return 0;
        Session active = SESSIONS.get(player.getUUID());
        if (active != null) {
            int secondsLeft = Math.max(1,
                    (int) Math.ceil((active.endAt - active.domain.getGameTime()) / 20.0D));
            message(player, "Drowned Domain is already active: " + secondsLeft + "s remaining.");
            return 0;
        }

        int cooldown = ElijahPirate.getOriginResource(player, "elijah:domain_cooldown");
        if (cooldown > 0) {
            message(player, String.format("Drowned Domain cooldown: %.1fs remaining.", cooldown / 20.0D));
            return 0;
        }

        PowderPouch pouch = player.getCapability(PowderPouch.CAPABILITY).orElse(null);
        if (pouch == null) return 0;
        LivingEntity target = findTarget(player);
        if (target == null) {
            message(player, "Drowned Domain: look directly at a nearby hostile target.");
            return 0;
        }

        MinecraftServer server = player.getServer();
        if (server == null) return 0;
        ServerLevel domain = server.getLevel(DOMAIN_DIMENSION);
        if (domain == null) {
            message(player, "The Drowned Domain dimension is unavailable; reload the world once.");
            return 0;
        }
        if (!SESSIONS.isEmpty()) {
            message(player, "Another Drowned Domain is already active.");
            return 0;
        }

        buildArena(domain);
        ResourceKey<Level> playerOrigin = player.level().dimension();
        Vec3 playerPosition = player.position();
        float playerYaw = player.getYRot();
        float playerPitch = player.getXRot();
        ResourceKey<Level> targetOrigin = target.level().dimension();
        Vec3 targetPosition = target.position();
        float targetYaw = target.getYRot();
        float targetPitch = target.getXRot();
        boolean targetWasPersistent = target instanceof Mob mob && mob.isPersistenceRequired();

        // The target starts on the wide back of the sandy crescent while the
        // caster begins inside the lagoon, where the pirate's swim advantage
        // immediately matters.
        LivingEntity movedTarget = transferLivingEntity(target, domain, -28.0D, BEACH_SPAWN_Y, 0.0D,
                targetYaw, targetPitch, true);
        if (movedTarget == null) {
            message(player, "The target could not be pulled into the domain.");
            return 0;
        }

        player.teleportTo(domain, 8.0D, WATER_SPAWN_Y, 0.0D, playerYaw, playerPitch);
        movedTarget.setDeltaMovement(Vec3.ZERO);
        movedTarget.hurtMarked = true;
        forceTargetAggro(movedTarget, player);

        Session session = new Session(player.getUUID(), movedTarget.getUUID(), domain,
                playerOrigin, playerPosition, playerYaw, playerPitch,
                targetOrigin, targetPosition, targetYaw, targetPitch,
                targetWasPersistent, domain.getGameTime() + DOMAIN_TICKS);
        SESSIONS.put(player.getUUID(), session);
        // Start the visible cooldown only after the dimension, target transfer,
        // and session creation have all succeeded. A rejected cast never burns it.
        ElijahPirate.setOriginResource(player, "elijah:domain_cooldown", 100);
        applyDomainBuffs(player);
        domain.playSound(null, player.blockPosition(), SoundEvents.AMBIENT_UNDERWATER_ENTER,
                SoundSource.PLAYERS, 1.2F, 0.7F);
        message(player, "The Drowned Domain opens — the tide answers your call!");
        message(player, "Domain cooldown: 5s on the visible Origins bar.");
        return 1;
    }

    /**
     * Recreates the target from its complete NBT snapshot in the destination.
     * This is the same transfer strategy used by the Echo domain: it works for
     * modded mobs that override changeDimension and return null, preserves
     * equipment/attributes/AI, and restores the source if construction fails.
     */
    private static LivingEntity transferLivingEntity(LivingEntity target, ServerLevel destination,
                                                      double x, double y, double z,
                                                      float yaw, float pitch,
                                                      boolean persistenceRequired) {
        if (target instanceof ServerPlayer || destination == null
                || !(target.level() instanceof ServerLevel) || target.isRemoved()) return null;

        UUID id = target.getUUID();
        float originalYaw = target.getYRot();
        float originalPitch = target.getXRot();
        CompoundTag snapshot = new CompoundTag();
        // saveAsPassenger writes the entity's `id` field, which
        // EntityType.loadEntityRecursive needs to reconstruct modded mobs.
        if (!target.saveAsPassenger(snapshot)) return null;
        snapshot.putUUID("UUID", id);
        if (target instanceof Mob) snapshot.putBoolean("PersistenceRequired", persistenceRequired);

        Entity recreated = EntityType.loadEntityRecursive(snapshot, destination, entity -> {
            entity.setUUID(id);
            entity.moveTo(x, y, z, yaw, pitch);
            entity.setDeltaMovement(Vec3.ZERO);
            return entity;
        });
        if (recreated instanceof LivingEntity moved && destination.addFreshEntity(recreated)) {
            // Only remove the source after the destination entity is live.
            target.discard();
            return moved;
        }
        return null;
    }

    /** Ends a session when Origins removes the power or the player unloads it. */
    public static void clearTransient(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session != null) endSession(session, player.getServer(), true);
        removeDomainBuffs(player);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null || SESSIONS.isEmpty()) return;
        List<Session> sessions = new ArrayList<>(SESSIONS.values());
        for (Session session : sessions) {
            ServerPlayer owner = server.getPlayerList().getPlayer(session.ownerId);
            if (owner == null || !owner.isAlive() || owner.level() != session.domain) {
                SESSIONS.remove(session.ownerId);
                // The target must never be stranded in the private dimension
                // just because the caster died, disconnected, or was moved.
                endSession(session, server, true);
                continue;
            }
            long now = session.domain.getGameTime();
            if (now >= session.endAt) {
                SESSIONS.remove(session.ownerId);
                endSession(session, server, true);
                continue;
            }
            applyDomainBuffs(owner);
            Entity entity = session.domain.getEntity(session.targetId);
            if (!(entity instanceof LivingEntity target) || !target.isAlive()) {
                SESSIONS.remove(session.ownerId);
                endSession(session, server, true);
                continue;
            }
            forceTargetAggro(target, owner);
            int secondsLeft = Math.max(1, (int) Math.ceil((session.endAt - now) / 20.0D));
            if (secondsLeft != session.lastDisplayedSecond) {
                session.lastDisplayedSecond = secondsLeft;
                message(owner, "Drowned Domain: " + secondsLeft + "s remaining");
            }
            if (now >= session.startedAt + CANNON_DELAY_TICKS
                    && now - session.lastCannonball >= CANNON_INTERVAL_TICKS) {
                session.lastCannonball = now;
                fireCannonball(session, owner, target);
            }
        }
    }

    /** Keeps transferred mobs loaded and hostile to the caster for the full session. */
    private static void forceTargetAggro(LivingEntity target, ServerPlayer owner) {
        if (!(target instanceof Mob mob)) return;
        mob.setPersistenceRequired();
        mob.setTarget(owner);
        mob.setAggressive(true);
        mob.setLastHurtByMob(owner);
        mob.getLookControl().setLookAt(owner, 30.0F, 30.0F);
        if (mob.distanceToSqr(owner) > 2.25D) {
            mob.getNavigation().moveTo(owner, 1.35D);
        }
    }

    private static LivingEntity findTarget(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 direction = player.getLookAngle().normalize();
        Vec3 maximum = eye.add(direction.scale(40.0D));
        HitResult blockHit = player.level().clip(new ClipContext(eye, maximum,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double sightRange = blockHit.getType() == HitResult.Type.MISS
                ? 40.0D : eye.distanceTo(blockHit.getLocation());
        Vec3 visibleEnd = eye.add(direction.scale(sightRange));
        AABB area = player.getBoundingBox().expandTowards(direction.scale(40.0D)).inflate(2.0D);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Mob mob : player.serverLevel().getEntitiesOfClass(Mob.class, area,
                candidate -> candidate.isAlive() && !(candidate instanceof UndeadCrewmate)
                        && !candidate.isAlliedTo(player))) {
            Optional<Vec3> hit = mob.getBoundingBox().inflate(0.25D).clip(eye, visibleEnd);
            if (hit.isPresent()) {
                double distance = eye.distanceToSqr(hit.get());
                if (distance < bestDistance) {
                    best = mob;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static LivingEntity findTargetFallback(ServerPlayer player) {
        AABB area = player.getBoundingBox().inflate(40.0D);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Mob mob : player.serverLevel().getEntitiesOfClass(Mob.class, area,
                candidate -> candidate.isAlive() && !(candidate instanceof UndeadCrewmate)
                        && !candidate.isAlliedTo(player))) {
            double distance = player.distanceToSqr(mob);
            if (distance < bestDistance) {
                best = mob;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static void fireCannonball(Session session, ServerPlayer owner, LivingEntity target) {
        int shot = session.cannonIndex++;
        // Deliberately scatter impacts around the target instead of putting
        // every shell directly through its centre. A downward trace finds the
        // real sand/lagoon floor so shots still detonate when the target swims.
        double scatterAngle = shot * 2.399963229728653D;
        double scatterRadius = 1.25D + (shot % 5) * 0.55D;
        double impactX = Math.max(-36.0D, Math.min(36.0D,
                target.getX() + Math.cos(scatterAngle) * scatterRadius));
        double impactZ = Math.max(-36.0D, Math.min(36.0D,
                target.getZ() + Math.sin(scatterAngle) * scatterRadius));
        Vec3 groundTraceStart = new Vec3(impactX, Math.max(78.0D, target.getY() + 8.0D), impactZ);
        Vec3 groundTraceEnd = new Vec3(impactX, 61.0D, impactZ);
        HitResult groundHit = session.domain.clip(new ClipContext(groundTraceStart, groundTraceEnd,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
        Vec3 aim = groundHit.getType() == HitResult.Type.MISS
                ? new Vec3(impactX, target.getY() + 0.1D, impactZ)
                : groundHit.getLocation().add(0.0D, 0.06D, 0.0D);

        // Alternate between the four invisible arena walls. These launch
        // points line up with the four ships outside the barrier, making the
        // barrage read as broadside fire rather than projectiles appearing in
        // the sky. Spawn just inside the barrier so it cannot intercept them.
        int wall = shot & 3;
        double lateralBase = wall < 2 ? aim.z : aim.x;
        double lateral = Math.max(-28.0D, Math.min(28.0D,
                lateralBase + ((shot % 5) - 2) * 1.5D));
        double firingY = Math.max(70.0D, aim.y + 3.5D);
        Vec3 origin = switch (wall) {
            case 0 -> new Vec3(-39.0D, firingY, lateral);
            case 1 -> new Vec3(39.0D, firingY, lateral);
            case 2 -> new Vec3(lateral, firingY, -39.0D);
            default -> new Vec3(lateral, firingY, 39.0D);
        };
        Vec3 direction = aim.subtract(origin).normalize();

        int curse = Math.max(0, Math.min(100,
                ElijahPirate.getOriginResource(owner, "elijah:blood_resource")));
        float attackDamage = (float) (owner.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.55D);
        float curseDamage = (curse / 10) * 1.5F;
        float damage = Math.max(1.0F, attackDamage + curseDamage);
        FlintlockBall cannonball = FlintlockBall.cannonball(session.domain, owner, damage, 4.0F);
        cannonball.setPos(origin.x, origin.y, origin.z);
        cannonball.setNoGravity(true);
        cannonball.shoot(direction.x, direction.y, direction.z, 4.6F, 0.0F);
        if (!session.domain.addFreshEntity(cannonball)) {
            message(owner, "Drowned Domain cannon failed to launch; retrying next volley.");
            return;
        }
        session.domain.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE,
                origin.x, origin.y, origin.z, 7, 0.18D, 0.18D, 0.18D, 0.035D);
        session.domain.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME,
                origin.x, origin.y, origin.z, 3, 0.08D, 0.08D, 0.08D, 0.02D);
        // A compact warning ring gives the target a readable tell without
        // filling the screen with explosion particles.
        for (int point = 0; point < 12; point++) {
            double angle = Math.PI * 2.0D * point / 12.0D;
            session.domain.sendParticles(new net.minecraft.core.particles.DustParticleOptions(
                            new Vector3f(0.75F, 0.12F, 0.04F), 0.65F),
                    aim.x + Math.cos(angle) * 1.25D, aim.y + 0.08D,
                    aim.z + Math.sin(angle) * 1.25D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        // Loud enough to carry from the wall/ship to the entire arena.
        session.domain.playSound(null, origin.x, origin.y, origin.z, SoundEvents.FIREWORK_ROCKET_BLAST,
                SoundSource.HOSTILE, 4.0F, 0.55F);
    }

    private static void applyDomainBuffs(ServerPlayer player) {
        addModifier(player.getAttribute(Attributes.MOVEMENT_SPEED), DOMAIN_SPEED_ID,
                "Drowned Domain movement speed", 0.50D);
        addModifier(lifeStealAttribute(player), DOMAIN_LIFESTEAL_ID,
                "Drowned Domain life steal", 0.50D, AttributeModifier.Operation.ADDITION);
        player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, 30, 2, false, true, true));
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 30, 1, false, true, true));
    }

    private static void removeDomainBuffs(ServerPlayer player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(DOMAIN_SPEED_ID);
        AttributeInstance lifeSteal = lifeStealAttribute(player);
        if (lifeSteal != null) lifeSteal.removeModifier(DOMAIN_LIFESTEAL_ID);
        MobEffectInstance regen = player.getEffect(MobEffects.REGENERATION);
        if (regen != null && regen.getDuration() <= 35) player.removeEffect(MobEffects.REGENERATION);
        MobEffectInstance dolphins = player.getEffect(MobEffects.DOLPHINS_GRACE);
        if (dolphins != null && dolphins.getDuration() <= 35) player.removeEffect(MobEffects.DOLPHINS_GRACE);
    }

    private static AttributeInstance lifeStealAttribute(ServerPlayer player) {
        net.minecraft.world.entity.ai.attributes.Attribute attribute =
                ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation("attributeslib", "life_steal"));
        return attribute == null ? null : player.getAttribute(attribute);
    }

    private static void addModifier(AttributeInstance instance, UUID id, String name, double value) {
        addModifier(instance, id, name, value, AttributeModifier.Operation.MULTIPLY_TOTAL);
    }

    private static void addModifier(AttributeInstance instance, UUID id, String name, double value,
                                     AttributeModifier.Operation operation) {
        if (instance != null && instance.getModifier(id) == null) {
            instance.addTransientModifier(new AttributeModifier(id, name, value, operation));
        }
    }

    private static void endSession(Session session, MinecraftServer server, boolean returnCombatants) {
        if (server == null) return;
        ServerPlayer owner = server.getPlayerList().getPlayer(session.ownerId);
        if (owner != null) {
            removeDomainBuffs(owner);
            if (owner.isAlive()) {
                teleportPlayerBack(owner, server, session);
                message(owner, "Drowned Domain closed — the power is ready.");
            }
        }
        if (!returnCombatants) return;
        Entity entity = session.domain.getEntity(session.targetId);
        if (entity instanceof LivingEntity target && target.isAlive()) {
            ServerLevel origin = server.getLevel(session.targetOrigin);
            if (origin != null) {
                transferLivingEntity(target, origin, session.targetPosition.x, session.targetPosition.y,
                        session.targetPosition.z, session.targetYaw, session.targetPitch,
                        session.targetWasPersistent);
            }
        }
    }

    private static void teleportPlayerBack(ServerPlayer owner, MinecraftServer server, Session session) {
        ServerLevel origin = server.getLevel(session.playerOrigin);
        if (origin != null) owner.teleportTo(origin, session.playerPosition.x, session.playerPosition.y,
                session.playerPosition.z, session.playerYaw, session.playerPitch);
    }

    private static void buildArena(ServerLevel level) {
        final int oceanSurface = 63;
        final int beachTop = 66;
        clearLegacyArenaGeometry(level);

        // A full barrier foundation supports every sand block without gravity
        // updates. The crescent itself rises two blocks above the lagoon.
        for (int x = -42; x <= 42; x++) {
            for (int z = -42; z <= 42; z++) set(level, x, 62, z, Blocks.BARRIER);
        }
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                set(level, x, oceanSurface, z, Blocks.SAND);
                double outer = square((x + 4.0D) / 36.0D) + square(z / 32.0D);
                double inner = square((x - 8.0D) / 33.0D) + square(z / 26.0D);
                boolean sandyCrescent = outer <= 1.0D && inner >= 1.0D;
                if (sandyCrescent) {
                    set(level, x, 64, z, Blocks.SANDSTONE);
                    set(level, x, 65, z, Blocks.SAND);
                    set(level, x, beachTop, z, Blocks.SAND);
                    set(level, x, 67, z, Blocks.AIR);
                } else {
                    set(level, x, 64, z, Blocks.WATER);
                    set(level, x, 65, z, Blocks.WATER);
                    set(level, x, 66, z, Blocks.AIR);
                    set(level, x, 67, z, Blocks.AIR);
                }
            }
        }

        int[][] dunes = {{-31, -10}, {-30, 10}, {-24, -22}, {-23, 22}, {-10, -29}, {-9, 29}};
        for (int i = 0; i < dunes.length; i++) {
            buildSandDune(level, dunes[i][0], dunes[i][1], 4 + i % 2, 3 + (i + 1) % 2, 67, 2 + i % 3);
        }
        buildPalm(level, -29, 67, 5);
        buildBarriers(level);

        // Every ship lies tangentially around the arena, presenting its long
        // inward broadside rather than its bow to the combatants.
        buildShip(level, 0, -76, true);
        buildShip(level, 0, 76, true);
        buildShip(level, -76, 0, false);
        buildShip(level, 76, 0, false);
        buildDistantIslands(level);
    }

    /** Removes every known legacy arena/ship footprint once after a restart. */
    private static void clearLegacyArenaGeometry(ServerLevel level) {
        if (arenaGeometryMigrated) return;
        arenaGeometryMigrated = true;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -115; x <= 115; x++) {
            for (int z = -115; z <= 115; z++) {
                boolean arena = Math.abs(x) <= 42 && Math.abs(z) <= 42;
                boolean fleetLane = (Math.abs(x) >= 43 && Math.abs(x) <= 115 && Math.abs(z) <= 42)
                        || (Math.abs(z) >= 43 && Math.abs(z) <= 115 && Math.abs(x) <= 42);
                if (!arena && !fleetLane) continue;
                for (int y = 64; y <= 105; y++) {
                    pos.set(x, y, z);
                    if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                }
                if (fleetLane) {
                    set(level, x, 62, z, Blocks.WATER);
                    set(level, x, 63, z, Blocks.WATER);
                }
            }
        }
    }

    private static void buildSandDune(ServerLevel level, int cx, int cz, int radiusX,
                                      int radiusZ, int baseY, int height) {
        for (int layer = 0; layer < height; layer++) {
            int rx = Math.max(1, radiusX - layer);
            int rz = Math.max(1, radiusZ - layer);
            for (int dx = -rx; dx <= rx; dx++) {
                for (int dz = -rz; dz <= rz; dz++) {
                    if (square(dx / (double) rx) + square(dz / (double) rz) <= 1.0D) {
                        set(level, cx + dx, baseY + layer, cz + dz, Blocks.SAND);
                    }
                }
            }
        }
    }

    private static void buildPalm(ServerLevel level, int x, int baseY, int z) {
        // Curved, leaning trunk instead of a vertical pole.
        int[] bendX = {0, 0, 0, 1, 1, 1, 2, 2, 3, 3, 3};
        int[] bendZ = {0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2};
        for (int y = 0; y < bendX.length; y++) {
            set(level, x + bendX[y], baseY + y, z + bendZ[y], Blocks.JUNGLE_LOG);
        }
        int topX = x + 3;
        int topY = baseY + 10;
        int topZ = z + 2;
        set(level, topX, topY + 1, topZ, Blocks.JUNGLE_LEAVES);

        // Eight long fronds droop at their tips, producing a recognizable
        // palm silhouette rather than a round deciduous canopy.
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] direction : directions) {
            for (int step = 0; step <= 6; step++) {
                int frondY = topY + (step <= 2 ? 1 : 0) - (step >= 5 ? step - 4 : 0);
                int fx = topX + direction[0] * step;
                int fz = topZ + direction[1] * step;
                set(level, fx, frondY, fz, Blocks.JUNGLE_LEAVES);
                if (step >= 2 && step <= 4) {
                    set(level, fx + direction[1], frondY, fz + direction[0], Blocks.JUNGLE_LEAVES);
                }
            }
        }
        set(level, topX + 1, topY - 1, topZ, Blocks.BROWN_WOOL);
        set(level, topX - 1, topY - 1, topZ, Blocks.BROWN_WOOL);
        set(level, topX, topY - 1, topZ + 1, Blocks.BROWN_WOOL);
    }

    private static void buildDistantIslands(ServerLevel level) {
        int[][] islands = {
                {0, -136, 12, 7, 5}, {65, -124, 9, 6, 4}, {120, -78, 11, 7, 5},
                {136, 0, 13, 8, 5}, {122, 72, 9, 6, 4}, {60, 126, 11, 7, 5},
                {0, 138, 13, 7, 5}, {-66, 124, 10, 6, 4}, {-121, 76, 12, 7, 5},
                {-137, 0, 11, 8, 5}, {-120, -76, 10, 6, 4}, {-62, -126, 12, 7, 5}
        };
        for (int[] island : islands) {
            buildSandDune(level, island[0], island[1], island[2], island[3], 64, island[4]);
            set(level, island[0], 64 + island[4], island[1], Blocks.SANDSTONE);
            set(level, island[0] + 1, 64 + island[4], island[1], Blocks.SANDSTONE);
        }
    }

    private static double square(double value) {
        return value * value;
    }

    private static void buildBarriers(ServerLevel level) {
        for (int y = 64; y <= 110; y++) {
            for (int n = -41; n <= 41; n++) {
                set(level, -41, y, n, Blocks.BARRIER);
                set(level, 41, y, n, Blocks.BARRIER);
                set(level, n, y, -41, Blocks.BARRIER);
                set(level, n, y, 41, Blocks.BARRIER);
            }
        }
    }

    private static void buildShip(ServerLevel level, int cx, int cz, boolean eastWest) {
        // A genuinely deep 3D hull: each vertical layer widens toward the
        // waterline, then supports an enclosed gun deck and full main deck.
        for (int along = -28; along <= 28; along++) {
            int taper = Math.max(0, Math.abs(along) - 17);
            int halfWidth = Math.max(3, 12 - (taper + 1) / 2);
            int[] insetByLayer = {7, 5, 3, 1, 0, 0, 0};
            for (int layer = 0; layer < insetByLayer.length; layer++) {
                int y = 62 + layer;
                int layerWidth = Math.max(1, halfWidth - insetByLayer[layer]);
                for (int across = -layerWidth; across <= layerWidth; across++) {
                    net.minecraft.world.level.block.Block block;
                    if (layer == 6) block = Blocks.SPRUCE_PLANKS;
                    else if (Math.abs(across) == layerWidth && (along & 3) == 0) block = Blocks.DARK_OAK_LOG;
                    else if (layer == 4 && Math.abs(across) == layerWidth) block = Blocks.STRIPPED_DARK_OAK_LOG;
                    else block = Blocks.DARK_OAK_PLANKS;
                    placeShipBlock(level, cx, cz, eastWest, along, across, y, block);
                }
            }
            placeShipBlock(level, cx, cz, eastWest, along, -halfWidth, 69, Blocks.DARK_OAK_FENCE);
            placeShipBlock(level, cx, cz, eastWest, along, halfWidth, 69, Blocks.DARK_OAK_FENCE);
        }

        int[] cannonAlong = {-23, -18, -13, -8, -3, 3, 8, 13, 18, 23};
        for (int along : cannonAlong) {
            for (int side : new int[]{-1, 1}) {
                placeShipBlock(level, cx, cz, eastWest, along, side * 12, 66, Blocks.POLISHED_BLACKSTONE);
                buildCannon(level, cx, cz, eastWest, side, along);
            }
        }

        // Two-storey sterncastle with side and rear cabin windows.
        for (int along = 15; along <= 27; along++) {
            int width = Math.max(5, 11 - Math.max(0, along - 21));
            for (int across = -width; across <= width; across++) {
                placeShipBlock(level, cx, cz, eastWest, along, across, 69, Blocks.SPRUCE_PLANKS);
                for (int y = 70; y <= 74; y++) {
                    if (Math.abs(across) >= width - 1 || along >= 25) {
                        boolean window = y == 72 && ((Math.abs(across) + along) % 4 == 0);
                        placeShipBlock(level, cx, cz, eastWest, along, across, y,
                                window ? Blocks.YELLOW_STAINED_GLASS : Blocks.DARK_OAK_PLANKS);
                    }
                }
                placeShipBlock(level, cx, cz, eastWest, along, across, 75, Blocks.SPRUCE_PLANKS);
                if (Math.abs(across) == width) {
                    placeShipBlock(level, cx, cz, eastWest, along, across, 76, Blocks.DARK_OAK_FENCE);
                }
            }
        }

        // Raised forecastle and decorated gold-tipped figurehead/bowsprit.
        for (int along = -27; along <= -17; along++) {
            int width = Math.max(4, 10 - Math.max(0, -along - 20));
            for (int across = -width; across <= width; across++) {
                placeShipBlock(level, cx, cz, eastWest, along, across, 69, Blocks.SPRUCE_PLANKS);
                if (Math.abs(across) == width) {
                    placeShipBlock(level, cx, cz, eastWest, along, across, 70, Blocks.DARK_OAK_FENCE);
                }
            }
        }
        for (int along = -37; along <= -24; along++) {
            placeShipBlock(level, cx, cz, eastWest, along, 0, 72,
                    along <= -35 ? Blocks.GOLD_BLOCK : Blocks.DARK_OAK_FENCE);
        }

        int[] mastAlong = {-17, 0, 16};
        for (int along : mastAlong) buildMast(level, cx, cz, eastWest, along);
        buildRigging(level, cx, cz, eastWest, mastAlong);
    }

    private static void buildMast(ServerLevel level, int cx, int cz, boolean eastWest, int along) {
        for (int y = 69; y <= 103; y++) {
            placeShipBlock(level, cx, cz, eastWest, along, 0, y, Blocks.DARK_OAK_LOG);
        }
        buildBillowedSail(level, cx, cz, eastWest, along, 77, 87, 12);
        buildBillowedSail(level, cx, cz, eastWest, along, 90, 98, 9);
        buildYard(level, cx, cz, eastWest, along, 77, 13);
        buildYard(level, cx, cz, eastWest, along, 87, 12);
        buildYard(level, cx, cz, eastWest, along, 90, 10);
        buildYard(level, cx, cz, eastWest, along, 98, 9);

        for (int across = -2; across <= 2; across++) {
            for (int depth = -1; depth <= 1; depth++) {
                int longitudinal = along + depth;
                placeShipBlock(level, cx, cz, eastWest, longitudinal, across, 100, Blocks.DARK_OAK_SLAB);
            }
        }
        placeShipBlock(level, cx, cz, eastWest, along, 0, 104, Blocks.RED_WOOL);
        placeShipBlock(level, cx, cz, eastWest, along + 1, 0, 104, Blocks.RED_WOOL);
        placeShipBlock(level, cx, cz, eastWest, along + 2, 0, 104, Blocks.WHITE_WOOL);
    }

    private static void buildBillowedSail(ServerLevel level, int cx, int cz, boolean eastWest,
                                          int mastAlong, int minY, int maxY, int maxWidth) {
        double midpoint = (minY + maxY) / 2.0D;
        double halfHeight = (maxY - minY) / 2.0D;
        for (int y = minY; y <= maxY; y++) {
            double vertical = 1.0D - Math.abs(y - midpoint) / (halfHeight + 1.0D);
            int width = Math.max(3, maxWidth - (int) Math.round((1.0D - vertical) * 4.0D));
            for (int across = -width; across <= width; across++) {
                double edge = 1.0D - Math.abs(across) / (double) (width + 1);
                int billow = Math.max(0, (int) Math.round(3.0D * vertical * edge));
                net.minecraft.world.level.block.Block sail = Math.abs(y - midpoint) <= 1.0D
                        ? Blocks.RED_WOOL : (((y + across) & 1) == 0
                        ? Blocks.WHITE_WOOL : Blocks.LIGHT_GRAY_WOOL);
                for (int depth = 0; depth <= billow; depth++) {
                    placeShipBlock(level, cx, cz, eastWest, mastAlong + depth, across, y, sail);
                }
            }
        }
    }

    private static void buildYard(ServerLevel level, int cx, int cz, boolean eastWest,
                                  int along, int y, int width) {
        for (int across = -width; across <= width; across++) {
            placeShipBlock(level, cx, cz, eastWest, along, across, y, Blocks.DARK_OAK_FENCE);
        }
    }

    private static void buildRigging(ServerLevel level, int cx, int cz, boolean eastWest, int[] masts) {
        for (int along = masts[0]; along <= masts[masts.length - 1]; along++) {
            placeShipBlock(level, cx, cz, eastWest, along, 0, 101, Blocks.CHAIN);
        }
        for (int mast : masts) {
            for (int side : new int[]{-1, 1}) {
                for (int step = 1; step <= 11; step++) {
                    int y = 100 - step * 2;
                    placeShipBlock(level, cx, cz, eastWest, mast, side * step, y, Blocks.CHAIN);
                }
            }
        }
        buildLongRigging(level, cx, cz, eastWest, masts[0], -30);
        buildLongRigging(level, cx, cz, eastWest, masts[2], 28);
    }

    private static void buildLongRigging(ServerLevel level, int cx, int cz, boolean eastWest,
                                         int startAlong, int endAlong) {
        int distance = Math.abs(endAlong - startAlong);
        for (int step = 0; step <= distance; step++) {
            double progress = step / (double) Math.max(1, distance);
            int along = (int) Math.round(startAlong + (endAlong - startAlong) * progress);
            int y = (int) Math.round(101 - 30 * progress);
            placeShipBlock(level, cx, cz, eastWest, along, 0, y, Blocks.CHAIN);
        }
    }

    private static void placeShipBlock(ServerLevel level, int cx, int cz, boolean eastWest,
                                       int along, int across, int y,
                                       net.minecraft.world.level.block.Block block) {
        int x = eastWest ? cx + along : cx + across;
        int z = eastWest ? cz + across : cz + along;
        set(level, x, y, z, block);
    }

    private static void buildCannon(ServerLevel level, int cx, int cz, boolean eastWest,
                                    int side, int along) {
        Direction facing = eastWest
                ? (side < 0 ? Direction.NORTH : Direction.SOUTH)
                : (side < 0 ? Direction.WEST : Direction.EAST);
        int x = eastWest ? cx + along : cx + side * 11;
        int z = eastWest ? cz + side * 11 : cz + along;
        int dx = facing.getStepX();
        int dz = facing.getStepZ();
        // Enclosed lower gun deck: the chamber sits inside the hull and the
        // barrel exits sideways through the Y=66 port below the main deck.
        setOptional(level, x, 65, z, "createbigcannons:cannon_carriage", facing);
        setOptional(level, x, 66, z, "createbigcannons:fixed_cannon_mount", facing);
        setOptional(level, x + dx, 66, z + dz, "createbigcannons:cast_iron_cannon_chamber", facing);
        setOptional(level, x + dx * 2, 66, z + dz * 2, "createbigcannons:cast_iron_cannon_barrel", facing);
        setOptional(level, x + dx * 3, 66, z + dz * 3, "createbigcannons:cast_iron_cannon_barrel", facing);
        setOptional(level, x + dx * 4, 66, z + dz * 4, "createbigcannons:cast_iron_cannon_end", facing);
    }

    private static void setOptional(ServerLevel level, int x, int y, int z, String id, Direction facing) {
        ResourceLocation key = new ResourceLocation(id);
        net.minecraft.world.level.block.Block block = BuiltInRegistries.BLOCK.get(key);
        if (block == Blocks.AIR || !key.equals(BuiltInRegistries.BLOCK.getKey(block))) {
            set(level, x, y, z, Blocks.POLISHED_BLACKSTONE);
            return;
        }
        BlockState state = block.defaultBlockState();
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        } else if (state.hasProperty(BlockStateProperties.FACING)) {
            state = state.setValue(BlockStateProperties.FACING, facing);
        }
        level.setBlock(new BlockPos(x, y, z), state, 2);
    }

    private static void set(ServerLevel level, int x, int y, int z, net.minecraft.world.level.block.Block block) {
        // Client update without neighbor updates: this keeps the two-deep
        // source-water section stable and avoids tens of thousands of fluid
        // and gravity updates while the arena is reconstructed.
        level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 2);
    }

    private static void message(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.GOLD), true);
    }

    private static final class Session {
        private final UUID ownerId;
        private final UUID targetId;
        private final ServerLevel domain;
        private final ResourceKey<Level> playerOrigin;
        private final Vec3 playerPosition;
        private final float playerYaw;
        private final float playerPitch;
        private final ResourceKey<Level> targetOrigin;
        private final Vec3 targetPosition;
        private final float targetYaw;
        private final float targetPitch;
        private final boolean targetWasPersistent;
        private final long startedAt;
        private final long endAt;
        private long lastCannonball;
        private int cannonIndex;
        private int lastDisplayedSecond = -1;

        private Session(UUID ownerId, UUID targetId, ServerLevel domain,
                        ResourceKey<Level> playerOrigin, Vec3 playerPosition, float playerYaw, float playerPitch,
                        ResourceKey<Level> targetOrigin, Vec3 targetPosition, float targetYaw, float targetPitch,
                        boolean targetWasPersistent, long endAt) {
            this.ownerId = ownerId;
            this.targetId = targetId;
            this.domain = domain;
            this.playerOrigin = playerOrigin;
            this.playerPosition = playerPosition;
            this.playerYaw = playerYaw;
            this.playerPitch = playerPitch;
            this.targetOrigin = targetOrigin;
            this.targetPosition = targetPosition;
            this.targetYaw = targetYaw;
            this.targetPitch = targetPitch;
            this.targetWasPersistent = targetWasPersistent;
            this.startedAt = domain.getGameTime();
            this.endAt = endAt;
            this.lastCannonball = this.startedAt;
        }
    }
}
