package com.huanghuang.rsintegration.storage.disk.rs;

import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;

import java.util.List;

/** 磁盘操纵器跨 tick 批处理状态；放在普通包中，避免 Mixin 内部类被直接引用。 */
public final class DiskManipulatorTransferSession<T> {
    public enum Step {
        PROGRESS,
        SKIP,
        STOP
    }

    public final IStorageDisk<T> disk;
    public final int slot;
    public List<T> sources;
    public int cursor;
    public int livePosition;
    public long cooldownUntil;
    public boolean passProgress;

    public DiskManipulatorTransferSession(IStorageDisk<T> disk, int slot) {
        this.disk = disk;
        this.slot = slot;
    }
}
