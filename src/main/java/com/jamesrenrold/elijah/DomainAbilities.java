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
    private static final double ARENA_Y = 65.0D;
    private static final UUID DOMAIN_SPEED_ID = UUID.fromString("c01c5046-3b27-49c5-9384-95f1f1cdb5db");
    private static final UUID DOMAIN_LIFESTEAL_ID = UUID.fromString("6e39eb13-5420-4de8-bf2d-5895e1cbbd7c");
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Vector3f CANNON_DUST = new Vector3f(0.08F, 0.08F, 0.08F);

    private DomainAbilities() {}

    public static int activate(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!player.isAlive() || player.isSpectator() || BloodAbilities.isHuntActive(player)
                || SESSIONS.containsKey(player.getUUID())) return 0;

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

        LivingEntity movedTarget = transferLivingEntity(target, domain, 10.0D, ARENA_Y, 0.0D,
                targetYaw, targetPitch);
        if (movedTarget == null) {
            message(player, "The target could not be pulled into the domain.");
            return 0;
        }

        player.teleportTo(domain, -10.0D, ARENA_Y, 0.0D, playerYaw, playerPitch);
        movedTarget.setDeltaMovement(Vec3.ZERO);
        movedTarget.hurtMarked = true;

        Session session = new Session(player.getUUID(), movedTarget.getUUID(), domain,
                playerOrigin, playerPosition, playerYaw, playerPitch,
                targetOrigin, targetPosition, targetYaw, targetPitch,
                domain.getGameTime() + DOMAIN_TICKS);
        SESSIONS.put(player.getUUID(), session);
        applyDomainBuffs(player);
        domain.playSound(null, player.blockPosition(), SoundEvents.AMBIENT_UNDERWATER_ENTER,
                SoundSource.PLAYERS, 1.2F, 0.7F);
        message(player, "The Drowned Domain opens — the tide answers your call!");
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
                                                      float yaw, float pitch) {
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
        // Alternate between the four invisible arena walls. These launch
        // points line up with the four ships outside the barrier, making the
        // barrage read as broadside fire rather than projectiles appearing in
        // the sky. Spawn just inside the barrier so it cannot intercept them.
        int wall = shot & 3;
        double lateralBase = wall < 2 ? target.getZ() : target.getX();
        double lateral = Math.max(-28.0D, Math.min(28.0D,
                lateralBase + ((shot % 5) - 2) * 1.5D));
        Vec3 origin = switch (wall) {
            case 0 -> new Vec3(-39.25D, 68.0D + (shot % 3), lateral);
            case 1 -> new Vec3(39.25D, 68.0D + (shot % 3), lateral);
            case 2 -> new Vec3(lateral, 68.0D + (shot % 3), -39.25D);
            default -> new Vec3(lateral, 68.0D + (shot % 3), 39.25D);
        };
        Vec3 aim = target.position().add(0.0D,
                Math.min(0.9D, target.getBbHeight() * 0.35D), 0.0D);
        Vec3 direction = aim.subtract(origin).normalize();

        int curse = Math.max(0, Math.min(100,
                ElijahPirate.getOriginResource(owner, "elijah:blood_resource")));
        float attackDamage = (float) (owner.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.30D);
        float curseDamage = (curse / 10) * 1.5F;
        float damage = Math.max(1.0F, attackDamage + curseDamage);
        FlintlockBall cannonball = FlintlockBall.cannonball(session.domain, owner, damage, 4.0F);
        cannonball.setPos(origin.x, origin.y, origin.z);
        cannonball.shoot(direction.x, direction.y, direction.z, 4.6F, 0.0F);
        session.domain.addFreshEntity(cannonball);
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
                    aim.x + Math.cos(angle) * 1.25D, target.getY() + 0.08D,
                    aim.z + Math.sin(angle) * 1.25D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        session.domain.playSound(null, origin.x, origin.y, origin.z, SoundEvents.FIREWORK_ROCKET_BLAST,
                SoundSource.HOSTILE, 0.75F, 0.55F);
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
                transferLivingEntity(target, origin, session.targetPosition.x, session.targetPosition.y,
                        session.targetPosition.z, session.targetYaw, session.targetPitch);
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
        // A full barrier floor prevents the sand/water island from falling if
        // the dimension generator is replaced or a block update occurs.
        for (int x = -42; x <= 42; x++) {
            for (int z = -42; z <= 42; z++) set(level, x, surface - 2, z, Blocks.BARRIER);
        }
        // Fill all the way to the barrier walls. Water is exactly two source
        // blocks deep and is sealed by invisible barriers so it cannot spread.
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                set(level, x, surface - 1, z, Blocks.SAND);
                // Slightly more than half of the expanded arena is water,
                // while keeping the requested two-source-block depth.
                if (x <= -7) {
                    set(level, x, surface, z, Blocks.SAND);
                    set(level, x, surface + 1, z, Blocks.AIR);
                } else if (x == -6) {
                    set(level, x, surface, z, Blocks.BARRIER);
                    set(level, x, surface + 1, z, Blocks.BARRIER);
                } else {
                    set(level, x, surface, z, Blocks.WATER);
                    set(level, x, surface + 1, z, Blocks.WATER);
                }
            }
        }

        // Low dunes on the sandy half.
        int[][] dunes = {{-29, -19}, {-20, -14}, {-11, -24}, {-31, 7}, {-20, 19}, {-10, 8}};
        for (int[] dune : dunes) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > 4) continue;
                    int height = 1 + Math.max(0, 2 - (Math.abs(dx) + Math.abs(dz)) / 2);
                    for (int dy = 0; dy < height; dy++) set(level, dune[0] + dx, surface + 1 + dy, dune[1] + dz, Blocks.SAND);
                }
            }
        }
        buildPalm(level, -23, surface, -1);
        buildBarriers(level);
        buildShip(level, 0, -64, false);
        buildShip(level, 0, 64, false);
        buildShip(level, -64, 0, true);
        buildShip(level, 64, 0, true);
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
            for (int n = -41; n <= 41; n++) {
                set(level, -41, y, n, Blocks.BARRIER);
                set(level, 41, y, n, Blocks.BARRIER);
                set(level, n, y, -41, Blocks.BARRIER);
                set(level, n, y, 41, Blocks.BARRIER);
            }
        }
    }

    private static void buildShip(ServerLevel level, int cx, int cz, boolean eastWest) {
        // Large multi-deck dark-oak galleon silhouette: pointed hull, raised
        // sterncastle, three masts, yards, sails, and working CBC cannon blocks.
        for (int along = -19; along <= 19; along++) {
            int halfWidth = Math.max(2, 9 - Math.max(0, Math.abs(along) - 12) / 2);
            for (int across = -halfWidth; across <= halfWidth; across++) {
                int x = eastWest ? cx + along : cx + across;
                int z = eastWest ? cz + across : cz + along;
                set(level, x, 64, z, Blocks.DARK_OAK_PLANKS);
                set(level, x, 65, z, Blocks.DARK_OAK_PLANKS);
                set(level, x, 66, z, Blocks.DARK_OAK_PLANKS);
                if (Math.abs(across) >= halfWidth - 1) set(level, x, 67, z, Blocks.DARK_OAK_FENCE);
            }
        }
        int[] mastAlong = {-12, 0, 12};
        for (int along : mastAlong) buildMast(level, cx, cz, eastWest, along);
        // Broad raised sterncastle and a long bowsprit.
        for (int along = 12; along <= 18; along++) {
            for (int across = -7; across <= 7; across++) {
                int x = eastWest ? cx + along : cx + across;
                int z = eastWest ? cz + across : cz + along;
                set(level, x, 68, z, Blocks.DARK_OAK_PLANKS);
                if (along >= 15) set(level, x, 69, z, Blocks.DARK_OAK_PLANKS);
            }
        }
        for (int along = -21; along <= -14; along++) {
            int x = eastWest ? cx + along : cx;
            int z = eastWest ? cz : cz + along;
            set(level, x, 68, z, Blocks.DARK_OAK_FENCE);
        }
        for (int side : new int[]{-1, 1}) buildCannon(level, cx, cz, eastWest, side, -11);
        for (int side : new int[]{-1, 1}) buildCannon(level, cx, cz, eastWest, side, 0);
        for (int side : new int[]{-1, 1}) buildCannon(level, cx, cz, eastWest, side, 11);
    }

    private static void buildMast(ServerLevel level, int cx, int cz, boolean eastWest, int along) {
        int mastX = eastWest ? cx + along : cx;
        int mastZ = eastWest ? cz : cz + along;
        for (int y = 68; y <= 90; y++) set(level, mastX, y, mastZ, Blocks.DARK_OAK_LOG);
        for (int y = 74; y <= 84; y++) {
            int width = Math.max(2, 9 - Math.abs(y - 79));
            for (int offset = -width; offset <= width; offset++) {
                int x = eastWest ? mastX : mastX + offset;
                int z = eastWest ? mastZ + offset : mastZ;
                set(level, x, y, z, y % 2 == 0 ? Blocks.BLACK_WOOL : Blocks.WHITE_WOOL);
            }
        }
        for (int offset = -10; offset <= 10; offset++) {
            int x = eastWest ? mastX : mastX + offset;
            int z = eastWest ? mastZ + offset : mastZ;
            set(level, x, 79, z, Blocks.DARK_OAK_FENCE);
        }
    }

    private static void buildCannon(ServerLevel level, int cx, int cz, boolean eastWest,
                                    int side, int along) {
        Direction facing = eastWest
                ? (side < 0 ? Direction.WEST : Direction.EAST)
                : (side < 0 ? Direction.NORTH : Direction.SOUTH);
        int x = eastWest ? cx + side * 8 : cx + along;
        int z = eastWest ? cz + along : cz + side * 8;
        int dx = facing.getStepX();
        int dz = facing.getStepZ();
        setOptional(level, x, 68, z, "createbigcannons:cannon_carriage", facing);
        setOptional(level, x, 69, z, "createbigcannons:fixed_cannon_mount", facing);
        setOptional(level, x + dx, 69, z + dz, "createbigcannons:cast_iron_cannon_chamber", facing);
        setOptional(level, x + dx * 2, 69, z + dz * 2, "createbigcannons:cast_iron_cannon_barrel", facing);
        setOptional(level, x + dx * 3, 69, z + dz * 3, "createbigcannons:cast_iron_cannon_barrel", facing);
        setOptional(level, x + dx * 4, 69, z + dz * 4, "createbigcannons:cast_iron_cannon_end", facing);
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
        private final long startedAt;
        private final long endAt;
        private long lastCannonball;
        private int cannonIndex;
        private int lastDisplayedSecond = -1;

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
