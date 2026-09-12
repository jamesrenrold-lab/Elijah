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
    /** Four reserve slots, displayed as a 2x2 grid. */
    public static final int RESERVE_START = 1;
    public static final int RESERVE_SLOTS = 4;
    public static final int RESERVE_LIMIT = 64;
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
    public boolean cursedFormActive;
    public long bloodBuffUntil;
    public long bloodCooldownUntil;
    public long bloodHuntUntil;
    public UUID bloodLockedTarget;
    public long bloodFlightUntil;
    public boolean bloodFlightWasMayFly;
    public long bloodOverdriveUntil;
    public long bloodExhaustedUntil;
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
        player.getCapability(CAPABILITY).ifPresent(pouch -> {
            if (pouch.tickGenerator(player.serverLevel().getGameTime())
                    && player.containerMenu instanceof PowderMenu menu) {
                // The generator is a capability slot rather than a vanilla
                // inventory slot; explicitly broadcast it so the client sees
                // new powder without clicking the output slot.
                menu.broadcastChanges();
            }
        });
    }

    private boolean tickGenerator(long now) {
        if (nextGeneratorTick <= 0L) {
            nextGeneratorTick = now + GENERATOR_INTERVAL_TICKS;
            return false;
        }
        if (now < nextGeneratorTick) return false;
        int before = getStackInSlot(GENERATOR_SLOT).getCount();
        generateOnePowder();
        nextGeneratorTick = now + GENERATOR_INTERVAL_TICKS;
        return getStackInSlot(GENERATOR_SLOT).getCount() != before;
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
        tag.putBoolean("CursedFormActive", cursedFormActive);
        tag.putLong("BloodBuffUntil", bloodBuffUntil);
        tag.putLong("BloodCooldownUntil", bloodCooldownUntil);
        tag.putLong("BloodHuntUntil", bloodHuntUntil);
        if (bloodLockedTarget != null) tag.putUUID("BloodLockedTarget", bloodLockedTarget);
        tag.putLong("BloodFlightUntil", bloodFlightUntil);
        tag.putBoolean("BloodFlightWasMayFly", bloodFlightWasMayFly);
        tag.putLong("BloodOverdriveUntil", bloodOverdriveUntil);
        tag.putLong("BloodExhaustedUntil", bloodExhaustedUntil);
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
        cursedFormActive = tag.getBoolean("CursedFormActive");
        bloodBuffUntil = tag.getLong("BloodBuffUntil");
        bloodCooldownUntil = tag.getLong("BloodCooldownUntil");
        bloodHuntUntil = tag.getLong("BloodHuntUntil");
        bloodLockedTarget = tag.hasUUID("BloodLockedTarget") ? tag.getUUID("BloodLockedTarget") : null;
        bloodFlightUntil = tag.getLong("BloodFlightUntil");
        bloodFlightWasMayFly = tag.getBoolean("BloodFlightWasMayFly");
        bloodOverdriveUntil = tag.getLong("BloodOverdriveUntil");
        bloodExhaustedUntil = tag.getLong("BloodExhaustedUntil");
        bloodLastDegenerationTick = tag.getLong("BloodLastDegenerationTick");
        bloodLastEnemyHitTick = tag.getLong("BloodLastEnemyHitTick");
        nextGeneratorTick = tag.getLong("NextGeneratorTick");
        // Do not call ItemStackHandler.deserializeNBT here: Forge resizes its internal
        // list to the serialized Size field. Read entries manually so both the old
        // one-slot pouch and the previous 10-slot pouch migrate safely.
        for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
            super.setStackInSlot(slot, ItemStack.EMPTY);
        }
        int serializedSize = tag.contains("Size", Tag.TAG_INT) ? tag.getInt("Size") : 1;
        ListTag items = tag.getList("Items", Tag.TAG_COMPOUND);
        for (int index = 0; index < items.size(); index++) {
            CompoundTag entry = items.getCompound(index);
            int serializedSlot = entry.getInt("Slot");
            CompoundTag stackTag = entry.contains("Stack", Tag.TAG_COMPOUND)
                    ? entry.getCompound("Stack") : entry;
            ItemStack stack = ItemStack.of(stackTag);
            if (stack.isEmpty()) continue;

            if (serializedSize > TOTAL_SLOTS) {
                // The previous build had eight reserve slots and put the generator
                // at slot 9. Consolidate its reserve powder into the new 2x2 grid.
                if (serializedSlot >= 1 && serializedSlot <= 8) {
                    int remaining = stack.is(Items.GUNPOWDER) ? stack.getCount() : 0;
                    for (int slot = RESERVE_START; remaining > 0 && slot < GENERATOR_SLOT; slot++) {
                        ItemStack existing = getStackInSlot(slot);
                        int room = existing.isEmpty() ? RESERVE_LIMIT
                                : (existing.is(Items.GUNPOWDER) ? RESERVE_LIMIT - existing.getCount() : 0);
                        if (room <= 0) continue;
                        int moved = Math.min(room, remaining);
                        if (existing.isEmpty()) super.setStackInSlot(slot, new ItemStack(Items.GUNPOWDER, moved));
                        else {
                            ItemStack merged = existing.copy();
                            merged.grow(moved);
                            super.setStackInSlot(slot, merged);
                        }
                        remaining -= moved;
                    }
                } else if (serializedSlot == 9 && stack.is(Items.GUNPOWDER)) {
                    ItemStack generator = stack.copy();
                    generator.setCount(Math.min(GENERATOR_LIMIT, generator.getCount()));
                    super.setStackInSlot(GENERATOR_SLOT, generator);
                } else if (serializedSlot == CHAMBER_SLOT) {
                    stack.setCount(Math.min(CHAMBER_LIMIT, stack.getCount()));
                    super.setStackInSlot(CHAMBER_SLOT, stack);
                }
            } else if (serializedSlot >= 0 && serializedSlot < TOTAL_SLOTS) {
                int limit = getSlotLimit(serializedSlot);
                stack.setCount(Math.min(limit, stack.getCount()));
                super.setStackInSlot(serializedSlot, stack);
            }
        }
        // Sanitize old or malformed data while preserving the old chamber slot.
        for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
            ItemStack stack = getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            // The generator is output-only, but its saved contents must still
            // be gunpowder. This also cleans malformed or legacy player data.
            if (!stack.is(Items.GUNPOWDER)) {
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
