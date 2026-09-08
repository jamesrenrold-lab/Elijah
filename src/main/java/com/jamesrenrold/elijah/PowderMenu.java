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
        addSlot(new SlotItemHandler(pouch, 0, 80, 31) {
            @Override
            public int getMaxStackSize() { return PowderPouch.LIMIT; }

            @Override
            public int getMaxStackSize(ItemStack stack) { return PowderPouch.LIMIT; }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        8 + column * 18, 98 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, 156));
        }
    }

    public int powderCount() { return pouch.getStackInSlot(0).getCount(); }

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
        if (index == 0) {
            if (!moveItemStackTo(stack, 1, 37, true)) return ItemStack.EMPTY;
        } else if (pouch.isItemValid(0, stack)) {
            // Use the handler directly: it preserves any overflow in the source slot.
            ItemStack remainder = pouch.insertItem(0, stack, false);
            if (remainder.getCount() == stack.getCount()) return ItemStack.EMPTY;
            stack.setCount(remainder.getCount());
        } else if (index < 28) {
            if (!moveItemStackTo(stack, 28, 37, false)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 1, 28, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }
}
