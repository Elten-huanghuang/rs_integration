package com.example.rsiintegration.apismoke;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(RsiApiSmokeMod.MOD_ID)
public final class RsiApiSmokeMod {
    public static final String MOD_ID = "rsi_api_smoke";
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    private static final DeferredRegister<RecipeSerializer<?>>
            RECIPE_SERIALIZERS = DeferredRegister.create(
                    ForgeRegistries.RECIPE_SERIALIZERS,
                    MOD_ID
            );
    public static final RegistryObject<Item> API_SMOKE_RESULT =
            ITEMS.register("api_smoke_result",
                    () -> new Item(new Item.Properties()));
    public static final RegistryObject<
            RecipeSerializer<IronAlchemistChanceRecipe>>
            IRON_ALCHEMIST_CHANCE_SERIALIZER =
            RECIPE_SERIALIZERS.register(
                    "iron_alchemist_chance",
                    IronAlchemistChanceRecipe.Serializer::new
            );

    public RsiApiSmokeMod() {
        ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
        RECIPE_SERIALIZERS.register(
                FMLJavaModLoadingContext.get().getModEventBus());
    }
}
