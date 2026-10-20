package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MagicResourceTaskLedgerTest extends BootstrapTest {
    @Test
    void pendingInjectionSurvivesSaveAndReload() {
        MagicResourceTaskLedger ledger = new MagicResourceTaskLedger();
        UUID taskId = UUID.randomUUID();
        ledger.recordTask(taskId, List.of(
                new MagicResourceCost(MagicResourceKind.SOURCE, 120)));
        ledger.recordInjection(taskId, MagicResourceKind.SOURCE,
                new ResourceLocation("minecraft", "overworld"), new BlockPos(4, 65, 9), 80);

        CompoundTag saved = ledger.save(new CompoundTag());
        assertEquals(1, saved.getList("tasks", Tag.TAG_COMPOUND).size());
        assertEquals(1, saved.getList("injections", Tag.TAG_COMPOUND).size());

        MagicResourceTaskLedger loaded = new MagicResourceTaskLedger(saved);
        CompoundTag reloaded = loaded.save(new CompoundTag());
        assertEquals(1, reloaded.getList("tasks", Tag.TAG_COMPOUND).size());
        CompoundTag injection = reloaded.getList("injections", Tag.TAG_COMPOUND)
                .getCompound(0);
        assertEquals(taskId, injection.getUUID("task"));
        assertEquals("SOURCE", injection.getString("kind"));
        assertEquals(80, injection.getLong("amount"));
        assertEquals("ACTIVE", injection.getString("state"));
    }

    @Test
    void closingTaskClearsItsReservationSummary() {
        MagicResourceTaskLedger ledger = new MagicResourceTaskLedger();
        UUID taskId = UUID.randomUUID();
        ledger.recordTask(taskId, List.of(new MagicResourceCost(MagicResourceKind.MANA, 50)));
        ledger.taskClosed(taskId);

        CompoundTag saved = ledger.save(new CompoundTag());
        assertFalse(saved.getList("tasks", Tag.TAG_COMPOUND).stream().findAny().isPresent());
    }

    @Test
    void readsLegacyIntAmountRecords() {
        CompoundTag saved = new CompoundTag();
        CompoundTag task = new CompoundTag();
        task.putUUID("id", UUID.randomUUID());
        CompoundTag resources = new CompoundTag();
        resources.putInt(MagicResourceKind.MANA.id(), Integer.MAX_VALUE);
        task.put("resources", resources);
        ListTag tasks = new ListTag();
        tasks.add(task);
        saved.put("tasks", tasks);

        MagicResourceTaskLedger loaded = new MagicResourceTaskLedger(saved);
        CompoundTag copy = loaded.save(new CompoundTag());
        CompoundTag amount = copy.getList("tasks", Tag.TAG_COMPOUND)
                .getCompound(0).getCompound("resources");
        assertEquals(Integer.MAX_VALUE, amount.getLong(MagicResourceKind.MANA.id()));
    }

    @Test
    void injectionAmountsAboveIntRoundTripWithoutNarrowing() {
        MagicResourceTaskLedger ledger = new MagicResourceTaskLedger();
        UUID taskId = UUID.randomUUID();
        long amount = Integer.MAX_VALUE + 321L;
        ledger.recordInjection(taskId, MagicResourceKind.SOUL,
                new ResourceLocation("minecraft", "overworld"), BlockPos.ZERO, amount);

        CompoundTag saved = ledger.save(new CompoundTag());
        CompoundTag injection = saved.getList("injections", Tag.TAG_COMPOUND).getCompound(0);

        assertEquals(amount, injection.getLong("amount"));
    }
}
