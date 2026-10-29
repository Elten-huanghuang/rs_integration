package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * 一次操作的输入所有权：持有、投放、取回或主动移交。
 *
 * <p>持有的材料可以原样退回；投放后只能认领原方块实体中仍匹配的实际残留。
 * 取回数量以提取接口的返回值为准，不按订单模板重建已消耗物品。
 * 本类不操作存储或账本，结算仍由执行链统一完成。</p>
 */
public final class OperationInputRecovery {
    private static final Logger LOGGER = LogManager.getLogger(OperationInputRecovery.class);
    private final List<Input> inputs = new ArrayList<>();
    @Nullable
    private List<ItemStack> recovered;

    /** 仅在材料已实际交给 delegate 后登记，不能登记尚未提交的预留。 */
    public Input hold(ItemStack stack) {
        if (recovered != null) throw new IllegalStateException("输入回收已结算");
        Input input = new Input(stack);
        inputs.add(input);
        return input;
    }

    /** 重复调用返回同一份回收凭据的副本，不会再次提取机器库存。 */
    public List<ItemStack> recover(@Nullable ServerLevel level) {
        if (recovered == null) {
            List<ItemStack> result = new ArrayList<>();
            for (Input input : inputs) {
                try {
                    ItemStack stack = input.take(level);
                    if (!stack.isEmpty()) result.add(stack);
                } catch (RuntimeException exception) {
                    // 不能因一个槽位不可读而丢弃其他槽位已经取得的回收凭据。
                    LOGGER.warn("[RSI-Recovery] 无法确认输入回收，保守跳过该认领", exception);
                }
            }
            recovered = List.copyOf(result);
        }
        return recovered.stream().map(ItemStack::copy).toList();
    }

    /** 成功完成或将所有权交给玩家时，放弃输入认领而不触碰机器。 */
    public void release() {
        for (Input input : inputs) input.release();
        inputs.clear();
        recovered = List.of();
    }

    public static final class Input {
        private final ItemStack template;
        @Nullable
        private BlockEntity owner;
        @Nullable
        private Supplier<ItemStack> reader;
        @Nullable
        private IntFunction<ItemStack> extractor;
        private boolean released;

        private Input(ItemStack template) {
            this.template = template.copy();
        }

        /**
         * 在向机器转交材料前绑定位置；原生插入可能同步消耗材料，
         * 因此绑定后即停止把模板当作仍在 delegate 手中的实物。
         */
        public void place(BlockEntity owner, Supplier<ItemStack> reader,
                          IntFunction<ItemStack> extractor) {
            if (released || this.owner != null) {
                throw new IllegalStateException("输入已投放或移交");
            }
            this.owner = Objects.requireNonNull(owner, "owner");
            this.reader = Objects.requireNonNull(reader, "reader");
            this.extractor = Objects.requireNonNull(extractor, "extractor");
        }

        public void place(BlockEntity owner, Container container, int slot) {
            place(owner, () -> container.getItem(slot), amount -> container.removeItem(slot, amount));
        }

        public void place(BlockEntity owner, IItemHandler handler, int slot) {
            place(owner, () -> handler.getStackInSlot(slot),
                    amount -> handler.extractItem(slot, amount, false));
        }

        /** 单独归还可复用催化剂时也使用相同的归属验证，且不会再次进入失败退款。 */
        public ItemStack take(@Nullable ServerLevel level) {
            if (released) return ItemStack.EMPTY;
            released = true;
            if (owner == null) return template.copy();
            if (level == null || owner.isRemoved()
                    || !level.hasChunkAt(owner.getBlockPos())
                    || level.getBlockEntity(owner.getBlockPos()) != owner) {
                return ItemStack.EMPTY;
            }
            ItemStack available = reader.get();
            if (!MaterialMatcher.equivalentRuntimeFragment(template, available)) return ItemStack.EMPTY;
            ItemStack removed = extractor.apply(Math.min(template.getCount(), available.getCount()));
            if (removed == null || removed.isEmpty()) return ItemStack.EMPTY;
            owner.setChanged();
            return removed.copy();
        }

        public void release() {
            released = true;
        }
    }
}
