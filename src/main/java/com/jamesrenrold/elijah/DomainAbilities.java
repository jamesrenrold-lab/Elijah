package com.jamesrenrold.elijah;

import com.mojang.logging.LogUtils;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.ITeleporter;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The pirate's island domain. The dimension itself is supplied by the data
 * files; this class builds the arena, moves the combatants, and owns the
 * temporary buffs and cannonball sure-hit effect.
 */
@Mod.EventBusSubscriber(modid = ElijahPirate.MOD_ID)
public final class DomainAbilities {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceKey<Level> DOMAIN_DIMENSION = ResourceKey.create(
            Registries.DIMENSION, new ResourceLocation(ElijahPirate.MOD_ID, "drowned_domain"));

    private static final int DOMAIN_TICKS = 40 * 20;
    private static final int COOLDOWN_TICKS = 5 * 20;
    private static final int CANNON_DELAY_TICKS = 5 * 20;
    private static final int CANNON_INTERVAL_TICKS = 10;
    private static final double WATER_SPAWN_X = 8.0D;
    private static final double WATER_SPAWN_Y = 63.2D;
    private static final double BEACH_SPAWN_X = -34.0D;
    private static final double BEACH_SPAWN_Y = 67.0D;
    private static final UUID DOMAIN_SPEED_ID = UUID.fromString("c01c5046-3b27-49c5-9384-95f1f1cdb5db");
    private static final UUID DOMAIN_LIFESTEAL_ID = UUID.fromString("6e39eb13-5420-4de8-bf2d-5895e1cbbd7c");
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();
    private static final Map<UUID, Long> LIFECYCLE_GUARDS = new HashMap<>();
    private static final Set<UUID> PENDING_ACTIVATIONS = new HashSet<>();
    private static final BlockPos ARENA_MARKER = new BlockPos(0, 61, 0);
    private static final Vector3f CANNON_DUST = new Vector3f(0.08F, 0.08F, 0.08F);
    private static boolean arenaReady;

    private DomainAbilities() {}

    public static int activate(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!player.isAlive() || player.isSpectator() || BloodAbilities.isHuntActive(player)) return 0;
        if (player.getServer() == null) return 0;

