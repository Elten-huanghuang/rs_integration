package com.huanghuang.rsintegration.network;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.jei.JeiMarqueeSelector;
import com.huanghuang.rsintegration.mods.goety.GoetyRSModule;
import com.huanghuang.rsintegration.sidepanel.RSInventoryTransferHandler;
import com.huanghuang.rsintegration.util.ModIds;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

@JeiPlugin
public final class RSJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID = new ResourceLocation(RSIntegrationMod.MOD_ID, "main");

    @Nullable
    private static IJeiRuntime cachedRuntime;

    @Nullable
    public static IJeiRuntime getRuntime() {
        return cachedRuntime;
    }

    @Override
    public @NotNull ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(@NotNull IJeiRuntime jeiRuntime) {
        cachedRuntime = jeiRuntime;
        if (!RSIntegrationConfig.ENABLE_JEI.get()) return;
        JeiMarqueeSelector.register();
        if (RSIntegrationConfig.ENABLE_GOETY.get() && ModList.get().isLoaded(ModIds.GOETY)) {
            GoetyRSModule.INSTANCE.onJeiRuntimeAvailable(jeiRuntime);
        }
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiMarqueeSelector.unregister();
        cachedRuntime = null;
        if (RSIntegrationConfig.ENABLE_GOETY.get() && ModList.get().isLoaded(ModIds.GOETY)) {
            GoetyRSModule.INSTANCE.onJeiRuntimeUnavailable();
        }
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        if (RSIntegrationConfig.ENABLE_GOETY.get() && ModList.get().isLoaded(ModIds.GOETY)) {
            GoetyRSModule.INSTANCE.registerRecipeTransferHandlers(registration);
        }
        if (RSIntegrationConfig.ENABLE_RS_SIDE_PANEL.get()
                && ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
            registration.addUniversalRecipeTransferHandler(
                    new RSInventoryTransferHandler());
        }
        if (RSIntegrationConfig.ENABLE_EIDOLON.get() && ModList.get().isLoaded(ModIds.EIDOLON)) {
            registerEidolonTransferHandlers(registration);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerEidolonTransferHandlers(IRecipeTransferRegistration registration) {
        try {
            Class<?> containerClass = Class.forName("elucent.eidolon.gui.WorktableContainer");
            Class<?> regClass = Class.forName("elucent.eidolon.registries.Registry");
            java.lang.reflect.Field wtField = regClass.getField("WORKTABLE_CONTAINER");
            var regObj = (net.minecraftforge.registries.RegistryObject<?>) wtField.get(null);
            MenuType<?> menuType = (MenuType<?>) regObj.get();

            Class<?> jeiRegClass = Class.forName("elucent.eidolon.gui.jei.JEIRegistry");
            java.lang.reflect.Field catField = jeiRegClass.getField("WORKTABLE_CATEGORY");
            var recipeType = (mezz.jei.api.recipe.RecipeType<?>) catField.get(null);

            // WorktableContainer slots: 0=result, 1-9=core(3x3), 10-13=extras(4)
            // WorktableRecipe.getIngredients() returns 9 core + 4 extras = 13
            // Player inventory starts at slot 14 (36 slots: 27 inv + 9 hotbar)
            registration.addRecipeTransferHandler(
                    (Class) containerClass, menuType, recipeType,
                    1,   // first recipe slot (skip result)
                    13,  // recipe slot count (9 core + 4 extras)
                    14,  // first inventory slot
                    36   // inventory slot count
            );
            RSIntegrationMod.LOGGER.debug("[RSI-JEI] Registered Eidolon worktable transfer handler");
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI] Failed to register Eidolon worktable transfer", e);
        }
    }
}
