package com.huanghuang.rsintegration.feature.experience;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;
import java.util.function.Function;

/** 精妙经验进入 RS 时按等量 mB 统一身份，不依赖精妙核心的类。 */
public final class ExperienceFluidNormalization {
    private static final Set<ResourceLocation> SOPHISTICATED_EXPERIENCE = Set.of(
            new ResourceLocation("sophisticatedcore", "xp_still"),
            new ResourceLocation("sophisticatedcore", "xp_flowing"));

    private ExperienceFluidNormalization() {}

    public static boolean canNormalize(FluidStack stack) {
        if (stack.isEmpty() || !ExperienceFluids.EXPERIENCE.isPresent()) return false;
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        // 不按经验标签泛化转换，避免误用其他模组的经验换算比例。
        return id != null && SOPHISTICATED_EXPERIENCE.contains(id);
    }

    /** 调用者先检查 canNormalize；模拟与实际插入复用相同的身份转换。 */
    public static FluidStack insertConverted(FluidStack original, int amount,
                                              Function<FluidStack, FluidStack> insertion) {
        FluidStack converted = new FluidStack(
                ExperienceFluids.EXPERIENCE.get(), amount, original.getTag());
        FluidStack remainder = insertion.apply(converted);
        if (remainder.isEmpty()) return FluidStack.EMPTY;

        // 只转换接收部分；背包或管道中的余量仍是原流体，不能被动换液。
        FluidStack originalRemainder = original.copy();
        originalRemainder.setAmount(remainder.getAmount());
        return originalRemainder;
    }
}
