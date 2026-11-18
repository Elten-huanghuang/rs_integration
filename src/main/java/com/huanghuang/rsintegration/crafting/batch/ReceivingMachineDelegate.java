package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import net.minecraft.world.item.ItemStack;
import javax.annotation.Nullable;
import java.util.List;

/** 具有可核实输出槽的机器只取得写入端；资格判断保留在具体机器。 */
public abstract class ReceivingMachineDelegate extends AbstractBatchDelegate {
    @Nullable protected TaskOutputReception.Writer production;

    public abstract boolean acceptsFixedOutput();

    @Override public final void bindProduction(TaskOutputReception.Writer writer) {
        if (acceptsFixedOutput()) production = writer;
    }

    @Nullable protected final TaskOutputReception.Batch outputBatch() {
        return production == null ? null : production.current();
    }

    protected boolean supportsNetworkOutputReception() { return false; }

    @Override protected final void onStorageEndpointChanged() {
        if (production != null) production.unbindNetwork();
    }

    protected final void prepareOutputBatch(List<TaskOutputReception.Demand> demands) {
        if (production == null) return;
        if (supportsNetworkOutputReception()) production.bindTo(storageEndpoint());
        production.prepare(demands);
    }

    protected final void receiveDirectOutput(ItemStack actual, List<ItemStack> physical) {
        if (actual.isEmpty()) return;
        if (production == null) physical.add(actual.copy());
        else production.hold(actual);
    }
}
