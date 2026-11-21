package com.huanghuang.rsintegration.mods.eidolon;

import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.batch.OperationLifecycle;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EidolonRitualNativeOutputTest extends BootstrapTest {
    @Test
    void onlyTheRequestedRitualCanHandOffActualNbt() throws Exception {
        var delegate = new EidolonBatchDelegate();
        var ritualId = new ResourceLocation("test", "wanted_ritual");
        set(delegate, "isRitual", true);
        set(delegate, "ritualOutputId", ritualId);
        ItemStack preview = new ItemStack(Items.DIAMOND);
        preview.getOrCreateTag().putString("preview", "default");
        set(delegate, "pendingResult", preview);

        ItemStack output = new ItemStack(Items.DIAMOND);
        output.getOrCreateTag().putString("runtime", "from_reagent");
        try (var reception = TaskOutputReception.open()) {
            delegate.bindProduction(reception.writer());
            assertFalse(delegate.receiveRitualOutput(new ResourceLocation("test", "foreign"), output, true));
            assertFalse(delegate.receiveRitualOutput(ritualId, new ItemStack(Items.EMERALD), true));
            assertTrue(delegate.receiveRitualOutput(ritualId, output, true));
            assertFalse(delegate.receiveRitualOutput(ritualId, output, true));

            var result = new OperationLifecycle(delegate, reception).collect(null, 1, List::of);
            assertTrue(result.settlement().complete());
            assertEquals(1, result.stacks().size());
            assertTrue(ItemStack.matches(output, result.stacks().get(0)));
        }
    }

    private static void set(EidolonBatchDelegate delegate, String name, Object value) throws Exception {
        var field = EidolonBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(delegate, value);
    }
}
