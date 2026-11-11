package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeProgress;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeState;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.Reason;
import com.huanghuang.rsintegration.mixin.refinedstorage.MonitorAmountsAccessor;
import com.huanghuang.rsintegration.mixin.refinedstorage.MonitorItemAccessor;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.autocrafting.craftingmonitor.ICraftingMonitorElement;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonitorStepEntryTest extends BootstrapTest {
    @Test void processingCountsRemainOperationsAndNeverPretendToBeMachineTime() {
        var node = node(4, NodeState.RUNNING, Reason.NONE, 20, 100, 7);
        var entry = MonitorStepEntry.forRsi(snapshot(List.of(node))).get(0);
        assertEquals(0.2, entry.completedFraction());
        assertEquals(0, entry.elementIndex()); // 节点索引；点击时再按产物定位材料格。
        var counts = (TranslatableContents) entry.counts().getContents();
        assertEquals("rsi.monitor.step_counts", counts.getKey());
        assertArrayEquals(new Object[]{20, 100, 7}, counts.getArgs());
        var pending = (TranslatableContents) entry.tooltip().get(3).getContents();
        assertArrayEquals(new Object[]{73L}, pending.getArgs());
    }

    @Test void activeFilterKeepsBlockersAndRunningStepsAndSearchCanFindMachineOrRecipe() {
        var entries = MonitorStepEntry.forRsi(snapshot(List.of(
                node(1, NodeState.SUCCEEDED, Reason.NONE, 10, 10, 0),
                node(2, NodeState.BLOCKED, Reason.NO_BOUND_MACHINE, 0, 10, 0),
                node(3, NodeState.RUNNING, Reason.NONE, 2, 10, 1),
                node(4, NodeState.FAILED, Reason.OUTPUT_MISSING, 0, 10, 0))));
        assertEquals(List.of("node:2", "node:4", "node:3"),
                MonitorStepEntry.filter(entries, "", true).stream().map(MonitorStepEntry::key).toList());
        assertEquals(4, MonitorStepEntry.filter(entries, " FURNACE ", false).size());
        assertEquals("node:2", MonitorStepEntry.filter(entries, "TEST:STEP_2", true).get(0).key());
        assertTrue(MonitorStepEntry.filter(entries, "unknown material", false).isEmpty());
    }

    @Test void cancellingInFlightStepStaysVisibleUntilSettled() {
        var base = node(1, NodeState.RUNNING, Reason.PLAYER_CANCELLED, 3, 10, 2);
        var draining = new NodeProgress(base.nodeId(), NodeState.CANCELLED, base.recipeId(), base.modTypeId(),
                base.displayOutput(), 3, 10, 2, base.machineLabel(), base.reason(), "", true);
        var entries = MonitorStepEntry.filter(MonitorStepEntry.forRsi(snapshot(List.of(draining))), "", true);
        assertEquals(1, entries.size());
        assertEquals("rsi.monitor.draining", ((TranslatableContents) entries.get(0).state().getContents()).getKey());
    }

    @Test void nativeInputOutputAndScheduledCountsStayDistinctAndHaveNoInventedPercentage() {
        var element = nativeElement(Integer.MAX_VALUE, Integer.MAX_VALUE, 15, 4, 8);
        var entry = MonitorStepEntry.forNative(List.of(element)).get(0);
        assertEquals(-1, entry.completedFraction());
        assertTrue(entry.active());
        assertEquals(0, entry.priority());
        assertArrayEquals(new Object[]{"2147483647", "2147483647", "15"},
                ((TranslatableContents) entry.counts().getContents()).getArgs());
        assertEquals(element.getTooltip(), entry.tooltip());
    }

    @Test void storedOnlyNativeMaterialIsAvailableInAllButNotActive() {
        var entries = MonitorStepEntry.forNative(List.of(nativeElement(0, 0, 0, 0, 123)));
        assertTrue(MonitorStepEntry.filter(entries, "", true).isEmpty());
        assertEquals(1, MonitorStepEntry.filter(entries, "minecraft:diamond", false).size());
    }

    @Test void machineLineDistinguishesAssignedAndCandidateLocationsAndRetainsDimensionInDetails() {
        var running = MonitorStepEntry.forRsi(snapshot(List.of(node(1, NodeState.RUNNING, Reason.NONE, 0, 10, 1)))).get(0);
        var queued = MonitorStepEntry.forRsi(snapshot(List.of(node(2, NodeState.READY, Reason.MACHINE_BUSY, 0, 10, 0)))).get(0);
        assertEquals("rsi.monitor.step_machine", ((TranslatableContents) running.machine().getContents()).getKey());
        assertEquals("rsi.monitor.machine_candidate", ((TranslatableContents) queued.machine().getContents()).getKey());
        assertTrue(running.tooltip().stream().anyMatch(line -> line.getString().equals("furnace @ 1,2,3")));
        assertArrayEquals(new Object[]{Component.translatableWithFallback("rsi.batch.mod.vanilla_furnace", "vanilla furnace"), "1,2,3"},
                ((TranslatableContents) running.machine().getContents()).getArgs());
    }

    private static ICraftingMonitorElement nativeElement(int processing, int crafting, int scheduled, int missing, int stored) {
        var element = mock(ICraftingMonitorElement.class,
                withSettings().extraInterfaces(MonitorAmountsAccessor.class, MonitorItemAccessor.class));
        var counts = (MonitorAmountsAccessor) element;
        when(counts.rsi$processing()).thenReturn(processing);
        when(counts.rsi$crafting()).thenReturn(crafting);
        when(counts.rsi$scheduled()).thenReturn(scheduled);
        when(counts.rsi$missing()).thenReturn(missing);
        when(counts.rsi$stored()).thenReturn(stored);
        when(((MonitorItemAccessor) element).rsi$stack()).thenReturn(new ItemStack(Items.DIAMOND));
        when(element.getTooltip()).thenReturn(List.of(Component.literal("authoritative material details")));
        when(element.getId()).thenReturn(new ResourceLocation("refinedstorage", "item"));
        return element;
    }

    private static NodeProgress node(int id, NodeState state, Reason reason, int completed, int total, int running) {
        return new NodeProgress(id, state, "test:step_" + id, "vanilla_furnace", new ItemStack(Items.IRON_INGOT),
                completed, total, running, "furnace @ 1,2,3", reason, "", false);
    }

    private static CraftProgressSnapshot snapshot(List<NodeProgress> nodes) {
        return new CraftProgressSnapshot(UUID.randomUUID(), 1, CraftProgressSnapshot.Result.RUNNING,
                Reason.NONE, 0, nodes.size(), 1, null, nodes);
    }
}
