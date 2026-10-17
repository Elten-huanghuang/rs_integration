package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskManager;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * 魔法资源库存变更版本号的契约。
 *
 * <p>混合终端不再每秒轮询，改为订阅这个版本号；只要有一条写入路径忘了声明变更，
 * 对应的资源就又变成「不实时刷新」。所以这里逐个锁住写入路径：盘内插入、盘内提取、
 * 任务预留的提取与回滚。三种资源共用同一入口，Botania 的魔力和 Ars 的魔源走的
 * 是同一条断言，不会出现「灵魂修了、其他没修」。</p>
 */
class MagicResourceDirtyContractTest extends BootstrapTest {

    private static UnifiedDiskCore disk() {
        return new UnifiedDiskCore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new UnifiedDiskCore.Limits(8, 8, 1048576, 268435456));
    }

    @Test
    void diskInsertAndExtractAnnounceEveryKind() {
        UnifiedDiskCore disk = disk();
        for (MagicResourceKind kind : MagicResourceKind.values()) {
            long before = MagicResourceDirty.version();
            assertEquals(50, MagicDiskStore.insert(diskList(disk), kind, 50, true));
            assertEquals(before + 1, MagicResourceDirty.version(), kind + " 插入后未声明变更");

            before = MagicResourceDirty.version();
            assertEquals(50, MagicDiskStore.extract(diskList(disk), kind, 50, true));
            assertEquals(before + 1, MagicResourceDirty.version(), kind + " 提取后未声明变更");
        }
    }

    @Test
    void dryRunDoesNotAnnounceChange() {
        UnifiedDiskCore disk = disk();
        List<UnifiedDiskCore> disks = diskList(disk);
        MagicDiskStore.insert(disks, MagicResourceKind.MANA, 50, true);
        long before = MagicResourceDirty.version();
        // 模拟只是探测容量与可提取量，两个方向都不改库存，也就不算一次变更。
        MagicDiskStore.insert(disks, MagicResourceKind.MANA, 50, false);
        MagicDiskStore.extract(disks, MagicResourceKind.MANA, 50, false);
        assertEquals(before, MagicResourceDirty.version());
        assertEquals(50, disk.magicAmount(MagicResourceKind.MANA));
    }

    @Test
    void capacityIsSummedAcrossMountedDisks() {
        UnifiedDiskCore first = disk();
        UnifiedDiskCore second = disk();
        assertEquals(2_000_000_000, MagicDiskStore.capacity(
                List.of(first, second), MagicResourceKind.MANA));
        assertEquals(Integer.MAX_VALUE, MagicDiskStore.capacity(
                List.of(first, second, disk()), MagicResourceKind.MANA));
    }

    @Test
    void reservationExtractAndCloseAnnounceChanges() {
        UnifiedDiskManager manager = mock(UnifiedDiskManager.class);
        UnifiedDiskCore disk = disk();
        // 备料必须在打桩之前：否则 MagicDiskStore.insert 也会被 mock 成返回默认值。
        MagicDiskStore.insert(diskList(disk), MagicResourceKind.SOUL, 100, true);
        try (MockedStatic<MagicDiskStore> store = mockStatic(MagicDiskStore.class)) {
            store.when(() -> MagicDiskStore.mountedDisks(manager)).thenReturn(List.of(disk));
            store.when(() -> MagicDiskStore.capacity(List.of(disk), MagicResourceKind.SOUL))
                    .thenReturn(MagicResourceKind.SOUL.capacity());

            long before = MagicResourceDirty.version();
            MagicResourceReservations.Pool pool =
                    MagicResourceReservations.reserve(manager, MagicResourceKind.SOUL, 60);
            assertEquals(60, pool.remaining());
            assertTrue(MagicResourceDirty.version() > before, "预留后未声明变更");

            before = MagicResourceDirty.version();
            assertEquals(40, pool.withdraw(40));
            assertTrue(MagicResourceDirty.version() > before, "预留提取后未声明变更");

            before = MagicResourceDirty.version();
            pool.close();
            assertTrue(MagicResourceDirty.version() > before, "预留关闭后未声明变更");
        }
    }

    private static List<UnifiedDiskCore> diskList(UnifiedDiskCore disk) {
        return List.of(disk);
    }
}
