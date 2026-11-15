package com.huanghuang.rsintegration.crafting;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

/** 需要额外运行时资源、当前不能参与递归合成的特殊配方。 */
public final class RecursiveRecipePolicy {
    private RecursiveRecipePolicy() {}

    public static boolean isExcluded(Recipe<?> recipe) {
        if (recipe == null) return false;
        ResourceLocation id = recipe.getId();
        if (id != null && "reliquary".equals(id.getNamespace())
                && id.getPath().startsWith("alkahestry/")) {
            return true;
        }
        // 万能溶剂之经的充能不在普通材料图中；同时覆盖整合包重命名的配方。
        // 按类名判断，避免在未安装 Reliquary 时加载可选模组类型。
        for (Class<?> type = recipe.getClass(); type != null; type = type.getSuperclass()) {
            if (switch (type.getName()) {
                case "reliquary.crafting.AlkahestryCraftingRecipe",
                     "reliquary.crafting.AlkahestryChargingRecipe",
                     "reliquary.crafting.AlkahestryDrainRecipe" -> true;
                default -> false;
            }) return true;
        }
        return false;
    }
}
