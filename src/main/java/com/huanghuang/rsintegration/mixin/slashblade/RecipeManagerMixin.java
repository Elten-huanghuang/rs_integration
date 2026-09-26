package com.huanghuang.rsintegration.mixin.slashblade;
import java.lang.reflect.Method;

import com.google.gson.JsonElement;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladeRecipeJsonCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Map;

@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @ModifyVariable(
            method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Map<ResourceLocation, JsonElement> rsi$repairSlashBladeRecipes(
            Map<ResourceLocation, JsonElement> recipes) {
        return SlashBladeRecipeJsonCompat.repairAll(recipes);
    }
}
