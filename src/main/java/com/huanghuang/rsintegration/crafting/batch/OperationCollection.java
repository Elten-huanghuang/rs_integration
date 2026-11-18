package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.world.item.ItemStack;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** 一次终结的实物交接。先取机器，成功后再接管捕获区和账本，取货异常时仍可恢复。 */
public record OperationCollection(List<ItemStack> physical, List<ItemStack> held,
                                  @Nullable OutputAccounting.Settlement settlement) {
    public List<ItemStack> stacks() {
        List<ItemStack> all = new ArrayList<>(physical);
        all.addAll(held);
        return List.copyOf(all);
    }

    static OperationCollection assess(OperationOutputSource.Handoff handoff, OutputContract contract, int operations,
                                      List<ItemStack> captured, List<ItemStack> held, List<ItemStack> cumulative) {
        List<ItemStack> physical = new ArrayList<>(captured);
        physical.addAll(handoff.stacks());
        OutputAccounting.Settlement settlement = null;
        if (contract != null && !contract.ports().isEmpty()) {
            var records = new ArrayList<>(handoff.ports());
            records.addAll(OutputAccounting.capturedWorldOutputRecords(contract, captured));
            records.addAll(OutputAccounting.attributeReceived(contract, operations, cumulative));
            settlement = OutputAccounting.assess(contract, operations, records);
        }
        return new OperationCollection(List.copyOf(physical), held, settlement);
    }
}
