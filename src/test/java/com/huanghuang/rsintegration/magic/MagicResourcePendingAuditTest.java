package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 无法自动回收的魔法资源量转为待核对记录后的存档与核对行为。 */
class MagicResourcePendingAuditTest extends BootstrapTest {
    @Test
    void pendingAuditKeepsAuditingEvidenceAcrossSaveAndReload() {
        MagicResourceTaskLedger ledger = new MagicResourceTaskLedger();
        UUID taskId = UUID.randomUUID();
        ledger.archive(taskId, MagicResourceKind.SOUL, 240, 12,
                MagicResourceTaskLedger.PendingReason.BD_NETWORK, 4_000L);

        CompoundTag saved = ledger.save(new CompoundTag());
        assertEquals(1, saved.getList("pendingAudits", Tag.TAG_COMPOUND).size());

        MagicResourceTaskLedger reloaded =
                new MagicResourceTaskLedger(saved);
        assertEquals(1, reloaded.pendingAuditCount());
        MagicResourceTaskLedger.Pending entry = reloaded.pendingAudits().get(0);
        assertEquals(taskId, entry.taskId());
        assertEquals(MagicResourceKind.SOUL, entry.kind());
        assertEquals(240, entry.amount());
        assertEquals(12, entry.bdNetworkId());
        assertEquals(MagicResourceTaskLedger.PendingReason.BD_NETWORK, entry.reason());
        assertEquals(4_000L, entry.recordedAt());
    }

    @Test
    void pendingAuditsAreNotEnteredIntoAutomaticRecovery() {
        MagicResourceTaskLedger ledger = new MagicResourceTaskLedger();
        ledger.archive(UUID.randomUUID(), MagicResourceKind.SOURCE, 90, -1,
                MagicResourceTaskLedger.PendingReason.NO_TARGET, 10L);

        CompoundTag saved = ledger.save(new CompoundTag());
        // 待核对记录不是注入记录：重载后不会进入自动回收，必须人工核对。
        assertFalse(saved.getList("injections", Tag.TAG_COMPOUND).stream().findAny().isPresent());
        assertFalse(ledger.pendingAudits().isEmpty());
    }

    @Test
    void resolvingPendingAuditRemovesItOnce() {
        MagicResourceTaskLedger ledger = new MagicResourceTaskLedger();
        ledger.archive(UUID.randomUUID(), MagicResourceKind.MANA, 30, -1,
                MagicResourceTaskLedger.PendingReason.MACHINE_PARTIAL, 1L);
        UUID pendingId = ledger.pendingAudits().get(0).id();

        assertTrue(ledger.resolvePending(pendingId));
        assertTrue(ledger.pendingAudits().isEmpty());
        assertFalse(ledger.resolvePending(pendingId));
        assertFalse(ledger.resolvePending(null));
    }

    @Test
    void pendingAuditsDropsTheOldestWhenTheCapIsReached() {
        MagicResourceTaskLedger ledger = new MagicResourceTaskLedger();
        // 上限为 512；写入 513 条后最早的一条必须被淘汰，且不再无限增长。
        for (int i = 0; i < 513; i++) {
            ledger.archive(null, MagicResourceKind.MANA, i + 1, -1,
                    MagicResourceTaskLedger.PendingReason.NO_TARGET, i + 1L);
        }
        assertEquals(512, ledger.pendingAuditCount());
        assertEquals(2L, ledger.pendingAudits().get(0).recordedAt());
        assertEquals(513L, ledger.pendingAudits().get(511).recordedAt());
    }

    @Test
    void invalidPendingRecordsAreDroppedOnLoad() {
        CompoundTag broken = new CompoundTag();
        broken.putUUID("id", UUID.randomUUID());
        broken.putString("kind", "NOT_A_KIND");
        broken.putString("reason", "NO_TARGET");
        broken.putInt("amount", 10);
        broken.putLong("at", 1L);

        CompoundTag payload = new CompoundTag();
        payload.put("pendingAudits", listOf(broken));

        MagicResourceTaskLedger reloaded = new MagicResourceTaskLedger(payload);
        assertEquals(0, reloaded.pendingAuditCount());
        assertNull(reloaded.pendingAudits().stream().findAny().orElse(null));
    }

    private static ListTag listOf(CompoundTag entry) {
        ListTag list = new ListTag();
        list.add(entry);
        return list;
    }
}
