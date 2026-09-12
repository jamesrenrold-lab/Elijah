package com.jamesrenrold.elijah;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
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
    private static final int DOMAIN_COOLDOWN_TICKS = 4 * 60 * 20;
    private static final int CANNON_DELAY_TICKS = 5 * 20;
    private static final int CANNON_INTERVAL_TICKS = 10;
    private static final double ARENA_Y = 65.0D;
    private static final UUID DOMAIN_SPEED_ID = UUID.fromString("c01c5046-3b27-49c5-9384-95f1f1cdb5db");
    private static final UUID DOMAIN_LIFESTEAL_ID = UUID.fromString("6e39eb13-5420-4de8-bf2d-5895e1cbbd7c");
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Vector3f CANNON_DUST = new Vector3f(0.08F, 0.08F, 0.08F);

    private DomainAbilities() {}

    public static int activate(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!player.isAlive() || player.isSpectator() || SESSIONS.containsKey(player.getUUID())) return 0;

        PowderPouch pouch = player.getCapability(PowderPouch.CAPABILITY).orElse(null);
        if (pouch == null) return 0;
        long now = player.serverLevel().getGameTime();
        if (now < pouch.domainCooldownUntil) {
            message(player, "Drowned Domain: " + ((pouch.domainCooldownUntil - now + 19L) / 20L) + "s remaining");
            return 0;
        }

        LivingEntity target = findTarget(player);
        if (target == null) {
            message(player, "Drowned Domain needs a nearby hostile target.");
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

        Entity changed = target.changeDimension(domain);
        if (!(changed instanceof LivingEntity movedTarget)) {
            message(player, "The target could not be pulled into the domain.");
            return 0;
        }

        player.teleportTo(domain, -10.0D, ARENA_Y, 0.0D, playerYaw, playerPitch);
        movedTarget.teleportTo(10.0D, ARENA_Y, 0.0D);
        movedTarget.setYRot(targetYaw);
        movedTarget.setXRot(targetPitch);
        movedTarget.setDeltaMovement(Vec3.ZERO);
        movedTarget.hurtMarked = true;

        Session session = new Session(player.getUUID(), movedTarget.getUUID(), domain,
                playerOrigin, playerPosition, playerYaw, playerPitch,
                targetOrigin, targetPosition, targetYaw, targetPitch,
                domain.getGameTime() + DOMAIN_TICKS);
        SESSIONS.put(player.getUUID(), session);
        pouch.domainCooldownUntil = now + DOMAIN_COOLDOWN_TICKS;
        applyDomainBuffs(player);
        domain.playSound(null, player.blockPosition(), SoundEvents.AMBIENT_UNDERWATER_ENTER,
                SoundSource.PLAYERS, 1.2F, 0.7F);
        message(player, "The Drowned Domain opens — the tide answers your call!");
        return 1;
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
                endSession(session, server, false);
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
            if (now >= session.startedAt + CANNON_DELAY_TICKS
                    && now - session.lastCannonball >= CANNON_INTERVAL_TICKS) {
                session.lastCannonball = now;
                fireCannonball(session, owner, target);
            }
        }
    }

    private static LivingEntity findTarget(ServerPlayer player) {
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
        double angle = (session.cannonIndex++ * Math.PI * 0.5D) + (session.cannonIndex % 2) * 0.22D;
        Vec3 aim = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
        Vec3 origin = aim.add(Math.cos(angle) * 4.6D, 0.7D, Math.sin(angle) * 4.6D);
        Vec3 direction = aim.subtract(origin).normalize();

        // A zero-damage visible shot is paired with the direct damage below.
        // This keeps the sure-hit behavior reliable even when a target moves
        // during the very short five-block flight path.
        FlintlockBall visual = new FlintlockBall(session.domain, owner, 0.0F, 0);
        visual.setPos(origin.x, origin.y, origin.z);
        visual.shoot(direction.x, direction.y, direction.z, 2.8F, 0.0F);
        session.domain.addFreshEntity(visual);

        if (target.hurt(session.domain.damageSources().magic(), 4.0F)) {
            session.domain.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION,
                    aim.x, aim.y, aim.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        session.domain.sendParticles(new net.minecraft.core.particles.DustParticleOptions(CANNON_DUST, 1.2F),
                origin.x, origin.y, origin.z, 8, 0.12D, 0.12D, 0.12D, 0.04D);
        session.domain.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE,
                origin.x, origin.y, origin.z, 10, 0.15D, 0.15D, 0.15D, 0.06D);
        session.domain.playSound(null, origin.x, origin.y, origin.z, SoundEvents.GENERIC_EXPLODE,
                SoundSource.HOSTILE, 0.65F, 1.45F);
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
            if (owner.isAlive()) teleportPlayerBack(owner, server, session);
        }
        if (!returnCombatants) return;
        Entity entity = session.domain.getEntity(session.targetId);
        if (entity instanceof LivingEntity target && target.isAlive()) {
            ServerLevel origin = server.getLevel(session.targetOrigin);
            if (origin != null) {
                Entity moved = target.changeDimension(origin);
                if (moved != null) {
                    moved.teleportTo(session.targetPosition.x, session.targetPosition.y, session.targetPosition.z);
                    moved.setYRot(session.targetYaw);
                    moved.setXRot(session.targetPitch);
                }
            }
        }
    }

    private static void teleportPlayerBack(ServerPlayer owner, MinecraftServer server, Session session) {
        ServerLevel origin = server.getLevel(session.playerOrigin);
        if (origin != null) owner.teleportTo(origin, session.playerPosition.x, session.playerPosition.y,
                session.playerPosition.z, session.playerYaw, session.playerPitch);
    }

    private static void buildArena(ServerLevel level) {
        final int surface = 64;
        // The flat dimension provides a water ocean up to y=63. Replace the
        // center with a broad beach/lagoon, leaving open water on every side.
        for (int x = -27; x <= 27; x++) {
            for (int z = -23; z <= 23; z++) {
                double ellipse = (x * x) / 729.0D + (z * z) / 529.0D;
                if (ellipse > 1.0D) continue;
                set(level, x, surface - 1, z, Blocks.SAND);
                if (x < 0) {
                    set(level, x, surface, z, Blocks.SAND);
                    set(level, x, surface + 1, z, Blocks.AIR);
                } else {
                    set(level, x, surface, z, Blocks.WATER);
                    set(level, x, surface + 1, z, Blocks.WATER);
                }
            }
        }

        // Low dunes on the sandy half.
        int[][] dunes = {{-19, -12}, {-11, -14}, {-3, -9}, {-20, 7}, {-9, 13}, {-2, 6}};
        for (int[] dune : dunes) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > 4) continue;
                    int height = 1 + Math.max(0, 2 - (Math.abs(dx) + Math.abs(dz)) / 2);
                    for (int dy = 0; dy < height; dy++) set(level, dune[0] + dx, surface + 1 + dy, dune[1] + dz, Blocks.SAND);
                }
            }
        }
        buildPalm(level, -15, surface, -1);
        buildBarriers(level);
        buildShip(level, 0, -43, false);
        buildShip(level, 0, 43, false);
        buildShip(level, -43, 0, true);
        buildShip(level, 43, 0, true);
    }

    private static void buildPalm(ServerLevel level, int x, int baseY, int z) {
        for (int y = 0; y < 8; y++) set(level, x + (y > 4 ? 1 : 0), baseY + y, z, Blocks.JUNGLE_LOG);
        int topX = x + 1;
        int topY = baseY + 8;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 4) set(level, topX + dx, topY, z + dz, Blocks.JUNGLE_LEAVES);
            }
        }
        set(level, topX, topY + 1, z, Blocks.JUNGLE_LEAVES);
        set(level, topX - 3, topY - 1, z, Blocks.JUNGLE_LEAVES);
        set(level, topX + 3, topY - 1, z, Blocks.JUNGLE_LEAVES);
        set(level, topX, topY - 1, z - 3, Blocks.JUNGLE_LEAVES);
        set(level, topX, topY - 1, z + 3, Blocks.JUNGLE_LEAVES);
    }

    private static void buildBarriers(ServerLevel level) {
        for (int y = 64; y <= 76; y++) {
            for (int n = -31; n <= 31; n++) {
                set(level, -31, y, n, Blocks.BARRIER);
                set(level, 31, y, n, Blocks.BARRIER);
                set(level, n, y, -31, Blocks.BARRIER);
                set(level, n, y, 31, Blocks.BARRIER);
            }
        }
    }

    private static void buildShip(ServerLevel level, int cx, int cz, boolean eastWest) {
        for (int along = -7; along <= 7; along++) {
            for (int across = -3; across <= 3; across++) {
                if (Math.abs(across) == 3 && Math.abs(along) < 5) continue;
                int x = eastWest ? cx + across : cx + along;
                int z = eastWest ? cz + along : cz + across;
                set(level, x, 64, z, Blocks.DARK_OAK_PLANKS);
                set(level, x, 65, z, Blocks.DARK_OAK_PLANKS);
            }
        }
        int mastX = eastWest ? cx : cx;
        int mastZ = eastWest ? cz : cz;
        for (int y = 66; y <= 75; y++) set(level, mastX, y, mastZ, Blocks.DARK_OAK_LOG);
        for (int sailY = 69; sailY <= 73; sailY++) {
            for (int width = -3; width <= 3; width++) {
                if (Math.abs(width) <= sailY - 69) {
                    int x = eastWest ? mastX + width : mastX + width;
                    int z = eastWest ? mastZ : mastZ + width;
                    set(level, x, sailY, z, Blocks.BLACK_WOOL);
                }
            }
        }
        set(level, eastWest ? mastX + 3 : mastX, 66, eastWest ? mastZ : mastZ + 3, Blocks.DARK_OAK_FENCE);
        set(level, eastWest ? mastX - 3 : mastX, 66, eastWest ? mastZ : mastZ - 3, Blocks.DARK_OAK_FENCE);
    }

    private static void set(ServerLevel level, int x, int y, int z, net.minecraft.world.level.block.Block block) {
        level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
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
        private final long startedAt;
        private final long endAt;
        private long lastCannonball;
        private int cannonIndex;

        private Session(UUID ownerId, UUID targetId, ServerLevel domain,
                        ResourceKey<Level> playerOrigin, Vec3 playerPosition, float playerYaw, float playerPitch,
                        ResourceKey<Level> targetOrigin, Vec3 targetPosition, float targetYaw, float targetPitch,
                        long endAt) {
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
            this.startedAt = domain.getGameTime();
            this.endAt = endAt;
            this.lastCannonball = this.startedAt;
        }
    }
}
