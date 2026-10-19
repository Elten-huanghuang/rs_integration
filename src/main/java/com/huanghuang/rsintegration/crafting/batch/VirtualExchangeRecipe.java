package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 无加工状态、可按逻辑次数直接结算的配方契约。
 *
 * <p>成本使用 {@link IngredientSpec} 保留替代材料和材料角色。
 * {@code CATALYST} 等复用材料只预留一份，并在成功结算时原样返回。</p>
 */
public interface VirtualExchangeRecipe {
    List<IngredientSpec> costSpecs();

    List<ItemStack> resultItems(RegistryAccess access);

    default List<ItemStack> secondaryItems(RegistryAccess access) {
        return List.of();
    }
}
