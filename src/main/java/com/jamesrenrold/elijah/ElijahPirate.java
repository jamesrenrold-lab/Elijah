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
import net.minecraft.world.entity.MobCategory;
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

    public ElijahPirate() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        MENUS.register(bus);
        ENTITIES.register(bus);
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
        // Origins execute_command actions supply permission level 2. Ordinary players
        // can use their Origin keybinds, but cannot grant themselves the abilities by command.
        event.getDispatcher().register(Commands.literal("elijah")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("pouch").executes(context -> openPouch(context.getSource())))
                .then(Commands.literal("fire").executes(context -> fire(context.getSource())))
                .then(Commands.literal("unload").executes(context -> unload(context.getSource()))));
    }

    private int openPouch(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!player.isAlive() || player.isSpectator()) return 0;
        player.getCapability(PowderPouch.CAPABILITY).ifPresent(pouch ->
                NetworkHooks.openScreen(player, new SimpleMenuProvider(
                        (id, inventory, ignored) -> new PowderMenu(id, inventory, pouch),
                        Component.translatable("container.elijah.powder_pouch"))));
        return 1;
    }

    private int fire(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!player.isAlive() || player.isSpectator()) return 0;
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
        float damage = (float) (PirateConfig.BASE_DAMAGE.get() + PirateConfig.DAMAGE_PER_POWDER.get() * count);
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

    private static double clampVelocity(double value) { return Math.max(-3.8, Math.min(3.8, value)); }

    private int unload(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (player.containerMenu instanceof PowderMenu) player.closeContainer();
        player.getCapability(PowderPouch.CAPABILITY).ifPresent(pouch -> {
            ItemStack powder = pouch.extractItem(0, PowderPouch.LIMIT, false);
            if (!powder.isEmpty()) {
                player.getInventory().add(powder);
                if (!powder.isEmpty()) player.drop(powder, false);
            }
        });
        return 1;
    }
}
