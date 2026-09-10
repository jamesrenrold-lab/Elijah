package com.jamesrenrold.elijah;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

public final class PowderMenu extends AbstractContainerMenu {
    private final PowderPouch pouch;
    private final Player owner;

    public PowderMenu(int id, Inventory inventory) {
        this(id, inventory, new PowderPouch());
    }

    public PowderMenu(int id, Inventory inventory, PowderPouch pouch) {
        super(ElijahPirate.POWDER_MENU.get(), id);
        this.pouch = pouch;
        this.owner = inventory.player;
        // Reserve: four gunpowder slots arranged as a 2x2 grid.
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 2; column++) {
                final int slot = PowderPouch.RESERVE_START + row * 2 + column;
                addSlot(new SlotItemHandler(pouch, slot, 20 + column * 18, 45 + row * 18) {
                    @Override
                    public int getMaxStackSize() { return PowderPouch.RESERVE_LIMIT; }

                    @Override
                    public int getMaxStackSize(ItemStack stack) { return PowderPouch.RESERVE_LIMIT; }
                });
            }
        }
        // Automatic generator output, moved left into the old load position.
        addSlot(new SlotItemHandler(pouch, PowderPouch.GENERATOR_SLOT, 80, 54) {
            @Override
            public boolean mayPlace(ItemStack stack) { return false; }

            @Override
            public int getMaxStackSize() { return PowderPouch.GENERATOR_LIMIT; }

            @Override
            public int getMaxStackSize(ItemStack stack) { return PowderPouch.GENERATOR_LIMIT; }
        });
        // Ready-to-fire chamber, moved to the old generator position.
        addSlot(new SlotItemHandler(pouch, PowderPouch.CHAMBER_SLOT, 122, 54) {
            @Override
            public int getMaxStackSize() { return PowderPouch.CHAMBER_LIMIT; }

            @Override
            public int getMaxStackSize(ItemStack stack) { return PowderPouch.CHAMBER_LIMIT; }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        20 + column * 18, 124 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 20 + column * 18, 182));
        }
    }

    public int powderCount() { return pouch.getStackInSlot(PowderPouch.CHAMBER_SLOT).getCount(); }

    public int reserveCount() {
        int count = 0;
        for (int slot = PowderPouch.RESERVE_START; slot < PowderPouch.GENERATOR_SLOT; slot++) {
            count += pouch.getStackInSlot(slot).getCount();
        }
        return count;
    }

    public int generatorCount() { return pouch.getStackInSlot(PowderPouch.GENERATOR_SLOT).getCount(); }

    @Override
    public boolean stillValid(Player player) {
        return player == owner && player.isAlive() && !player.isSpectator();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < PowderPouch.TOTAL_SLOTS) {
            if (!moveItemStackTo(stack, PowderPouch.TOTAL_SLOTS, slots.size(), true)) return ItemStack.EMPTY;
        } else if (pouch.isItemValid(PowderPouch.CHAMBER_SLOT, stack)) {
            // Shift-clicking powder fills the chamber first, then the reserve grid.
            ItemStack remainder = pouch.insertItem(PowderPouch.CHAMBER_SLOT, stack, false);
            for (int reserve = PowderPouch.RESERVE_START;
                 !remainder.isEmpty() && reserve < PowderPouch.GENERATOR_SLOT; reserve++) {
                remainder = pouch.insertItem(reserve, remainder, false);
            }
            if (remainder.getCount() == stack.getCount()) return ItemStack.EMPTY;
            stack.setCount(remainder.getCount());
        } else if (index < 37) {
            if (!moveItemStackTo(stack, 37, slots.size(), false)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, PowderPouch.TOTAL_SLOTS, 37, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }
}
