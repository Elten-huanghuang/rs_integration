package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetyBatchDelegateTest extends BootstrapTest {

    private static class Research {}
    private static final class AddonResearch extends Research {}
    private static final class ResearchApi {
        public static boolean hasResearch(Object player, Research research) {
            return player != null && research != null;
        }
        public boolean instanceOnly(Object player, Research research) {
            return true;
        }
    }

    @Test
    void matchesRuntimeNbtOutputByItemType() {
        ItemStack expected = new ItemStack(Items.IRON_LEGGINGS);
        ItemStack enchanted = expected.copy();
        CompoundTag tag = new CompoundTag();
        tag.putString("runtime_enchantment", "present");
        enchanted.setTag(tag);

        assertTrue(IBatchDelegate.matchesProducedItem(enchanted, expected));
        assertFalse(IBatchDelegate.matchesProducedItem(
                new ItemStack(Items.IRON_CHESTPLATE), expected));
    }

    @Test
    void researchLookupAcceptsApiParameterSuperclass() throws Exception {
        Object player = new Object();
        AddonResearch research = new AddonResearch();

        Method method = GoetyBatchDelegate.findCompatibleStaticMethod(
                ResearchApi.class, "hasResearch", player, research);

        assertTrue(method != null && Boolean.TRUE.equals(method.invoke(null, player, research)));
    }

    @Test
    void researchLookupNeverSelectsInstanceMethod() {
        assertTrue(GoetyBatchDelegate.findCompatibleStaticMethod(
                ResearchApi.class, "instanceOnly", new Object(), new Research()) == null);
    }

    @Test
    void sacrificeDisplayAcceptsGoetyStringAndComponents() {
        assertEquals("Zombie", GoetyBatchDelegate.displayComponent("Zombie").getString());
        assertEquals("Skeleton", GoetyBatchDelegate
                .displayComponent(Component.literal("Skeleton")).getString());
        assertEquals("?", GoetyBatchDelegate.displayComponent(null).getString());
    }

    @Test
    void verifiedAltarWinsOverOtherCandidates() {
        assertEquals(GoetyBatchDelegate.PlanStructureOutcome.VERIFIED,
                GoetyBatchDelegate.summarizePlanStructureProbes(List.of(
                        GoetyBatchDelegate.PlanStructureProbe.STRUCTURE_MISMATCH,
                        GoetyBatchDelegate.PlanStructureProbe.VERIFIED,
                        GoetyBatchDelegate.PlanStructureProbe.UNLOADED)));
    }

    @Test
    void unloadedAltarDefersPreviewInsteadOfBlocking() {
        assertEquals(GoetyBatchDelegate.PlanStructureOutcome.DEFERRED_UNLOADED,
                GoetyBatchDelegate.summarizePlanStructureProbes(List.of(
                        GoetyBatchDelegate.PlanStructureProbe.STRUCTURE_MISMATCH,
                        GoetyBatchDelegate.PlanStructureProbe.UNLOADED)));
    }

    @Test
    void explicitStructureMismatchStillBlocks() {
        assertEquals(GoetyBatchDelegate.PlanStructureOutcome.STRUCTURE_MISMATCH,
                GoetyBatchDelegate.summarizePlanStructureProbes(List.of(
                        GoetyBatchDelegate.PlanStructureProbe.INVALID_BINDING,
                        GoetyBatchDelegate.PlanStructureProbe.STRUCTURE_MISMATCH)));
    }

    @Test
    void emptyCandidateListReportsNoBinding() {
        assertEquals(GoetyBatchDelegate.PlanStructureOutcome.NO_BINDING,
                GoetyBatchDelegate.summarizePlanStructureProbes(List.of()));
    }
}
