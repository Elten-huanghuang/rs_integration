package com.huanghuang.rsintegration.compat.jei;

import com.huanghuang.rsintegration.ModItems;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import net.minecraft.world.item.ItemStack;
import vazkii.botania.client.integration.jei.ManaPoolRecipeCategory;

/** 仅在该可选类被加载时，把共振池加入 Botania 的原生 JEI 机器列表。 */
public final class BotaniaManaPoolJeiWorkstations {
    private BotaniaManaPoolJeiWorkstations() {}

    public static void register(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(
                new ItemStack(ModItems.RESONANT_MANA_POOL.get()),
                ManaPoolRecipeCategory.TYPE);
    }
}
