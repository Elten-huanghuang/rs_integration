package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one capture lease and its matching interceptor handle. */
public final class CaptureSession implements AutoCloseable {
    private final CaptureLeaseRegistry registry;
    private final CaptureLeaseRegistry.Lease lease;
    private final CraftOutputInterceptor.CaptureHandle handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    public static CaptureSession arm(CaptureLeaseRegistry registry,
                                     CaptureLeaseRegistry.Lease lease,
                                     CraftOutputInterceptor.CaptureHandle handle) {
        return new CaptureSession(registry, lease, handle);
    }

    CaptureSession(CaptureLeaseRegistry registry, CaptureLeaseRegistry.Lease lease,
                   CraftOutputInterceptor.CaptureHandle handle) {
        this.registry = registry;
        this.lease = lease;
        this.handle = handle;
    }

    public List<ItemStack> drainAndClose() {
        if (!closed.compareAndSet(false, true)) return List.of();
        try {
            return handle.drainAndClose();
        } finally {
            registry.release(lease);
        }
    }

    public boolean hasCaptured() {
        return !closed.get() && handle.hasCaptured();
    }

    public List<ItemStack> snapshot() {
        return closed.get() ? List.of() : handle.snapshot();
    }

    @Override
    public void close() {
        drainAndClose();
    }
}
