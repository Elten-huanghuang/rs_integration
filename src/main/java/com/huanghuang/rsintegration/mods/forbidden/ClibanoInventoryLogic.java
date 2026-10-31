package com.huanghuang.rsintegration.mods.forbidden;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.mods.vanilla.VanillaFurnaceFuelPolicy;
import com.huanghuang.rsintegration.util.InsertedStackDelta;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** 炽炉双通道的槽位规划、投料与归属核算。 */
public final class ClibanoInventoryLogic {

    public static final int ENHANCER_SLOT = 0;
    public static final int SOUL_SLOT = 1;
    public static final int FUEL_SLOT = 2;
    public static final int FIRST_INPUT_SLOT = 3;
    public static final int SECOND_INPUT_SLOT = 4;
    public static final int FIRST_OUTPUT_SLOT = 5;
    public static final int SECOND_OUTPUT_SLOT = 6;

    private ClibanoInventoryLogic() {}

    public static int chooseInputSlot(ItemStack first, ItemStack second,
                                      int firstProgress, int secondProgress) {
        if (firstProgress <= 0 && (first == null || first.isEmpty())) return FIRST_INPUT_SLOT;
        if (secondProgress <= 0 && (second == null || second.isEmpty())) return SECOND_INPUT_SLOT;
        return -1;
    }

    public static int bufferedOperationCapacity(int configuredLimit, int inputPerOperation,
                                                int inputCapacity, int outputPerOperation,
                                                int outputCapacity) {
        if (configuredLimit <= 0 || inputPerOperation <= 0 || inputCapacity <= 0
                || outputPerOperation <= 0 || outputCapacity <= 0) return 0;
        return Math.max(0, Math.min(configuredLimit,
                Math.min(inputCapacity / inputPerOperation,
                        outputCapacity / outputPerOperation)));
    }

    public static InputBufferContract inputBufferContract(
            IItemHandler inventory, int firstProgress, int secondProgress, int configuredLimit,
            IngredientSpec spec, ItemStack prototype, ItemStack expectedOutput) {
        if (inventory == null || inventory.getSlots() < 7 || spec == null || spec.count() <= 0
                || prototype.isEmpty()
                || expectedOutput.isEmpty()) return InputBufferContract.none();
        // 原生炽炉优先合并同类产物，两个输入通道共享两个输出槽。
        int outputOperations = 0;
        for (int slot : new int[]{FIRST_OUTPUT_SLOT, SECOND_OUTPUT_SLOT}) {
            outputOperations += Math.min(inventory.getSlotLimit(slot),
                    expectedOutput.getMaxStackSize()) / expectedOutput.getCount();
        }
        int limit = Math.min(configuredLimit, outputOperations);
        List<InputBufferContract.InputSlot> inputs = new ArrayList<>(2);
        for (int slot : new int[]{FIRST_INPUT_SLOT, SECOND_INPUT_SLOT}) {
            int progress = slot == FIRST_INPUT_SLOT ? firstProgress : secondProgress;
            if (progress > 0 || !inventory.getStackInSlot(slot).isEmpty()) continue;
            int operations = Math.min(limit, Math.min(
                    inventory.getSlotLimit(slot), prototype.getMaxStackSize()) / spec.count());
            if (operations <= 0) continue;
            // 两个物理槽共用一条物料需求，份数由规划器分摊。
            inputs.add(new InputBufferContract.InputSlot(
                    "legacy:material:0", slot, prototype, spec.count(),
                    operations * spec.count(), spec.consumption()));
        }
        return new InputBufferContract(limit, inputs,
                List.of(new OutputContract.Port(
                        "forbidden_arcanus:clibano:output", null, expectedOutput,
                        expectedOutput.getCount(), OutputContract.Kind.PRIMARY,
                        OutputContract.Source.SLOT)));
    }

    public static boolean placeInputs(IItemHandler inputHandler, IItemHandler inventory,
                                      List<InputBufferPlan.InputSlot> inputs) {
        for (InputBufferPlan.InputSlot input : inputs) {
            if (!inventory.getStackInSlot(input.slot()).isEmpty()
                    || !inputHandler.insertItem(input.slot(), input.stack().copy(), true).isEmpty()) {
                return false;
            }
        }
        List<InputBufferPlan.InputSlot> inserted = new ArrayList<>(inputs.size());
        for (InputBufferPlan.InputSlot input : inputs) {
            ItemStack stack = input.stack();
            ItemStack remainder = inputHandler.insertItem(input.slot(), stack.copy(), false);
            ItemStack accepted = InsertedStackDelta.between(stack, remainder);
            if (!accepted.isEmpty()) inserted.add(new InputBufferPlan.InputSlot(
                    input.entryId(), input.slot(), accepted,
                    input.perOperation(), input.consumption()));
            if (!remainder.isEmpty()) {
                recoverInputs(inventory, inserted);
                return false;
            }
        }
        return true;
    }

    public static List<ItemStack> recoverInputs(
            IItemHandler inventory, List<InputBufferPlan.InputSlot> inputs) {
        List<ItemStack> recovered = new ArrayList<>();
        for (InputBufferPlan.InputSlot input : inputs) {
            ItemStack current = inventory.getStackInSlot(input.slot());
            if (!current.isEmpty() && ItemStack.isSameItemSameTags(current, input.stack())) {
                ItemStack removed = inventory.extractItem(input.slot(),
                        Math.min(input.stack().getCount(), current.getCount()), false);
                if (!removed.isEmpty()) recovered.add(removed.copy());
            }
        }
        return List.copyOf(recovered);
    }

    public static boolean hasRemainingInputs(
            IItemHandler inventory, List<InputBufferPlan.InputSlot> inputs) {
        for (InputBufferPlan.InputSlot input : inputs) {
            ItemStack current = inventory.getStackInSlot(input.slot());
            if (!current.isEmpty() && ItemStack.isSameItemSameTags(current, input.stack())) {
                return true;
            }
        }
        return false;
    }

    public static int countMatching(List<ItemStack> stacks, ItemStack expected) {
        if (expected == null || expected.isEmpty()) return 0;
        int count = 0;
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()
                    && ItemStack.isSameItemSameTags(stack, expected)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static int refundableAddedCount(ItemStack baseline, int addedCount, ItemStack current) {
        if (addedCount <= 0 || current == null || current.isEmpty()) return 0;
        int baselineCount = 0;
        if (baseline != null && !baseline.isEmpty()) {
            if (!ItemStack.isSameItemSameTags(baseline, current)) return 0;
            baselineCount = baseline.getCount();
        }
        return Math.min(addedCount, Math.max(0, current.getCount() - baselineCount));
    }

    public static boolean fireSatisfies(int currentOrdinal, int requiredOrdinal) {
        return currentOrdinal >= requiredOrdinal;
    }

    @Nullable
    public static VanillaFurnaceFuelPolicy.Selection selectFuel(
            List<ItemStack> candidates, List<? extends String> priorityIds,
            int requiredTicks, ToIntFunction<ItemStack> burnTime) {
        return VanillaFurnaceFuelPolicy.select(
                candidates, priorityIds, requiredTicks, burnTime);
    }

    public static int fuelToAdd(int requiredTicks, int singleFuelTicks, int existingCount) {
        int required = VanillaFurnaceFuelPolicy.requiredAmount(requiredTicks, singleFuelTicks);
        if (required == Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return Math.max(0, required - Math.max(0, existingCount));
    }
}
