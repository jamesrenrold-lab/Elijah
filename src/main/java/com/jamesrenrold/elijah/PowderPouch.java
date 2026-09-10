package com.jamesrenrold.elijah;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.ItemStackHandler;

/** A dedicated gunpowder inventory; never exposes the player's normal item-handler capability. */
@Mod.EventBusSubscriber(modid = ElijahPirate.MOD_ID)
public final class PowderPouch extends ItemStackHandler {
    /** The flintlock's ready-to-fire chamber. */
    public static final int CHAMBER_SLOT = 0;
    public static final int CHAMBER_LIMIT = 9;
    /** Eight reserve slots, displayed as a 4x2 grid. */
    public static final int RESERVE_START = 1;
    public static final int RESERVE_SLOTS = 8;
    public static final int RESERVE_LIMIT = 9;
    /** Generated powder output; it cannot be manually filled. */
    public static final int GENERATOR_SLOT = RESERVE_START + RESERVE_SLOTS;
    public static final int GENERATOR_LIMIT = 5;
    public static final int TOTAL_SLOTS = GENERATOR_SLOT + 1;
    /** Kept as an alias for older code/config compatibility. */
    public static final int LIMIT = CHAMBER_LIMIT;
    private static final long GENERATOR_INTERVAL_TICKS = 30L * 20L;
    public static final Capability<PowderPouch> CAPABILITY =
            CapabilityManager.get(new CapabilityToken<>() {});
    public long nextShotTick;
    public boolean dirtyTacticsArmed;
    public long dirtyTacticsReadyAt;
    public boolean wisdomOfTheSea;
    public int xpBonusRemainder;
    public UUID lastCombatTarget;
    public long lastCombatTargetTick;
    public long bloodBuffUntil;
    public long bloodCooldownUntil;
    public long bloodHuntUntil;
    public UUID bloodLockedTarget;
    public long bloodFlightUntil;
    public boolean bloodFlightWasMayFly;
    public long bloodOverdriveUntil;
    public long bloodLastDegenerationTick;
    public long bloodLastEnemyHitTick;
    public long bloodAllowLifestealUntil;
    public long nextGeneratorTick;

