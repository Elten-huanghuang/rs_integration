package com.huanghuang.rsintegration.mods.vanilla;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/** Canonical family shared by vanilla and compatible cooking machines. */
public enum CookingMachineFamily {
    FURNACE,
    BLAST_FURNACE,
    SMOKER,
    CAMPFIRE,
    UNKNOWN;

    public static CookingMachineFamily fromRecipe(Recipe<?> recipe) {
        if (recipe == null) return UNKNOWN;
        ResourceLocation typeId = recipe.getType() != null
                ? ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType()) : null;
        CookingMachineFamily byId = fromRecipeTypeId(typeId);
        if (byId != UNKNOWN) return byId;
        if (recipe instanceof BlastingRecipe) return BLAST_FURNACE;
        if (recipe instanceof SmokingRecipe) return SMOKER;
        if (recipe instanceof SmeltingRecipe) return FURNACE;
        if (recipe instanceof CampfireCookingRecipe) return CAMPFIRE;
        return UNKNOWN;
    }

    public static CookingMachineFamily fromRecipeTypeId(@Nullable ResourceLocation id) {
        if (id == null) return UNKNOWN;
        String namespace = id.getNamespace();
        if (!"minecraft".equals(namespace) && !"brickfurnace".equals(namespace)) return UNKNOWN;
        return switch (id.getPath()) {
            case "smelting" -> FURNACE;
            case "blasting" -> BLAST_FURNACE;
            case "smoking" -> SMOKER;
            case "campfire_cooking" -> "minecraft".equals(namespace) ? CAMPFIRE : UNKNOWN;
            default -> UNKNOWN;
        };
    }

    public static CookingMachineFamily fromBlock(Block block) {
        if (block instanceof BlastFurnaceBlock) return BLAST_FURNACE;
        if (block instanceof SmokerBlock) return SMOKER;
        if (block instanceof FurnaceBlock) return FURNACE;
        return UNKNOWN;
    }

    public boolean matches(Block block) {
        return this != UNKNOWN && this == fromBlock(block);
    }
}
