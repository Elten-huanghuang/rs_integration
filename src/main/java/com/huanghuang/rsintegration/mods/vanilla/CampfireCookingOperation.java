package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.progress.MachineCycleProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import javax.annotation.Nullable;

/** 只接管一次任务投料；原版负责烹饪和清空槽位。 */
public final class CampfireCookingOperation {
    public interface Access {
        int[] rsi$cookingProgress();
        int[] rsi$cookingTime();
        @Nullable CampfireCookingOperation rsi$getCookingOperation(int slot);
        void rsi$setCookingOperation(int slot, @Nullable CampfireCookingOperation operation);
    }

    private final CampfireBlockEntity campfire;
    private final Access access;
    private final int slot;
    private final ItemStack input;
    private final TaskOutputReception.Writer reception;
    private boolean completed;

    private CampfireCookingOperation(CampfireBlockEntity campfire, Access access, int slot,
                                    ItemStack input, TaskOutputReception.Writer reception) {
        this.campfire = campfire;
        this.access = access;
        this.slot = slot;
        this.input = input;
        this.reception = reception;
    }

    public static int freeSlot(CampfireBlockEntity campfire) {
        if (!(campfire instanceof Access access) || campfire.isRemoved()) return -1;
        for (int i = 0; i < campfire.getItems().size(); i++) {
            if (campfire.getItems().get(i).isEmpty() && access.rsi$getCookingOperation(i) == null) return i;
        }
        return -1;
    }

    public static OutputContract outputContract(ItemStack result) {
        return VanillaMachineBatchDelegate.vanillaPrimaryOutputContract(result, OutputContract.Source.WORLD, null);
    }

    @Nullable
    public static CampfireCookingOperation start(CampfireBlockEntity campfire, ItemStack material,
                                                 int cookTime, @Nullable TaskOutputReception.Writer reception) {
        if (reception == null || material.isEmpty() || material.getCount() != 1) return null;
        int slot = freeSlot(campfire);
        if (slot < 0 || !(campfire instanceof Access access)) return null;
        var operation = new CampfireCookingOperation(campfire, access, slot, material.copy(), reception);
        access.rsi$cookingProgress()[slot] = 0;
        access.rsi$cookingTime()[slot] = cookTime;
        campfire.getItems().set(slot, operation.input);
        access.rsi$setCookingOperation(slot, operation);
        return operation;
    }

    /** 仅从原版 cookTick 的产物出口调用；无配方时原版会掉回原料本身。 */
    public static boolean receive(CampfireBlockEntity campfire, int slot, ItemStack input, ItemStack actual) {
        if (!(campfire instanceof Access access)) return false;
        CampfireCookingOperation operation = access.rsi$getCookingOperation(slot);
        if (operation == null || !operation.ownsInput(campfire)
                || operation.input != input || actual == input || !operation.reception.tryHold(actual)) return false;
        operation.completed = true;
        operation.release();
        return true;
    }

    public boolean completed() {
        return completed;
    }

    public MachineCycleProgress progress() {
        return MachineCycleProgress.of(access.rsi$cookingProgress()[slot], access.rsi$cookingTime()[slot]);
    }

    public void updateCampfire(ServerLevel level) {
        BlockPos pos = campfire.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.LIT) && !state.getValue(BlockStateProperties.LIT)) {
            level.setBlock(pos, state.setValue(BlockStateProperties.LIT, true), 3);
        }
        campfire.setChanged();
        level.sendBlockUpdated(pos, state, level.getBlockState(pos), 3);
    }

    public boolean ownsInput(BlockEntity current) {
        return !completed && current == campfire && !campfire.isRemoved()
                && access.rsi$getCookingOperation(slot) == this
                && campfire.getItems().get(slot) == input && input.getCount() == 1;
    }

    public ItemStack cancel(BlockEntity current) {
        boolean owned = ownsInput(current);
        release();
        if (!owned) return ItemStack.EMPTY;
        campfire.getItems().set(slot, ItemStack.EMPTY);
        access.rsi$cookingProgress()[slot] = 0;
        access.rsi$cookingTime()[slot] = 0;
        if (campfire.getLevel() instanceof ServerLevel level) {
            BlockPos pos = campfire.getBlockPos();
            BlockState state = campfire.getBlockState();
            campfire.setChanged();
            level.sendBlockUpdated(pos, state, state, 3);
        }
        return input;
    }

    public void release() {
        if (access.rsi$getCookingOperation(slot) == this) access.rsi$setCookingOperation(slot, null);
    }
}