    public PowderPouch() { super(TOTAL_SLOTS); }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return slot >= CHAMBER_SLOT && slot < GENERATOR_SLOT && stack.is(Items.GUNPOWDER);
    }

    @Override
    public int getSlotLimit(int slot) {
        if (slot == GENERATOR_SLOT) return GENERATOR_LIMIT;
        if (slot >= RESERVE_START && slot < GENERATOR_SLOT) return RESERVE_LIMIT;
        return CHAMBER_LIMIT;
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        if (!stack.isEmpty() && (!isItemValid(slot, stack) || stack.getCount() > getSlotLimit(slot))) {
            throw new IllegalArgumentException("The powder pouch accepts only gunpowder in its storage slots.");
        }
        super.setStackInSlot(slot, stack);
    }

    /** Adds one generated powder without allowing the player to fill this slot. */
    private void generateOnePowder() {
        ItemStack output = getStackInSlot(GENERATOR_SLOT);
        if (output.isEmpty()) {
            output = new ItemStack(Items.GUNPOWDER, 1);
        } else if (!output.is(Items.GUNPOWDER) || output.getCount() >= GENERATOR_LIMIT) {
            return;
        } else {
            output = output.copy();
            output.grow(1);
        }
        output.setCount(Math.min(GENERATOR_LIMIT, output.getCount()));
        super.setStackInSlot(GENERATOR_SLOT, output);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        player.getCapability(CAPABILITY).ifPresent(pouch -> pouch.tickGenerator(player.serverLevel().getGameTime()));
    }

    private void tickGenerator(long now) {
        if (nextGeneratorTick <= 0L) {
            nextGeneratorTick = now + GENERATOR_INTERVAL_TICKS;
            return;
        }
        if (now < nextGeneratorTick) return;
        generateOnePowder();
        nextGeneratorTick = now + GENERATOR_INTERVAL_TICKS;
    }

    public void clearPowder() {
        for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
            super.setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = super.serializeNBT();
        tag.putLong("NextShotTick", nextShotTick);
        tag.putBoolean("DirtyTacticsArmed", dirtyTacticsArmed);
        tag.putLong("DirtyTacticsReadyAt", dirtyTacticsReadyAt);
        tag.putBoolean("WisdomOfTheSea", wisdomOfTheSea);
        tag.putInt("XpBonusRemainder", xpBonusRemainder);
        if (lastCombatTarget != null) tag.putUUID("LastCombatTarget", lastCombatTarget);
        tag.putLong("LastCombatTargetTick", lastCombatTargetTick);
        tag.putLong("BloodBuffUntil", bloodBuffUntil);
        tag.putLong("BloodCooldownUntil", bloodCooldownUntil);
        tag.putLong("BloodHuntUntil", bloodHuntUntil);
        if (bloodLockedTarget != null) tag.putUUID("BloodLockedTarget", bloodLockedTarget);
        tag.putLong("BloodFlightUntil", bloodFlightUntil);
        tag.putBoolean("BloodFlightWasMayFly", bloodFlightWasMayFly);
        tag.putLong("BloodOverdriveUntil", bloodOverdriveUntil);
        tag.putLong("BloodLastDegenerationTick", bloodLastDegenerationTick);
        tag.putLong("BloodLastEnemyHitTick", bloodLastEnemyHitTick);
        tag.putLong("NextGeneratorTick", nextGeneratorTick);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        nextShotTick = tag.getLong("NextShotTick");
        dirtyTacticsArmed = tag.getBoolean("DirtyTacticsArmed");
        dirtyTacticsReadyAt = tag.getLong("DirtyTacticsReadyAt");
        wisdomOfTheSea = tag.getBoolean("WisdomOfTheSea");
        xpBonusRemainder = Math.floorMod(tag.getInt("XpBonusRemainder"), 10);
        lastCombatTarget = tag.hasUUID("LastCombatTarget") ? tag.getUUID("LastCombatTarget") : null;
        lastCombatTargetTick = tag.getLong("LastCombatTargetTick");
        bloodBuffUntil = tag.getLong("BloodBuffUntil");
        bloodCooldownUntil = tag.getLong("BloodCooldownUntil");
        bloodHuntUntil = tag.getLong("BloodHuntUntil");
        bloodLockedTarget = tag.hasUUID("BloodLockedTarget") ? tag.getUUID("BloodLockedTarget") : null;
        bloodFlightUntil = tag.getLong("BloodFlightUntil");
        bloodFlightWasMayFly = tag.getBoolean("BloodFlightWasMayFly");
        bloodOverdriveUntil = tag.getLong("BloodOverdriveUntil");
        bloodLastDegenerationTick = tag.getLong("BloodLastDegenerationTick");
        bloodLastEnemyHitTick = tag.getLong("BloodLastEnemyHitTick");
        nextGeneratorTick = tag.getLong("NextGeneratorTick");
        super.deserializeNBT(tag);
        // Sanitize old or malformed data while preserving the old chamber slot.
        for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
            ItemStack stack = getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            if (slot == GENERATOR_SLOT || !isItemValid(slot, stack)) {
                super.setStackInSlot(slot, ItemStack.EMPTY);
                continue;
            }
            if (stack.getCount() > getSlotLimit(slot)) {
                ItemStack limited = stack.copy();
                limited.setCount(getSlotLimit(slot));
                super.setStackInSlot(slot, limited);
            }
        }
    }

    public static final class Provider implements ICapabilitySerializable<CompoundTag> {
        private final PowderPouch pouch = new PowderPouch();
        private final LazyOptional<PowderPouch> optional = LazyOptional.of(() -> pouch);

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
            return capability == CAPABILITY ? optional.cast() : LazyOptional.empty();
        }

        @Override
        public CompoundTag serializeNBT() { return pouch.serializeNBT(); }

        @Override
        public void deserializeNBT(CompoundTag tag) { pouch.deserializeNBT(tag); }

        public void invalidate() { optional.invalidate(); }
    }
}

