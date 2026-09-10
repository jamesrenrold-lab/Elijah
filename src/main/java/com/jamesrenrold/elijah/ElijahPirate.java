package com.jamesrenrold.elijah;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(ElijahPirate.MOD_ID)
public final class ElijahPirate {
    public static final String MOD_ID = "elijah";
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID);
    public static final RegistryObject<MenuType<PowderMenu>> POWDER_MENU = MENUS.register(
            "powder_pouch", () -> new MenuType<>(PowderMenu::new, FeatureFlags.DEFAULT_FLAGS));
    public static final RegistryObject<EntityType<FlintlockBall>> FLINTLOCK_BALL = ENTITIES.register(
            "flintlock_ball", () -> EntityType.Builder.<FlintlockBall>of(FlintlockBall::new, MobCategory.MISC)
                    .sized(0.18F, 0.18F).clientTrackingRange(8).updateInterval(1)
                    .build(MOD_ID + ":flintlock_ball"));
    public static final RegistryObject<EntityType<UndeadCrewmate>> UNDEAD_CREWMATE = ENTITIES.register(
            "undead_crewmate", () -> EntityType.Builder.<UndeadCrewmate>of(UndeadCrewmate::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F).clientTrackingRange(10).updateInterval(3)
                    .build(MOD_ID + ":undead_crewmate"));

    public ElijahPirate() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        MENUS.register(bus);
        ENTITIES.register(bus);
        bus.addListener(this::entityAttributes);
        bus.addListener(this::registerCapabilities);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, PirateConfig.SPEC);
        MinecraftForge.EVENT_BUS.addGenericListener(Entity.class, this::attach);
        MinecraftForge.EVENT_BUS.addListener(this::clonePlayer);
        MinecraftForge.EVENT_BUS.addListener(this::dropPowder);
        MinecraftForge.EVENT_BUS.addListener(this::commands);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.register(PowderPouch.class);
    }

    private void entityAttributes(EntityAttributeCreationEvent event) {
        event.put(UNDEAD_CREWMATE.get(), UndeadCrewmate.createAttributes().build());
    }

    private void attach(AttachCapabilitiesEvent<Entity> event) {
        if (!(event.getObject() instanceof Player)) return;
        PowderPouch.Provider provider = new PowderPouch.Provider();
        event.addCapability(new ResourceLocation(MOD_ID, "powder_pouch"), provider);
        event.addListener(provider::invalidate);
    }

    private void clonePlayer(PlayerEvent.Clone event) {
        Player old = event.getOriginal();
        old.reviveCaps();
        try {
            old.getCapability(PowderPouch.CAPABILITY).ifPresent(previous ->
                    event.getEntity().getCapability(PowderPouch.CAPABILITY).ifPresent(current -> {
                        current.deserializeNBT(previous.serializeNBT());
                        if (event.isWasDeath()) {
                            current.dirtyTacticsArmed = false;
                            current.bloodBuffUntil = 0L;
                            current.bloodCooldownUntil = 0L;
                            current.bloodHuntUntil = 0L;
                            current.bloodLockedTarget = null;
                            current.bloodFlightUntil = 0L;
                            current.bloodFlightWasMayFly = false;
                            current.bloodOverdriveUntil = 0L;
                            current.bloodLastDegenerationTick = 0L;
                            current.bloodLastEnemyHitTick = 0L;
                        }
                        if (event.isWasDeath() && !old.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
                            current.setStackInSlot(0, ItemStack.EMPTY);
                        }
                    }));
        } finally {
            old.invalidateCaps();
        }
    }

    private void dropPowder(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;
        player.getCapability(PowderPouch.CAPABILITY).ifPresent(pouch -> {
            ItemStack powder = pouch.extractItem(0, PowderPouch.LIMIT, false);
            if (!powder.isEmpty()) {
                ItemEntity drop = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), powder);
                drop.setDefaultPickUpDelay();
                event.getDrops().add(drop);
            }
        });
    }

    private void commands(RegisterCommandsEvent event) {
        // These are private Origin plumbing commands. Keeping the root command
        // unrestricted lets Connector/Apoli invoke them reliably for ordinary
        // players when an Origin keybind fires.
        event.getDispatcher().register(Commands.literal("elijah")
                .then(Commands.literal("pouch").executes(context -> openPouch(context.getSource())))
                .then(Commands.literal("fire").executes(context -> fire(context.getSource())))
                .then(Commands.literal("dirty_tactics").executes(context -> PirateAbilities.armDirtyTactics(context.getSource())))
                .then(Commands.literal("crew").executes(context -> summonCrew(context.getSource())))
                .then(Commands.literal("blood_rush").executes(context -> BloodAbilities.activateBloodRush(context.getSource())))
                .then(Commands.literal("blood_overdrive").executes(context -> BloodAbilities.triggerOverdrive(context.getSource())))
                .then(Commands.literal("blood_hunt").executes(context -> BloodAbilities.activateHunt(context.getSource())))
                .then(Commands.literal("blood_wings").executes(context -> BloodAbilities.activateWings(context.getSource())))
                .then(Commands.literal("sea_on").executes(context -> PirateAbilities.setWisdom(context.getSource(), true)))
                .then(Commands.literal("sea_off").executes(context -> PirateAbilities.setWisdom(context.getSource(), false)))
                .then(Commands.literal("unload").executes(context -> unload(context.getSource()))));
    }

    private int openPouch(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!player.isAlive() || player.isSpectator() || BloodAbilities.isHuntActive(player)) return 0;
        player.getCapability(PowderPouch.CAPABILITY).ifPresent(pouch ->
                NetworkHooks.openScreen(player, new SimpleMenuProvider(
                        (id, inventory, ignored) -> new PowderMenu(id, inventory, pouch),
                        Component.translatable("container.elijah.powder_pouch"))));
        return 1;
    }

    private int fire(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!player.isAlive() || player.isSpectator() || BloodAbilities.isHuntActive(player)) return 0;
        PowderPouch pouch = player.getCapability(PowderPouch.CAPABILITY).orElse(null);
        if (pouch == null) return 0;
        ServerLevel level = player.serverLevel();
        long now = level.getServer().overworld().getGameTime();
        if (now < pouch.nextShotTick) return 0;
        int count = pouch.getStackInSlot(0).getCount();
        if (count == 0) {
            player.displayClientMessage(Component.literal("Powder pouch empty!").withStyle(ChatFormatting.GOLD), true);
            level.playSound(null, player.blockPosition(), SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.5F, 1.2F);
            return 0;
        }
        Vec3 aim = player.getLookAngle().normalize();
        double physicalAttackDamage = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double spellPower = currentSpellPower(player);
        float damage = (float) (PirateConfig.BASE_DAMAGE.get()
                + PirateConfig.DAMAGE_PER_POWDER.get() * count
                + spellPower * PirateConfig.SPELL_POWER_DAMAGE_SCALE.get()
                + physicalAttackDamage * PirateConfig.PHYSICAL_DAMAGE_SCALE.get());
        FlintlockBall ball = new FlintlockBall(level, player, damage, PirateConfig.EFFECT_TICKS.get());
        // Start at the eyes, just below the crosshair; swept collision prevents tunnelling.
        ball.setPos(player.getX(), player.getEyeY() - 0.1, player.getZ());
        ball.shoot(aim.x, aim.y, aim.z, 3.5F, 0.15F);
        if (!level.addFreshEntity(ball)) return 0;
        pouch.extractItem(0, count, false);
        pouch.nextShotTick = now + PirateConfig.COOLDOWN_TICKS.get();
        player.containerMenu.broadcastChanges();

        double strength = PirateConfig.BASE_RECOIL.get() + PirateConfig.RECOIL_PER_POWDER.get() * count;
        Vec3 velocity = player.getDeltaMovement().add(aim.scale(-strength))
                .add(0, player.onGround() ? 0.08 : 0, 0);
        // Keep server and client velocity inside the vanilla motion-packet range.
        player.setDeltaMovement(new Vec3(clampVelocity(velocity.x), clampVelocity(velocity.y), clampVelocity(velocity.z)));
        player.hasImpulse = true;
        player.hurtMarked = true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));

        Vec3 muzzle = player.getEyePosition().add(aim.scale(0.4));
        level.sendParticles(ParticleTypes.SMOKE, muzzle.x, muzzle.y - 0.1, muzzle.z, 5 + count, 0.06, 0.06, 0.06, 0.03);
        level.sendParticles(ParticleTypes.FLAME, muzzle.x, muzzle.y - 0.1, muzzle.z, 2, 0.02, 0.02, 0.02, 0.01);
        level.playSound(null, player.blockPosition(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1.0F, 0.8F);
        level.playSound(null, player.blockPosition(), SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 0.8F, 0.65F);
        player.displayClientMessage(Component.literal("Flintlock: " + count + " powder fired").withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private int summonCrew(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!player.isAlive() || player.isSpectator() || BloodAbilities.isHuntActive(player)) return 0;
        ServerLevel level = player.serverLevel();
        int max = PirateConfig.CREW_MAX_COUNT.get();
        int current = level.getEntitiesOfClass(UndeadCrewmate.class, player.getBoundingBox().inflate(64.0D),
                crew -> player.getUUID().equals(crew.getOwnerId()) && crew.isAlive()).size();
        if (current >= max) {
            player.displayClientMessage(Component.literal("Your undead crew is already at its limit.")
                    .withStyle(ChatFormatting.GOLD), true);
            return 0;
        }
        // Origins supplies four charges; one key press always spends one and
        // summons exactly one crewmate.
        int amount = 1;
        LivingEntity target = player.getLastHurtMob();
        if (target == null || !(target instanceof Mob) || !target.isAlive()) target = player.getLastHurtByMob();
        for (int i = 0; i < amount; i++) {
            double angle = (Math.PI * 2.0D * (current % max)) / Math.max(1, max);
            double x = player.getX() + Math.cos(angle) * 1.35D;
            double z = player.getZ() + Math.sin(angle) * 1.35D;
            UndeadCrewmate crew = UNDEAD_CREWMATE.get().create(level);
            if (crew == null) continue;
            crew.moveTo(x, player.getY(), z, player.getYRot(), 0.0F);
            crew.setOwnerId(player.getUUID());
            crew.equipHonshu();
            // Snapshot the summoner's current combat stats. Account for the
            // Honshu's own held-item modifier so total crew attack remains 80%.
            AttributeInstance crewHealth = crew.getAttribute(Attributes.MAX_HEALTH);
            if (crewHealth != null) {
                double desiredHealth = Math.max(1.0D, player.getMaxHealth() * 0.80D);
                double existingHealthBonus = crewHealth.getValue() - crewHealth.getBaseValue();
                crewHealth.setBaseValue(Math.max(1.0D, desiredHealth - existingHealthBonus));
                crew.setHealth(crew.getMaxHealth());
            }
            AttributeInstance crewAttack = crew.getAttribute(Attributes.ATTACK_DAMAGE);
            if (crewAttack != null) {
                double desiredAttack = Math.max(0.0D, player.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.80D);
                double heldItemBonus = crewAttack.getValue() - crewAttack.getBaseValue();
                crewAttack.setBaseValue(Math.max(0.0D, desiredAttack - heldItemBonus));
            }
            AttributeInstance crewSpeed = crew.getAttribute(Attributes.ATTACK_SPEED);
            if (crewSpeed != null) {
                double desiredSpeed = PirateConfig.CREW_ATTACK_SPEED.get();
                double heldItemBonus = crewSpeed.getValue() - crewSpeed.getBaseValue();
                crewSpeed.setBaseValue(Math.max(0.1D, desiredSpeed - heldItemBonus));
            }
            if (target instanceof Mob mob && mob.isAlive()) crew.setTarget(mob);
            crew.setCustomName(Component.translatable("entity.elijah.undead_crewmate"));
            crew.setCustomNameVisible(false);
            crew.finalizeSpawn(level, level.getCurrentDifficultyAt(player.blockPosition()),
                    net.minecraft.world.entity.MobSpawnType.MOB_SUMMONED, null, null);
            level.addFreshEntity(crew);
            // finalizeSpawn and third-party mob patches may clear AI state;
            // restore the remembered combat target after the entity is live.
            if (target instanceof Mob mob && mob.isAlive()) crew.setTarget(mob);
        }
        level.playSound(null, player.blockPosition(), SoundEvents.ZOMBIE_AMBIENT, SoundSource.PLAYERS, 0.8F, 0.65F);
        player.displayClientMessage(Component.literal("The undead crew answers the call! (" + amount + ")")
                .withStyle(ChatFormatting.GOLD), true);
        return amount;
    }

    private static double clampVelocity(double value) { return Math.max(-3.8, Math.min(3.8, value)); }

    private static double currentSpellPower(Player player) {
        Attribute spellPowerAttribute = ForgeRegistries.ATTRIBUTES.getValue(
                new ResourceLocation("irons_spellbooks", "spell_power"));
        if (spellPowerAttribute == null) return 0.0D;
        AttributeInstance instance = player.getAttribute(spellPowerAttribute);
        return instance == null ? 0.0D : instance.getValue();
    }

    private int unload(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BloodAbilities.clearTransient(player);
        if (player.containerMenu instanceof PowderMenu) player.closeContainer();
        player.getCapability(PowderPouch.CAPABILITY).ifPresent(pouch -> {
            pouch.dirtyTacticsArmed = false;
            pouch.wisdomOfTheSea = false;
            ItemStack powder = pouch.extractItem(0, PowderPouch.LIMIT, false);
            if (!powder.isEmpty()) {
                player.getInventory().add(powder);
                if (!powder.isEmpty()) player.drop(powder, false);
            }
        });
        return 1;
    }
}
