package com.huanghuang.rsintegration.feature.experience;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.feature.experience.client.ExperienceFluidClient;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Consumer;

/** 独立于精妙核心和存储后端的液态经验，数量沿用普通流体的 mB 单位。 */
public final class ExperienceFluids {
    public static final int MILLIBUCKETS_PER_EXPERIENCE_POINT = 20;
    public static final TagKey<Fluid> EXPERIENCE_TAG = TagKey.create(
            Registries.FLUID, new ResourceLocation("forge", "experience"));

    private static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, RSIntegrationMod.MOD_ID);
    private static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(ForgeRegistries.FLUIDS, RSIntegrationMod.MOD_ID);

    public static final RegistryObject<FluidType> EXPERIENCE_TYPE = FLUID_TYPES.register(
            "experience", () -> new FluidType(FluidType.Properties.create()
                    .descriptionId("fluid_type.rs_integration.experience")
                    .lightLevel(10)
                    .density(800)
                    .viscosity(1500)) {
                @Override
                public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
                    // Forge 仅在物理客户端调用此入口，服务端不加载贴图扩展。
                    ExperienceFluidClient.initialize(consumer);
                }
            });
    public static final RegistryObject<FlowingFluid> EXPERIENCE = FLUIDS.register(
            "experience", () -> new ForgeFlowingFluid.Source(properties()));
    public static final RegistryObject<FlowingFluid> FLOWING_EXPERIENCE = FLUIDS.register(
            "flowing_experience", () -> new ForgeFlowingFluid.Flowing(properties()));

    private ExperienceFluids() {}

    private static ForgeFlowingFluid.Properties properties() {
        // 暂不注册世界方块和桶，先提供储罐、管道及网络可存储的真实流体。
        return new ForgeFlowingFluid.Properties(EXPERIENCE_TYPE, EXPERIENCE, FLOWING_EXPERIENCE);
    }

    public static void register(IEventBus modBus) {
        FLUID_TYPES.register(modBus);
        FLUIDS.register(modBus);
    }
}
