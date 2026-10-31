package com.huanghuang.rsintegration.feature.experience.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

import java.util.function.Consumer;

/** 液态经验使用本模组自带的贴图，不要求安装 Beyond Dimensions。 */
public final class ExperienceFluidClient implements IClientFluidTypeExtensions {
    private static final ResourceLocation STILL_TEXTURE =
            new ResourceLocation(RSIntegrationMod.MOD_ID, "block/experience_still");
    private static final ResourceLocation FLOWING_TEXTURE =
            new ResourceLocation(RSIntegrationMod.MOD_ID, "block/experience_flow");

    private ExperienceFluidClient() {}

    public static void initialize(Consumer<IClientFluidTypeExtensions> consumer) {
        consumer.accept(new ExperienceFluidClient());
    }

    @Override
    public ResourceLocation getStillTexture() {
        return STILL_TEXTURE;
    }

    @Override
    public ResourceLocation getFlowingTexture() {
        return FLOWING_TEXTURE;
    }

    @Override
    public int getTintColor() {
        return 0xFFFFFFFF;
    }
}
