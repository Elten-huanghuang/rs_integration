package com.huanghuang.rsintegration.mods.summoningrituals;

import com.huanghuang.rsintegration.crafting.plan.MachineCandidateView;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SummoningRitualRecipePolicyTest {
    @Test
    void loadedReadyAltarsAreOrderedByNearestDistance() {
        MachineCandidateView far = candidate(20);
        MachineCandidateView near = candidate(3);

        List<MachineCandidateView> ordered = SummoningRitualAltarBatchDelegate
                .orderPlanMachineCandidates(List.of(far, near),
                        new ResourceLocation("minecraft", "overworld"), 0.0, 64.0, 0.0);

        assertEquals(3, ordered.get(0).x());
        assertEquals(20, ordered.get(1).x());
    }

    private static MachineCandidateView candidate(int x) {
        return new MachineCandidateView("minecraft:overworld", x, 64, 0,
                new ItemStack(Items.STONE), MachineCandidateView.State.READY,
                Component.translatable("rsi.machine_candidate.ready"));
    }

    @Test
    void ritualOutputsNeverBecomeRecursiveIntermediateProducts() {
        SummoningRitualRecipeHandler handler = new SummoningRitualRecipeHandler();
        Recipe<?> unrelated = org.mockito.Mockito.mock(Recipe.class);
        assertFalse(handler.indexPrimaryOutput(unrelated));
        assertFalse(handler.hasDeterministicPrimaryOutput(unrelated));
    }

    @Test
    void altarDelegateDoesNotPublishWorldOutputs() {
        assertFalse(new SummoningRitualAltarBatchDelegate().publishesDeclaredGraphOutputs());
    }
}
