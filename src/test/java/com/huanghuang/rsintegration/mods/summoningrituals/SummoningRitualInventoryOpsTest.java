package com.huanghuang.rsintegration.mods.summoningrituals;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.List;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummoningRitualInventoryOpsTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void catalystUsesDynamicLastSlotAndPreservesNbt() {
        FakeHandler handler = new FakeHandler(3, 4096);
        ItemStack catalyst = new ItemStack(Items.STICK, 64);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", "ritual");
        catalyst.setTag(tag);

        List<SummoningRitualInventoryOps.Inserted> inserted = new ArrayList<>();
        ItemStack remainder = SummoningRitualInventoryOps.insertCatalyst(
                handler, catalyst, inserted);

        assertEquals(63, remainder.getCount());
        assertEquals(1, handler.getStackInSlot(2).getCount());
        assertTrue(ItemStack.isSameItemSameTags(catalyst, handler.getStackInSlot(2)));
        assertEquals(1, inserted.get(0).amount());
    }

    @Test
    void materialsMergeSameNbtBeforeUsingEmptySlotAndSupportOverstack() {
        FakeHandler handler = new FakeHandler(4, 4096);
        ItemStack existing = new ItemStack(Items.DIAMOND, 400);
        CompoundTag tag = new CompoundTag();
        tag.putString("grade", "ritual");
        existing.setTag(tag);
        handler.stacks[0] = existing;

        ItemStack incoming = new ItemStack(Items.DIAMOND, 300);
        incoming.setTag(tag.copy());
        List<SummoningRitualInventoryOps.Inserted> inserted = new ArrayList<>();
        ItemStack remainder = SummoningRitualInventoryOps.insertMaterial(
                handler, incoming, inserted);

        assertTrue(remainder.isEmpty());
        assertEquals(700, handler.getStackInSlot(0).getCount());
        assertTrue(handler.getStackInSlot(1).isEmpty());
    }

    @Test
    void differentNbtDoesNotMerge() {
        FakeHandler handler = new FakeHandler(3, 4096);
        ItemStack existing = new ItemStack(Items.DIAMOND, 5);
        CompoundTag oldTag = new CompoundTag();
        oldTag.putString("grade", "old");
        existing.setTag(oldTag);
        handler.stacks[0] = existing;

        ItemStack incoming = new ItemStack(Items.DIAMOND, 2);
        CompoundTag newTag = new CompoundTag();
        newTag.putString("grade", "new");
        incoming.setTag(newTag);
        ItemStack remainder = SummoningRitualInventoryOps.insertMaterial(
                handler, incoming, new ArrayList<>());

        assertTrue(remainder.isEmpty());
        assertEquals(5, handler.getStackInSlot(0).getCount());
        assertEquals(2, handler.getStackInSlot(1).getCount());
        assertTrue(ItemStack.isSameItemSameTags(incoming, handler.getStackInSlot(1)));
    }

    @Test
    void existingCatalystIsReusedAndNeverCountedAsMaterial() {
        FakeHandler handler = new FakeHandler(3, 4096);
        ItemStack catalyst = new ItemStack(Items.NETHER_STAR);
        CompoundTag catalystTag = new CompoundTag();
        catalystTag.putString("ritual", "ferrous");
        catalyst.setTag(catalystTag);
        handler.stacks[2] = catalyst.copy();

        List<IngredientSpec> inputs = List.of(
                new IngredientSpec(Ingredient.of(Items.DIAMOND), 2));
        List<IngredientSpec> missing = SummoningRitualAltarBatchDelegate.calculateMissing(
                handler, Ingredient.of(catalyst), inputs);

        assertEquals(1, missing.size());
        assertEquals(2, missing.get(0).count());
        assertEquals(DemandRole.CONSUMED, missing.get(0).role());
    }

    @Test
    void sixExecutionBatchRequestsTotalInputsMinusAltarInventoryAndReusesCatalyst() {
        FakeHandler handler = new FakeHandler(3, 4096);
        ItemStack catalyst = new ItemStack(Items.NETHER_STAR);
        handler.stacks[2] = catalyst.copy();
        handler.stacks[0] = new ItemStack(Items.DIAMOND, 4);

        List<IngredientSpec> missing = SummoningRitualAltarBatchDelegate.calculateMissing(
                handler, Ingredient.of(catalyst),
                List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 3)), 6);

        assertEquals(1, missing.size());
        assertEquals(14, missing.get(0).count());
        assertEquals(DemandRole.CONSUMED, missing.get(0).role());
    }

    @Test
    void missingCatalystIsPreparedForDynamicCatalystSlot() {
        FakeHandler handler = new FakeHandler(4, 4096);
        ItemStack catalyst = new ItemStack(Items.NETHER_STAR);
        List<IngredientSpec> missing = SummoningRitualAltarBatchDelegate.calculateMissing(
                handler, Ingredient.of(catalyst),
                List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 2)), 6);

        assertEquals(DemandRole.CATALYST, missing.get(0).role());
        assertEquals(1, missing.get(0).count());
        List<SummoningRitualInventoryOps.Inserted> inserted = new ArrayList<>();
        ItemStack catalystStack = catalyst.copy();
        assertTrue(SummoningRitualInventoryOps.insertCatalyst(
                handler, catalystStack, inserted).isEmpty());
        assertTrue(handler.getStackInSlot(handler.getSlots() - 1)
                .is(Items.NETHER_STAR));
        assertEquals(1, handler.getStackInSlot(handler.getSlots() - 1).getCount());
        assertTrue(handler.getStackInSlot(0).isEmpty());
    }

    @Test
    void materialReservationOrderCannotRouteCatalystAsOrdinaryInput() {
        List<IngredientSpec> required = List.of(
                new IngredientSpec(Ingredient.of(Items.DIAMOND), 1, DemandRole.CATALYST),
                new IngredientSpec(Ingredient.of(Items.ENDER_PEARL), 8));
        List<ItemStack> reservedOutOfOrder = List.of(
                new ItemStack(Items.ENDER_PEARL, 8), new ItemStack(Items.DIAMOND));

        List<ItemStack> ordered = SummoningRitualAltarBatchDelegate.orderMaterials(
                reservedOutOfOrder, required);

        assertEquals(Items.DIAMOND, ordered.get(0).getItem());
        assertEquals(Items.ENDER_PEARL, ordered.get(1).getItem());
    }

    @Test
    void recipeCatalystBoneIsOrderedBeforeDiamondMaterial() {
        List<IngredientSpec> required = List.of(
                new IngredientSpec(Ingredient.of(Items.BONE), 1, DemandRole.CATALYST),
                new IngredientSpec(Ingredient.of(Items.DIAMOND), 1));
        List<ItemStack> reservedOutOfOrder = List.of(
                new ItemStack(Items.DIAMOND), new ItemStack(Items.BONE));

        List<SummoningRitualAltarBatchDelegate.PreparedMaterial> ordered =
                SummoningRitualAltarBatchDelegate.orderPreparedMaterials(
                        reservedOutOfOrder, required, Ingredient.of(Items.BONE));

        assertNotNull(ordered);
        assertEquals(DemandRole.CATALYST, ordered.get(0).spec().role());
        assertEquals(Items.BONE, ordered.get(0).stack().getItem());
        assertEquals(DemandRole.CONSUMED, ordered.get(1).spec().role());
        assertEquals(Items.DIAMOND, ordered.get(1).stack().getItem());
    }

    @Test
    void sixExecutionBatchSupportsOverstackMaterialRequirement() {
        FakeHandler handler = new FakeHandler(3, 4096);
        handler.stacks[0] = new ItemStack(Items.DIAMOND, 50);

        List<IngredientSpec> missing = SummoningRitualAltarBatchDelegate.calculateMissing(
                handler, Ingredient.EMPTY,
                List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 100)), 6);

        assertEquals(550, missing.get(0).count());
        ItemStack batch = new ItemStack(Items.DIAMOND, missing.get(0).count());
        ItemStack remainder = SummoningRitualInventoryOps.insertMaterial(
                handler, batch, new ArrayList<>());
        assertTrue(remainder.isEmpty());
        assertEquals(600, handler.getStackInSlot(0).getCount());
    }

    @Test
    void strictCatalystNbtMismatchRequiresReplacement() {
        FakeHandler handler = new FakeHandler(2, 4096);
        ItemStack installed = new ItemStack(Items.NETHER_STAR);
        CompoundTag installedTag = new CompoundTag();
        installedTag.putString("ritual", "other");
        installed.setTag(installedTag);
        handler.stacks[1] = installed;

        ItemStack required = new ItemStack(Items.NETHER_STAR);
        CompoundTag requiredTag = new CompoundTag();
        requiredTag.putString("ritual", "ferrous");
        required.setTag(requiredTag);
        List<IngredientSpec> missing = SummoningRitualAltarBatchDelegate.calculateMissing(
                handler, StrictNBTIngredient.of(required), List.of());

        assertEquals(1, missing.size());
        assertEquals(DemandRole.CATALYST, missing.get(0).role());
    }

    @Test
    void catalystMisroutedByHandlerIsRepairedToDynamicLastSlot() {
        FakeHandler handler = new FakeHandler(5, 4096, true);
        ItemStack catalyst = new ItemStack(Items.GOLD_INGOT);
        List<SummoningRitualInventoryOps.Inserted> inserted = new ArrayList<>();

        ItemStack remainder = SummoningRitualInventoryOps.insertCatalyst(handler, catalyst, inserted);

        assertTrue(remainder.isEmpty());
        assertTrue(handler.getStackInSlot(4).is(Items.GOLD_INGOT));
        assertTrue(handler.getStackInSlot(0).isEmpty());
        assertEquals(1, inserted.get(0).amount());
    }

    private static final class FakeHandler implements IItemHandlerModifiable {
        private final ItemStack[] stacks;
        private final int limit;
        private final boolean misrouteLastSlot;

        private FakeHandler(int slots, int limit) {
            this(slots, limit, false);
        }

        private FakeHandler(int slots, int limit, boolean misrouteLastSlot) {
            this.stacks = new ItemStack[slots];
            this.limit = limit;
            this.misrouteLastSlot = misrouteLastSlot;
            for (int i = 0; i < slots; i++) stacks[i] = ItemStack.EMPTY;
        }

        @Override public int getSlots() { return stacks.length; }
        @Override public ItemStack getStackInSlot(int slot) { return stacks[slot]; }
        @Override public int getSlotLimit(int slot) { return limit; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            int targetSlot = misrouteLastSlot && slot == stacks.length - 1 && !simulate ? 0 : slot;
            ItemStack current = stacks[targetSlot];
            if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, stack)) return stack.copy();
            int room = current.isEmpty() ? limit : Math.max(0, limit - current.getCount());
            int accepted = Math.min(room, stack.getCount());
            if (!simulate && accepted > 0) {
                if (current.isEmpty()) stacks[targetSlot] = stack.copyWithCount(accepted);
                else current.grow(accepted);
            }
            return accepted == stack.getCount()
                    ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - accepted);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack current = stacks[slot];
            if (current.isEmpty() || amount <= 0) return ItemStack.EMPTY;
            int count = Math.min(amount, current.getCount());
            ItemStack result = current.copyWithCount(count);
            if (!simulate) {
                current.shrink(count);
                if (current.isEmpty()) stacks[slot] = ItemStack.EMPTY;
            }
            return result;
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            stacks[slot] = stack.copy();
        }
    }
}
