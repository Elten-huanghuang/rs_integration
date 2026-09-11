package com.huanghuang.rsintegration.network;

import com.huanghuang.rsintegration.compat.ftbquests.client.FtbQuestJeiRuntime;
import com.huanghuang.rsintegration.compat.ftbquests.client.FtbQuestSubmissionCategory;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.ClientSyncedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.jei.JeiMarqueeSelector;
import com.huanghuang.rsintegration.mods.jei.client.JeiCheatShortcuts;
import com.huanghuang.rsintegration.mods.rs.RSGridSearchCache;
import com.huanghuang.rsintegration.mods.pmmo.client.PmmoSalvageAccess;
import com.huanghuang.rsintegration.mods.pmmo.client.PmmoSalvageJeiBridge;
import com.huanghuang.rsintegration.mods.pmmo.client.PmmoSalvageRecipeCategory;
import com.huanghuang.rsintegration.mods.apotheosis.client.ApotheosisLibraryClientEvents;
import com.huanghuang.rsintegration.mods.goety.GoetyRSModule;
import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarRecipeResolver;
import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarRecipeWrapper;
import com.huanghuang.rsintegration.mods.distantworlds.client.LithumAltarFironRecipeCategory;
import com.huanghuang.rsintegration.sidepanel.RSInventoryTransferHandler;
import com.huanghuang.rsintegration.sidepanel.client.MachineFavoritesClient;
import com.huanghuang.rsintegration.util.ModIds;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
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
        com.huanghuang.rsintegration.villager.tradelock.client.VillagerTradeLockClient
                .onRuntimeAvailable();
        // RSGridSearchCache contains RS Grid types and is only registered in
        // RSOptionalBootstrap.  Keep BD-only JEI startup free of that class.
        if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
            RSGridSearchCache.onJeiRuntimeAvailable();
        }
        if (ClientSyncedConfig.isSynced() ? !ClientSyncedConfig.ENABLE_JEI : !RSIntegrationConfig.ENABLE_JEI.get()) return;
        JeiMarqueeSelector.register();
        JeiCheatShortcuts.register();
        if (RSIntegrationConfig.ENABLE_GOETY.get() && ModList.get().isLoaded(ModIds.GOETY)) {
            GoetyRSModule.INSTANCE.onJeiRuntimeAvailable(jeiRuntime);
        }
        if (ModList.get().isLoaded(ModIds.FTB_QUESTS)) {
            FtbQuestJeiRuntime
                    .onRuntimeAvailable(jeiRuntime);
        }
        if (pmmoSalvageEnabled()) {
            PmmoSalvageJeiBridge.addLateSyncedRecipes(jeiRuntime);
        }
    }

    @Override
    public void onRuntimeUnavailable() {
        com.huanghuang.rsintegration.client.RecipeAvailabilityClient.clear();
        JeiMarqueeSelector.unregister();
        JeiCheatShortcuts.unregister();
        cachedRuntime = null;
        com.huanghuang.rsintegration.villager.tradelock.client.VillagerTradeLockClient
                .onRuntimeUnavailable();
        if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
            RSGridSearchCache.onJeiRuntimeUnavailable();
        }
        if (ModList.get().isLoaded(ModIds.FTB_QUESTS)) {
            FtbQuestJeiRuntime
                    .onRuntimeUnavailable();
        }
        if (RSIntegrationConfig.ENABLE_GOETY.get() && ModList.get().isLoaded(ModIds.GOETY)) {
            GoetyRSModule.INSTANCE.onJeiRuntimeUnavailable();
        }
        if (ModList.get().isLoaded(ModIds.PMMO)) {
            PmmoSalvageJeiBridge.clear();
        }
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        if (ModList.get().isLoaded(ModIds.FTB_QUESTS)) {
            registration.addRecipeCategories(
                    new FtbQuestSubmissionCategory(
                            registration.getJeiHelpers().getGuiHelper()));
        }
        if (RSIntegrationConfig.ENABLE_DISTANT_WORLDS.get()
                && ModList.get().isLoaded(ModIds.DISTANT_WORLDS)) {
            registration.addRecipeCategories(new LithumAltarFironRecipeCategory(
                    registration.getJeiHelpers().getGuiHelper()));
        }
        if (pmmoSalvageEnabled()) {
            registration.addRecipeCategories(new PmmoSalvageRecipeCategory(
                    registration.getJeiHelpers().getGuiHelper(), PmmoSalvageAccess.salvageBlock()));
        }
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        if (RSIntegrationConfig.ENABLE_DISTANT_WORLDS.get()
                && ModList.get().isLoaded(ModIds.DISTANT_WORLDS)) {
            registration.addRecipes(LithumAltarFironRecipeCategory.TYPE,
                    LithumAltarRecipeResolver.definitions().stream()
                            .filter(LithumAltarRecipeResolver::isComplete)
                            .map(definition -> new LithumAltarRecipeWrapper(
                                    ResourceLocation.fromNamespaceAndPath("distant_worlds", definition.currentRecipe()),
                                    definition))
                            .toList());
        }
        if (pmmoSalvageEnabled()) {
            PmmoSalvageJeiBridge.registerRecipes(registration);
        }
        // FTB Quests client data is not guaranteed to exist during JEI's static
        // registration pass. Player-specific entries are added from
        // FtbQuestJeiRuntime once ClientQuestFile has synchronized.
    }

    @Override
    public void registerRecipeCatalysts(mezz.jei.api.registration.IRecipeCatalystRegistration registration) {
        if (RSIntegrationConfig.ENABLE_DISTANT_WORLDS.get()
                && ModList.get().isLoaded(ModIds.DISTANT_WORLDS)) {
            var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    ResourceLocation.fromNamespaceAndPath(ModIds.DISTANT_WORLDS, "lithum_core"));
            if (item != null) registration.addRecipeCatalyst(item, LithumAltarFironRecipeCategory.TYPE);
        }
        if (pmmoSalvageEnabled()) {
            var salvageBlock = PmmoSalvageAccess.salvageBlock();
            if (!salvageBlock.isEmpty()) {
                registration.addRecipeCatalyst(salvageBlock, PmmoSalvageRecipeCategory.TYPE);
            }
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

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
            registration.addGuiScreenHandler(
                    com.huanghuang.rsintegration.voidupgrade.client.VoidUpgradeScreen.class,
                    new com.huanghuang.rsintegration.voidupgrade.client.VoidUpgradeJeiScreenHandler());
            registration.addGhostIngredientHandler(
                    com.huanghuang.rsintegration.voidupgrade.client.VoidUpgradeScreen.class,
                    new com.huanghuang.rsintegration.voidupgrade.client.VoidUpgradeGhostIngredientHandler());
        }
        registerOptionalRefinedStorageGuiHandler(registration);
        registerOptionalBeyondDimensionsGuiHandlers(registration);
        if (!ModList.get().isLoaded(ModIds.APOTHEOSIS)) return;
        try {
            Class<?> raw = Class.forName(
                    "dev.shadowsoffire.apotheosis.ench.library.EnchLibraryScreen");
            if (!AbstractContainerScreen.class.isAssignableFrom(raw)) return;
            Class<? extends AbstractContainerScreen<?>> screenClass =
                    (Class<? extends AbstractContainerScreen<?>>) raw;
            registration.addGuiContainerHandler((Class) screenClass,
                    new IGuiContainerHandler<AbstractContainerScreen<?>>() {
                        @Override
                        public java.util.List<net.minecraft.client.renderer.Rect2i> getGuiExtraAreas(
                                AbstractContainerScreen<?> screen) {
                            return ApotheosisLibraryClientEvents.getJeiExtraAreas(screen);
                        }
                    });
        } catch (ReflectiveOperationException exception) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-JEI] Failed to register Apotheosis library exclusion area", exception);
        }
    }

    private static void registerOptionalRefinedStorageGuiHandler(
            IGuiHandlerRegistration registration) {
        if (!ModList.get().isLoaded(ModIds.REFINED_STORAGE)) return;
        try {
            Class<?> hooks = Class.forName(
                    "com.huanghuang.rsintegration.network.RSJeiOptionalHooks");
            hooks.getMethod("registerGridGuiHandler", IGuiHandlerRegistration.class)
                    .invoke(null, registration);
        } catch (ReflectiveOperationException exception) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-JEI] Refined Storage GUI handler unavailable", exception);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerOptionalBeyondDimensionsGuiHandlers(
            IGuiHandlerRegistration registration) {
        if (!ModList.get().isLoaded("beyonddimensions")) return;
        IGuiContainerHandler<AbstractContainerScreen<?>> handler =
                new IGuiContainerHandler<>() {
                    @Override
                    public java.util.List<net.minecraft.client.renderer.Rect2i> getGuiExtraAreas(
                            AbstractContainerScreen<?> screen) {
                        java.util.List<net.minecraft.client.renderer.Rect2i> areas =
                                new java.util.ArrayList<>();
                        areas.addAll(com.huanghuang.rsintegration.machine.BeyondDimensionsMachineHubClient
                                .getFavoriteExtraAreas(screen));
                        areas.addAll(com.huanghuang.rsintegration.autoeat.client.AutoEatClientEvents
                                .getGuiExtraAreas(screen));
                        return java.util.List.copyOf(areas);
                    }
                };
        for (String className : java.util.List.of(
                "com.wintercogs.beyonddimensions.client.gui.DimensionsCraftGUI",
                "com.wintercogs.beyonddimensions.client.gui.DimensionsTerminalCraftGUI")) {
            try {
                Class<?> raw = Class.forName(className);
                if (AbstractContainerScreen.class.isAssignableFrom(raw)) {
                    registration.addGuiContainerHandler((Class) raw, handler);
                }
            } catch (ReflectiveOperationException exception) {
                RSIntegrationMod.LOGGER.debug(
                        "[RSI-JEI] BD terminal exclusion handler unavailable for {}",
                        className, exception);
            }
        }
    }

    private static boolean pmmoSalvageEnabled() {
        return RSIntegrationConfig.ENABLE_PMMO.get() && ModList.get().isLoaded(ModIds.PMMO);
    }
}
