package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Consumer;

/** 单次操作的实物来源。收取时只移交本次新取出的实物，不取得结算和清理权。 */
@FunctionalInterface
public interface OperationOutputSource {
    Handoff collectFinal(ServerPlayer player);

    default void transferAvailable(ServerLevel level) {}

    /** 自定义执行器先采样本轮状态，再按该状态移交回执；普通槽位不重复抽取。 */
    default void transferSignalled() {}

    static OperationOutputSource incremental(Consumer<ServerLevel> transfer, OperationOutputSource last) {
        return new OperationOutputSource() {
            @Override public void transferAvailable(ServerLevel level) { transfer.accept(level); }
            @Override public Handoff collectFinal(ServerPlayer player) { return last.collectFinal(player); }
        };
    }

    record Handoff(List<ItemStack> stacks, List<OutputAccounting.CollectedOutput> ports) {
        public Handoff {
            stacks = List.copyOf(stacks);
            ports = List.copyOf(ports);
        }

        public static Handoff physical(List<ItemStack> stacks, OutputContract contract) {
            List<OutputAccounting.CollectedOutput> records = new ArrayList<>();
            if (contract != null && contract.ports().size() == 1) {
                var port = contract.ports().get(0);
                for (ItemStack stack : stacks) {
                    if (!stack.isEmpty()) records.add(new OutputAccounting.CollectedOutput(port.portId(), port.source(), stack));
                }
            }
            return new Handoff(stacks, records);
        }

        public static Handoff identified(List<OutputAccounting.CollectedOutput> records) {
            return new Handoff(records.stream().map(OutputAccounting.CollectedOutput::stack).toList(), records);
        }
    }

    static OperationOutputSource single(Function<ServerPlayer, ItemStack> extract, OutputContract contract) {
        return player -> {
            ItemStack stack = extract.apply(player);
            return Handoff.physical(stack.isEmpty() ? List.of() : List.of(stack), contract);
        };
    }

    static OperationOutputSource port(Function<ServerPlayer, ItemStack> extract, String portId, OutputContract.Source source) {
        return player -> {
            ItemStack stack = extract.apply(player);
            return Handoff.identified(stack.isEmpty() ? List.of() :
                    List.of(new OutputAccounting.CollectedOutput(portId, source, stack)));
        };
    }
}
