package com.jamesrenrold.elijah;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.ItemStackHandler;

/** A dedicated inventory; never exposes the player's normal item-handler capability. */
public final class PowderPouch extends ItemStackHandler {
    public static final int LIMIT = 9;
    public static final Capability<PowderPouch> CAPABILITY =
            CapabilityManager.get(new CapabilityToken<>() {});
    public long nextShotTick;
    public boolean dirtyTacticsArmed;
    public long dirtyTacticsReadyAt;
    public boolean wisdomOfTheSea;
    public int xpBonusRemainder;
    public UUID lastCombatTarget;
    public long lastCombatTargetTick;

    public PowderPouch() { super(1); }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return slot == 0 && stack.is(Items.GUNPOWDER);
    }

    @Override
    public int getSlotLimit(int slot) { return LIMIT; }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        if (!stack.isEmpty() && (!isItemValid(slot, stack) || stack.getCount() > LIMIT)) {
            throw new IllegalArgumentException("The powder pouch holds at most nine gunpowder.");
        }
        super.setStackInSlot(slot, stack);
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
        // Enforce the one-slot layout even for malformed/old save data.
        super.setStackInSlot(0, ItemStack.EMPTY);
        ListTag items = tag.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompound(i);
            if (entry.getInt("Slot") != 0) continue;
            ItemStack stack = ItemStack.of(entry);
            if (!stack.is(Items.GUNPOWDER)) continue;
            stack.setCount(Math.min(LIMIT, stack.getCount()));
            super.setStackInSlot(0, stack);
            break;
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
