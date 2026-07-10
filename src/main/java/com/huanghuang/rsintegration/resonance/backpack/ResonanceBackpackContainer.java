package com.huanghuang.rsintegration.resonance.backpack;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class ResonanceBackpackContainer extends AbstractContainerMenu {

    static final int DISK_SLOTS = 36;
    private static final int DISK_ROWS = 4;
    private static final int INV_SLOTS = 36;

    private final Container diskInventory;
    final ResonanceDiskWrapper disk;

    public ResonanceBackpackContainer(int containerId, Inventory playerInv,
                                      ResonanceDiskWrapper disk) {
        super(ModItems.RESONANCE_BACKPACK.get(), containerId);
        this.disk = disk;
        this.diskInventory = new ResonanceDiskInventory(disk);
        layoutSlots(playerInv, true);
    }

    public ResonanceBackpackContainer(int containerId, Inventory playerInv,
                                      FriendlyByteBuf buf) {
        super(ModItems.RESONANCE_BACKPACK.get(), containerId);
        this.disk = null;
        this.diskInventory = new SimpleContainer(DISK_SLOTS);
        layoutSlots(playerInv, false);
    }

    private void layoutSlots(Inventory playerInv, boolean serverSide) {
        int startX = 8;

        // Resonance backpack: 3 rows of 9 (y=18,36,54) + 1 hotbar row (y=76, 4px gap)
        for (int row = 0; row < 3; row++) {
            int y = 18 + row * 18;
            for (int col = 0; col < 9; col++) {
                if (serverSide && disk != null) {
                    this.addSlot(new ResonanceSlot(diskInventory,
                            row * 9 + col, startX + col * 18, y, disk));
                } else {
                    this.addSlot(new Slot(diskInventory,
                            row * 9 + col, startX + col * 18, y));
                }
            }
        }
        // Backpack hotbar row (indices 27-35)
        int bpHotY = 76;
        for (int col = 0; col < 9; col++) {
            if (serverSide && disk != null) {
                this.addSlot(new ResonanceSlot(diskInventory,
                        27 + col, startX + col * 18, bpHotY, disk));
            } else {
                this.addSlot(new Slot(diskInventory,
                        27 + col, startX + col * 18, bpHotY));
            }
        }

        // Player inventory: 3 rows — y=107,125,143
        int invY = 107;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9,
                        startX + col * 18, invY + row * 18));
            }
        }

        // Player hotbar — y=165 (4px gap below player inventory)
        int hotY = 165;
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, startX + col * 18, hotY));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack stack = this.slots.get(index).getItem();
        if (stack.isEmpty()) return ItemStack.EMPTY;

        ItemStack original = stack.copy();
        if (index < DISK_SLOTS) {
            if (!moveItemStackTo(stack, DISK_SLOTS, DISK_SLOTS + INV_SLOTS, true))
                return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(stack, 0, DISK_SLOTS, false))
                return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) this.slots.get(index).set(ItemStack.EMPTY);
        else this.slots.get(index).setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player player) { return true; }

    public int getStoredCount() {
        if (disk != null) return disk.getStored();
        int count = 0;
        for (int i = 0; i < DISK_SLOTS; i++)
            if (!diskInventory.getItem(i).isEmpty()) count++;
        return count;
    }

    public int getCapacity() {
        if (disk != null) return disk.getCapacity();
        return 2304;
    }
}
