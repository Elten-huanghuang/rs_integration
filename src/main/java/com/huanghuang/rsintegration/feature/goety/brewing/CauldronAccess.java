package com.huanghuang.rsintegration.feature.goety.brewing;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

/** 公共交互入口不直接加载可选的 Goety 类型。 */
public final class CauldronAccess {
    private static final ResourceLocation BLOCK = ResourceLocation.fromNamespaceAndPath("goety", "witch_cauldron");

    private CauldronAccess() {}

    public static boolean isTarget(Block block) {
        return BLOCK.equals(ForgeRegistries.BLOCKS.getKey(block));
    }

    public static boolean canOpen(String mode, boolean hasIngredients, boolean busy) {
        // 清水可以保留；药液、废液及已投料的锅由原生状态区分。
        return "IDLE".equals(mode) && !hasIngredients && !busy;
    }
}
