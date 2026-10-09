package com.huanghuang.rsintegration.crafting.fluid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.wrappers.FluidBucketWrapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 为缺少能力的桶补充标准行为，并通过物品声明的剩余容器保留第三方桶的种类。 */
public final class FluidContainerBucketSupport {
    private FluidContainerBucketSupport() {}

    private record Pair(ItemStack empty, ItemStack filled, FluidStack fluid) {}

    static Function<ItemStack, IFluidHandlerItem> handlers(List<ItemStack> samples,
            Function<ItemStack, IFluidHandlerItem> original) {
        Map<CompoundTag, Pair> filledPairs = new LinkedHashMap<>();
        Map<CompoundTag, Map<CompoundTag, Pair>> emptyPairs = new LinkedHashMap<>();
        for (ItemStack sample : samples) {
            if (sample.isEmpty() || !(sample.getItem() instanceof BucketItem)) continue;
            try {
                IFluidHandlerItem handler = handler(sample.copyWithCount(1), original);
                if (handler == null || handler.getClass() != FluidBucketWrapper.class) continue;
                FluidStack fluid = handler.getFluidInTank(0).copy();
                if (fluid.isEmpty()) continue;
                ItemStack empty = sample.getCraftingRemainingItem();
                if (empty.isEmpty() || empty.getCount() != 1 || empty.is(Items.BUCKET)
                        || ItemStack.isSameItemSameTags(empty, sample)) continue;
                IFluidHandlerItem emptyHandler = handler(empty.copy(), original);
                if (emptyHandler != null && (emptyHandler.getTanks() != 1
                        || !emptyHandler.getFluidInTank(0).isEmpty())) continue;
                Pair pair = new Pair(empty.copy(), sample.copyWithCount(1), fluid);
                filledPairs.putIfAbsent(key(sample), pair);
                emptyPairs.computeIfAbsent(key(empty), ignored -> new LinkedHashMap<>())
                        .putIfAbsent(fluidKey(fluid), pair);
            } catch (RuntimeException | LinkageError ignored) {
                // 不支持的容器继续交给原始能力探测。
            }
        }
        return stack -> {
            IFluidHandlerItem handler = handler(stack, original);
            // 自定义能力可能有容量、损耗等规则，应完整保留。
            if (handler != null && handler.getClass() != FluidBucketWrapper.class) return handler;
            Pair filled = filledPairs.get(key(stack));
            Map<CompoundTag, Pair> choices = filled == null ? emptyPairs.get(key(stack))
                    : emptyPairs.get(key(filled.empty()));
            if (choices == null) return handler;
            return new FluidBucketWrapper(stack) {
                private Pair content = filled;

                @Override public FluidStack getFluid() {
                    return content == null ? FluidStack.EMPTY : content.fluid().copy();
                }

                @Override public boolean canFillFluidType(FluidStack fluid) {
                    return choices.containsKey(fluidKey(fluid));
                }

                @Override protected void setFluid(FluidStack fluid) {
                    if (fluid.isEmpty()) {
                        container = content.empty().copy();
                        content = null;
                    } else {
                        content = choices.get(fluidKey(fluid));
                        container = content.filled().copy();
                    }
                }
            };
        };
    }

    public static IFluidHandlerItem getHandler(ItemStack stack) {
        if (stack.isEmpty()) return null;
        return handlers(List.of(stack), candidate -> FluidUtil.getFluidHandler(candidate).orElse(null)).apply(stack);
    }

    static IFluidHandlerItem defaultHandler(ItemStack stack,
            Function<ItemStack, IFluidHandlerItem> original) {
        return handler(stack, original);
    }

    private static IFluidHandlerItem handler(ItemStack stack,
            Function<ItemStack, IFluidHandlerItem> original) {
        IFluidHandlerItem handler = original.apply(stack);
        // Forge 仅为 BucketItem 本类附加默认能力，未声明能力的桶子类需要回退。
        return handler == null && stack.getItem() instanceof BucketItem ? new FluidBucketWrapper(stack) : handler;
    }

    public static boolean needsFallback(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BucketItem
                && !FluidUtil.getFluidHandler(stack.copyWithCount(1)).isPresent();
    }

    /** 在副本上模拟排空，客户端识别不会改变鼠标中的容器。 */
    public static FluidStack getFluid(ItemStack stack) {
        if (stack.isEmpty()) return FluidStack.EMPTY;
        IFluidHandlerItem handler = getHandler(stack.copyWithCount(1));
        return handler == null ? FluidStack.EMPTY
                : handler.drain(FluidType.BUCKET_VOLUME, IFluidHandler.FluidAction.SIMULATE);
    }

    private static CompoundTag key(ItemStack stack) {
        return stack.copyWithCount(1).save(new CompoundTag());
    }

    private static CompoundTag fluidKey(FluidStack fluid) {
        FluidStack identity = fluid.copy();
        identity.setAmount(1);
        return identity.writeToNBT(new CompoundTag());
    }
}