        // Origins invokes the entity action before ActiveCooldownPower records
        // the use. Teleporting dimensions inside that callback lets Connector
        // replace the power instance before it is committed, which made the
        // replacement unusable until death. Defer the transfer one server tick
        // so the originating power always completes first.
        PENDING_ACTIVATIONS.add(player.getUUID());
        return 1;
    }

    private static void activateNow(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || BloodAbilities.isHuntActive(player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;

        // Domain availability is owned entirely here. Origins only debounces
        // the key for one tick; the visible five-second cooldown below is the
        // sole gameplay cooldown, so a stale resource cannot lock the power.
        recoverFinishedSessions(server);
        Session active = SESSIONS.get(player.getUUID());
        if (active != null) {
            int secondsLeft = Math.max(1,
                    (int) Math.ceil((active.endAt - server.overworld().getGameTime()) / 20.0D));
            message(player, "Drowned Domain is already active: " + secondsLeft + "s remaining.");
            return;
        }
        long serverTime = server.overworld().getGameTime();
        long cooldownUntil = COOLDOWNS.getOrDefault(player.getUUID(), 0L);
        if (cooldownUntil > serverTime) {
            int secondsLeft = Math.max(1, (int) Math.ceil((cooldownUntil - serverTime) / 20.0D));
            message(player, "Drowned Domain cooldown: " + secondsLeft + "s");
            return;
        }
        COOLDOWNS.remove(player.getUUID());

        PowderPouch pouch = player.getCapability(PowderPouch.CAPABILITY).orElse(null);
        if (pouch == null) return;
        LivingEntity target = findTarget(player);
        if (target == null) {
            message(player, "Drowned Domain: look directly at a nearby hostile target.");
            return;
        }

        ServerLevel domain = server.getLevel(DOMAIN_DIMENSION);
        if (domain == null) {
            message(player, "The Drowned Domain dimension is unavailable; reload the world once.");
            return;
        }
        if (!SESSIONS.isEmpty()) {
            message(player, "Another Drowned Domain is already active.");
            return;
        }

        buildArena(domain);
        clearDomainProjectiles(domain);
        clearUninvitedMobs(domain, null);
        ResourceKey<Level> playerOrigin = player.level().dimension();
        Vec3 playerPosition = player.position();
        float playerYaw = player.getYRot();
        float playerPitch = player.getXRot();
        ResourceKey<Level> targetOrigin = target.level().dimension();
        Vec3 targetPosition = target.position();
        float targetYaw = target.getYRot();
        float targetPitch = target.getXRot();
        boolean targetWasPersistent = target instanceof Mob mob && mob.isPersistenceRequired();
        // Flip the original arrangement: the target begins in the lagoon and
        // the caster enters on the raised circular sand ring.
        LivingEntity movedTarget = moveEntity(target, domain, WATER_SPAWN_X, WATER_SPAWN_Y, 0.0D,
                targetYaw, targetPitch, true);
        if (movedTarget == null) {
            message(player, "The target could not be pulled into the domain.");
            return;
        }

        guardLifecycle(player, 60L);
        player.teleportTo(domain, BEACH_SPAWN_X, BEACH_SPAWN_Y, 0.0D, playerYaw, playerPitch);
        if (player.level() != domain) {
            ServerLevel targetReturnLevel = server.getLevel(targetOrigin);
            if (targetReturnLevel != null) {
                moveEntity(movedTarget, targetReturnLevel,
                        targetPosition.x, targetPosition.y, targetPosition.z,
                        targetYaw, targetPitch, targetWasPersistent);
            }
            message(player, "The caster could not enter the domain; the target was returned.");
            return;
        }
        movedTarget.setDeltaMovement(Vec3.ZERO);
        movedTarget.hurtMarked = true;
        forceTargetAggro(movedTarget, player);

        Session session = new Session(player.getUUID(), movedTarget, domain,
                playerOrigin, playerPosition, playerYaw, playerPitch,
                targetOrigin, targetPosition, targetYaw, targetPitch,
                targetWasPersistent, serverTime + DOMAIN_TICKS);
        SESSIONS.put(player.getUUID(), session);
        applyDomainBuffs(player);
        domain.playSound(null, player.blockPosition(), SoundEvents.AMBIENT_UNDERWATER_ENTER,
                SoundSource.PLAYERS, 1.2F, 0.7F);
        message(player, "The Drowned Domain opens — the tide answers your call!");
        message(player, "Drowned Domain cooldown begins when the domain closes: 5s.");
    }

    private static void recoverFinishedSessions(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (Session session : new ArrayList<>(SESSIONS.values())) {
            ServerPlayer owner = server.getPlayerList().getPlayer(session.ownerId);
            boolean abandoned = owner != null && owner.level() != session.domain
                    && now - session.startedAt > 40L;
            if (session.ending || owner == null || !owner.isAlive() || now >= session.endAt || abandoned) {
                finishSession(session, server, true, "stale session recovered");
            }
        }
    }

    /** Uses Forge's normal dimension-transfer contract; no Echo clone/discard/NBT path is used. */
    private static LivingEntity moveEntity(LivingEntity target, ServerLevel destination,
                                           double x, double y, double z,
                                           float yaw, float pitch,
                                           boolean persistenceRequired) {
        if (target instanceof ServerPlayer || destination == null
                || !(target.level() instanceof ServerLevel) || target.isRemoved()) return null;
        ITeleporter directTeleporter = new ITeleporter() {
            @Override
            public PortalInfo getPortalInfo(Entity entity, ServerLevel destinationLevel,
                                            Function<ServerLevel, PortalInfo> defaultPortalInfo) {
                return new PortalInfo(new Vec3(x, y, z), Vec3.ZERO, yaw, pitch);
            }

            @Override
            public boolean playTeleportSound(ServerPlayer player, ServerLevel sourceLevel,
                                             ServerLevel destinationLevel) {
                return false;
            }
        };
        Entity moved = target.changeDimension(destination, directTeleporter);
        if (!(moved instanceof LivingEntity living)) return null;
        living.moveTo(x, y, z, yaw, pitch);
        living.setDeltaMovement(Vec3.ZERO);
        if (persistenceRequired && living instanceof Mob mob) mob.setPersistenceRequired();
        return living;
    }

    private static void guardLifecycle(ServerPlayer player, long ticks) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            LIFECYCLE_GUARDS.put(player.getUUID(), server.overworld().getGameTime() + ticks);
        }
    }

    /** True only during the short Connector power-removal window around a domain teleport. */
    public static boolean shouldSuppressLifecycleUnload(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        Session active = SESSIONS.get(player.getUUID());
        if (active != null && !active.ending) return true;
        Long until = LIFECYCLE_GUARDS.get(player.getUUID());
        if (until == null) return false;
        if (server.overworld().getGameTime() <= until) return true;
        LIFECYCLE_GUARDS.remove(player.getUUID());
        return false;
    }

    /** Ends a session when Origins removes the power or the player unloads it. */
    public static void clearTransient(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null) finishSession(session, player.getServer(), true, "Origin removed");
        removeDomainBuffs(player);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        SESSIONS.clear();
        COOLDOWNS.clear();
        LIFECYCLE_GUARDS.clear();
        PENDING_ACTIVATIONS.clear();
        arenaReady = false;
        ServerLevel domain = event.getServer().getLevel(DOMAIN_DIMENSION);
        if (domain != null) buildArena(domain);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null) return;
        if (!PENDING_ACTIVATIONS.isEmpty()) {
            List<UUID> pending = new ArrayList<>(PENDING_ACTIVATIONS);
            PENDING_ACTIVATIONS.removeAll(pending);
            for (UUID playerId : pending) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) continue;
                try {
                    activateNow(player);
                } catch (Throwable error) {
                    LOGGER.error("Deferred Drowned Domain activation failed for {}", playerId, error);
                    message(player, "Drowned Domain failed to open; the key is ready to retry.");
                }
            }
        }
        if (SESSIONS.isEmpty()) return;
        List<Session> sessions = new ArrayList<>(SESSIONS.values());
        for (Session session : sessions) {
            try {
                tickSession(server, session);
            } catch (Throwable error) {
                LOGGER.error("Drowned Domain session {} failed; restoring its combatants",
                        session.ownerId, error);
                finishSession(session, server, true, "internal recovery");
            }
        }
    }

    private static void tickSession(MinecraftServer server, Session session) {
            ServerPlayer owner = server.getPlayerList().getPlayer(session.ownerId);
            if (owner == null || !owner.isAlive()) {
                finishSession(session, server, true, "caster unavailable");
                return;
            }
            long now = server.overworld().getGameTime();
            if (owner.level() != session.domain) {
                // A dimension transfer can expose the old level for a few
                // ticks. Tolerate that window instead of destroying a valid
                // session before the client finishes changing dimensions.
                if (++session.ownerMismatchTicks <= 40) return;
                finishSession(session, server, true, "caster left the domain");
                return;
            }
            session.ownerMismatchTicks = 0;
            if (now >= session.endAt) {
                finishSession(session, server, true, "time expired");
                return;
            }
            long age = now - session.startedAt;
            // Refresh potion effects once per second instead of sending effect
            // packets every server tick. Attribute modifiers are already stable.
            if (age % 20L == 0L) {
                applyDomainBuffs(owner);
                clearUninvitedMobs(session.domain, session.target);
            }
            if (!session.target.isAlive()
                    && session.target.getRemovalReason() == Entity.RemovalReason.KILLED) {
                session.targetDefeated = true;
                finishSession(session, server, false, "target defeated");
                return;
            }
            LivingEntity target = resolveTarget(session);
            if (target == null) {
                if (++session.targetMissingTicks <= 20) return;
                finishSession(session, server, true, "target transfer was interrupted");
                return;
            }
            session.targetMissingTicks = 0;
            if (!target.isAlive()) {
                session.targetDefeated = true;
                finishSession(session, server, false, "target defeated");
                return;
            }
            forceTargetAggro(target, owner, age % 10L == 0L);
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

    private static LivingEntity resolveTarget(Session session) {
        LivingEntity current = session.target;
        if (!current.isRemoved() && current.level() == session.domain) return current;
        Entity found = session.domain.getEntity(session.targetId);
        if (found instanceof LivingEntity living) {
            session.target = living;
            return living;
        }
        return null;
    }

    /** Keeps transferred mobs loaded and hostile to the caster for the full session. */
    private static void forceTargetAggro(LivingEntity target, ServerPlayer owner) {
        forceTargetAggro(target, owner, true);
    }

    private static void forceTargetAggro(LivingEntity target, ServerPlayer owner, boolean refreshPath) {
        if (!(target instanceof Mob mob)) return;
        mob.setPersistenceRequired();
        if (mob.getTarget() != owner) {
            mob.setTarget(owner);
            mob.setAggressive(true);
            mob.setLastHurtByMob(owner);
        }
        mob.getLookControl().setLookAt(owner, 30.0F, 30.0F);
        if (refreshPath && mob.distanceToSqr(owner) > 2.25D) {
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
        // Snapshot the target's exact hitbox centre now. The projectile never
        // reads the target again: it flies a fixed line to where the target WAS
        // when the broadside fired, giving a fast-moving target a fair dodge.
        Vec3 targetCentre = target.getBoundingBox().getCenter();
        Vec3 aim = new Vec3(
                Math.max(-40.0D, Math.min(40.0D, targetCentre.x)),
                targetCentre.y,
                Math.max(-40.0D, Math.min(40.0D, targetCentre.z)));

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
        FlintlockBall cannonball = FlintlockBall.cannonball(session.domain, owner, damage, 4.5F);
        cannonball.setPos(origin.x, origin.y, origin.z);
        cannonball.setGuaranteedImpact(aim);
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
        for (int point = 0; point < 10; point++) {
            double angle = Math.PI * 2.0D * point / 10.0D;
            session.domain.sendParticles(new net.minecraft.core.particles.DustParticleOptions(
                            new Vector3f(0.75F, 0.12F, 0.04F), 0.65F),
                    aim.x + Math.cos(angle) * 4.5D, aim.y + 0.08D,
                    aim.z + Math.sin(angle) * 4.5D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
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

    private static void finishSession(Session session, MinecraftServer server,
                                      boolean returnCombatants, String reason) {
        if (session.ending) {
            SESSIONS.remove(session.ownerId, session);
            return;
        }
        session.ending = true;
        SESSIONS.remove(session.ownerId, session);
        if (server == null) return;
        long now = server.overworld().getGameTime();
        COOLDOWNS.put(session.ownerId, now + COOLDOWN_TICKS);
        clearDomainProjectiles(session.domain);
        ServerPlayer owner = server.getPlayerList().getPlayer(session.ownerId);
        try {
            if (owner != null) {
                removeDomainBuffs(owner);
                if (owner.isAlive()) {
                    guardLifecycle(owner, 60L);
                    teleportPlayerBack(owner, server, session);
                    message(owner, "Drowned Domain closed (" + reason + "). Cooldown: 5s.");
                }
            }
        } finally {
            if (returnCombatants && !session.targetDefeated) restoreTarget(session, server);
        }
    }

    /** Returns the same Forge-transferred entity to its recorded origin. */
    private static void restoreTarget(Session session, MinecraftServer server) {
        ServerLevel origin = server.getLevel(session.targetOrigin);
        if (origin == null) return;
        Entity alreadyReturned = origin.getEntity(session.targetId);
        if (alreadyReturned instanceof LivingEntity) return;

        LivingEntity target = resolveTarget(session);
        if (target != null && target.isAlive()) {
            LivingEntity moved = moveEntity(target, origin,
                    session.targetPosition.x, session.targetPosition.y, session.targetPosition.z,
                    session.targetYaw, session.targetPitch, session.targetWasPersistent);
            if (moved == null) {
                LOGGER.error("Could not return Drowned Domain target {} to {}",
                        session.targetId, session.targetOrigin.location());
            }
        }
    }

    private static void teleportPlayerBack(ServerPlayer owner, MinecraftServer server, Session session) {
        ServerLevel origin = server.getLevel(session.playerOrigin);
        if (origin != null) owner.teleportTo(origin, session.playerPosition.x, session.playerPosition.y,
                session.playerPosition.z, session.playerYaw, session.playerPitch);
    }

    private static void clearDomainProjectiles(ServerLevel domain) {
        AABB arena = new AABB(-180.0D, 0.0D, -180.0D, 180.0D, 140.0D, 180.0D);
        for (FlintlockBall projectile : domain.getEntitiesOfClass(FlintlockBall.class, arena,
                FlintlockBall::isCannonball)) {
            projectile.discard();
        }
    }

    /**
     * The Void biome disables ordinary spawn tables; this second guard also
     * removes special-spawner arrivals such as patrols without touching the
     * transferred opponent or the caster's summoned crew.
     */
    private static void clearUninvitedMobs(ServerLevel domain, LivingEntity allowedTarget) {
        AABB arena = new AABB(-180.0D, 0.0D, -180.0D, 180.0D, 140.0D, 180.0D);
        for (Mob mob : domain.getEntitiesOfClass(Mob.class, arena,
                candidate -> candidate != allowedTarget && !(candidate instanceof UndeadCrewmate))) {
            mob.discard();
        }
    }

    private static void buildArena(ServerLevel level) {
        if (arenaReady) return;
        if (level.getBlockState(ARENA_MARKER).is(Blocks.DIAMOND_BLOCK)) {
            arenaReady = true;
            return;
        }
        final int beachTop = 66;

        // Version-2 terrain: one opaque sandstone seabed with exactly two
        // water blocks above it. This also seals old 60-deep-ocean chunks so
        // their transparent fluid layers are no longer rendered or simulated.
        buildOceanFoundation(level);

        // Remove only the obsolete terrain inside the playable arena. The old
        // migration swept more than two million positions; that scan was a
        // major source of server stalls and client chunk-update storms.
        clearLegacyCannons(level, 0, -76, true);
        clearLegacyCannons(level, 0, 76, true);
        clearLegacyCannons(level, -76, 0, false);
        clearLegacyCannons(level, 76, 0, false);
        clearOldSquareBarriers(level);
        for (int x = -44; x <= 44; x++) {
            for (int z = -44; z <= 44; z++) {
                for (int y = 65; y <= 82; y++) set(level, x, y, z, Blocks.AIR);
                double radiusSquared = square(x) + square(z);
                boolean sandyRing = radiusSquared <= square(42.0D)
                        && radiusSquared >= square(25.0D);
                if (sandyRing) {
                    set(level, x, 63, z, Blocks.SANDSTONE);
                    set(level, x, 64, z, Blocks.SANDSTONE);
                    set(level, x, 65, z, Blocks.SAND);
                    set(level, x, beachTop, z, Blocks.SAND);
                } else {
                    set(level, x, 63, z, Blocks.WATER);
                    set(level, x, 64, z, Blocks.WATER);
                }
            }
        }

        int[][] dunes = {
                {-34, -10}, {-34, 10}, {-27, -25}, {-27, 25}, {-10, -34}, {-9, 34},
                {34, -10}, {34, 10}, {27, -25}, {27, 25}, {10, -34}, {9, 34}
        };
        for (int i = 0; i < dunes.length; i++) {
            buildSandDune(level, dunes[i][0], dunes[i][1], 4 + i % 2, 3 + (i + 1) % 2, 67, 2 + i % 3);
        }
        buildPalm(level, -29, 67, 5);
        buildLagoonStairs(level);
        buildMirroredLagoonStairs(level);
        buildBarriers(level);

        // Every ship lies tangentially around the arena, presenting its long
        // inward broadside rather than its bow to the combatants.
        buildShip(level, 0, -76, true);
        buildShip(level, 0, 76, true);
        buildShip(level, -76, 0, false);
        buildShip(level, 76, 0, false);
        buildDistantIslands(level);
        set(level, ARENA_MARKER.getX(), ARENA_MARKER.getY(), ARENA_MARKER.getZ(),
                Blocks.DIAMOND_BLOCK);
        arenaReady = true;
    }

    private static void buildOceanFoundation(ServerLevel level) {
        // Radius 154 contains the playable wall, all four ships and the dune
        // silhouettes. New chunks use the same layers through dimension JSON.
        for (int x = -154; x <= 154; x++) {
            for (int z = -154; z <= 154; z++) {
                set(level, x, 62, z, Blocks.SANDSTONE);
                set(level, x, 63, z, Blocks.WATER);
                set(level, x, 64, z, Blocks.WATER);
            }
        }
    }

    /** Removes the old eighty-cannon layout without scanning the surrounding world. */
    private static void clearLegacyCannons(ServerLevel level, int cx, int cz, boolean eastWest) {
        int[] oldCannonAlong = {-23, -18, -13, -8, -3, 3, 8, 13, 18, 23};
        for (int along : oldCannonAlong) {
            for (int side : new int[]{-1, 1}) {
                Direction facing = eastWest
                        ? (side < 0 ? Direction.NORTH : Direction.SOUTH)
                        : (side < 0 ? Direction.WEST : Direction.EAST);
                int x = eastWest ? cx + along : cx + side * 11;
                int z = eastWest ? cz + side * 11 : cz + along;
                set(level, x, 65, z, Blocks.AIR);
                set(level, x, 66, z, Blocks.AIR);
                for (int segment = 1; segment <= 4; segment++) {
                    set(level, x + facing.getStepX() * segment, 66,
                            z + facing.getStepZ() * segment, Blocks.AIR);
                }
            }
        }
    }

    private static void buildSandDune(ServerLevel level, int cx, int cz, int radiusX,
                                      int radiusZ, int baseY, int height) {
        // Every falling-sand footprint gets a solid sandstone shelf first.
        for (int dx = -radiusX; dx <= radiusX; dx++) {
            for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                if (square(dx / (double) radiusX) + square(dz / (double) radiusZ) <= 1.0D) {
                    set(level, cx + dx, baseY - 1, cz + dz, Blocks.SANDSTONE);
                }
            }
        }
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

    /** A nine-block-wide, three-step sandstone ramp from the lagoon onto the crescent. */
    private static void buildLagoonStairs(ServerLevel level) {
        for (int z = -4; z <= 4; z++) {
            set(level, -22, 63, z, Blocks.SANDSTONE);
            setStair(level, -22, 64, z, true);

            set(level, -23, 63, z, Blocks.SANDSTONE);
            set(level, -23, 64, z, Blocks.SANDSTONE);
            setStair(level, -23, 65, z, true);

            set(level, -24, 63, z, Blocks.SANDSTONE);
            set(level, -24, 64, z, Blocks.SANDSTONE);
            set(level, -24, 65, z, Blocks.SANDSTONE);
            setStair(level, -24, 66, z, false);
        }
    }

    /** Matching east-side ramp for the completed circular sand arena. */
    private static void buildMirroredLagoonStairs(ServerLevel level) {
        for (int z = -4; z <= 4; z++) {
            set(level, 22, 63, z, Blocks.SANDSTONE);
            setMirroredStair(level, 22, 64, z, true);

            set(level, 23, 63, z, Blocks.SANDSTONE);
            set(level, 23, 64, z, Blocks.SANDSTONE);
            setMirroredStair(level, 23, 65, z, true);

            set(level, 24, 63, z, Blocks.SANDSTONE);
            set(level, 24, 64, z, Blocks.SANDSTONE);
            set(level, 24, 65, z, Blocks.SANDSTONE);
            setMirroredStair(level, 24, 66, z, false);
        }
    }

    private static void setMirroredStair(ServerLevel level, int x, int y, int z, boolean waterlogged) {
        BlockState state = Blocks.SANDSTONE_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, waterlogged);
        }
        level.setBlock(new BlockPos(x, y, z), state, 2);
    }

    private static void setStair(ServerLevel level, int x, int y, int z, boolean waterlogged) {
        BlockState state = Blocks.SANDSTONE_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, waterlogged);
        }
        level.setBlock(new BlockPos(x, y, z), state, 2);
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
        final double innerRadiusSquared = square(42.25D);
        final double outerRadiusSquared = square(44.0D);
        for (int y = 63; y <= 110; y++) {
            for (int x = -44; x <= 44; x++) {
                for (int z = -44; z <= 44; z++) {
                    double radiusSquared = square(x) + square(z);
                    if (radiusSquared >= innerRadiusSquared && radiusSquared <= outerRadiusSquared) {
                        set(level, x, y, z, Blocks.BARRIER);
                    }
                }
            }
        }
    }

    /** Removes the previous square wall before placing the circular shell. */
    private static void clearOldSquareBarriers(ServerLevel level) {
        for (int y = 63; y <= 110; y++) {
            for (int n = -41; n <= 41; n++) {
                clearBarrier(level, -41, y, n);
                clearBarrier(level, 41, y, n);
                clearBarrier(level, n, y, -41);
                clearBarrier(level, n, y, 41);
            }
        }
    }

    private static void clearBarrier(ServerLevel level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (level.getBlockState(pos).is(Blocks.BARRIER)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
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

        // Only the inward broadside needs live CBC blocks. Older builds placed
        // eighty complete cannons (including the unseen outer broadsides),
        // creating hundreds of ticking block entities around the player.
        int[] cannonAlong = {-21, -14, -7, 0, 7, 14, 21};
        int inwardSide = eastWest ? (cz < 0 ? 1 : -1) : (cx < 0 ? 1 : -1);
        for (int along : cannonAlong) {
            for (int side : new int[]{-1, 1}) {
                placeShipBlock(level, cx, cz, eastWest, along, side * 12, 66, Blocks.POLISHED_BLACKSTONE);
            }
            buildCannon(level, cx, cz, eastWest, inwardSide, along);
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
        BlockPos position = new BlockPos(x, y, z);
        if (!level.getBlockState(position).equals(state)) level.setBlock(position, state, 2);
    }

    private static void set(ServerLevel level, int x, int y, int z, net.minecraft.world.level.block.Block block) {
        // Client update without neighbor updates: this keeps the two-deep
        // source-water section stable and avoids fluid/gravity cascades. Skip
        // unchanged cells so subsequent starts never resend the whole arena.
        BlockPos position = new BlockPos(x, y, z);
        BlockState desired = block.defaultBlockState();
        if (!level.getBlockState(position).equals(desired)) {
            level.setBlock(position, desired, 2);
        }
    }

    private static void message(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.GOLD), true);
    }

    private static final class Session {
        private final UUID ownerId;
        private LivingEntity target;
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
        private int ownerMismatchTicks;
        private int targetMissingTicks;
        private boolean targetDefeated;
        private boolean ending;

        private Session(UUID ownerId, LivingEntity target, ServerLevel domain,
                        ResourceKey<Level> playerOrigin, Vec3 playerPosition, float playerYaw, float playerPitch,
                        ResourceKey<Level> targetOrigin, Vec3 targetPosition, float targetYaw, float targetPitch,
                        boolean targetWasPersistent, long endAt) {
            this.ownerId = ownerId;
            this.target = target;
            this.targetId = target.getUUID();
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
            this.startedAt = endAt - DOMAIN_TICKS;
            this.endAt = endAt;
            this.lastCannonball = this.startedAt;
        }
    }
}
