package com.huanghuang.rsintegration.mixin.plugin;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.ClassReader;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
 * Conditionally applies mixins that target optional mods (Nameless Trinkets, etc.)
 * so the game does not crash when those mods are absent.
 * <p>
 * At the time this plugin runs, Forge's {@code ModList} is not yet populated,
 * so detection uses classloader resource lookup rather than
 * {@code ModList.get().isLoaded(...)}. {@code Class.forName()} is unsafe here
 * because it triggers class loading → re-entrant mixin transformation → crash.
 */
public final class RSIntegrationMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Every mixin under our refinedstorage namespace has RS types in its
        // annotations or method bodies.  Forge may still parse the mixin
        // configuration when the optional dependency is absent, so reject it
        // before Mixin attempts to load the class.
        if (mixinClassName.contains(".refinedstorage.")) {
            return isClassPresent("com.refinedmods.refinedstorage.api.network.INetwork")
                    && isClassPresent(targetClassName);
        }
        if (isYzzzOwnedRefinedStorageMixin(mixinClassName)) {
            String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
            if (isClassPresent("me.realseek.yzzzfix.mixin.refinedstorage." + simpleName)) {
                return false;
            }
        }

        // Guard mixins whose BODY hard-references a *second* mod's class (beyond
        // their @Mixin target). Mixin's framework only auto-skips a mixin when its
        // @Mixin TARGET class is absent; a body reference to another absent mod
        // class would instead NoClassDefFoundError at apply/runtime. Probe that
        // second class here so the whole mixin is skipped when it is missing.
        if (mixinClassName.contains("goetydelight.BlockFinderCompatMixin")) {
            return isClassPresent("com.Polarice3.Goety.utils.BlockFinder")
                    && isClassPresent("net.v_black_cat.goetydelight.effect.ModEffects");
        }
        if (mixinClassName.contains("distantworlds.LithumCoreUpdateTickProcedureMixin")) {
            return isClassPresent("net.mcreator.distantworlds.procedures.LithumCoreUpdateTickProcedure");
        }
        if (mixinClassName.contains("apotheosis.EnchLibraryScreenMixin")) {
            return isClassPresent("dev.shadowsoffire.apotheosis.ench.library.EnchLibraryScreen")
                    && hasField(targetClassName, "filter");
        }
        if (mixinClassName.contains("apotheosis.ReforgingMenuRestockMixin")) {
            return isClassPresent("dev.shadowsoffire.apotheosis.adventure.affix.reforging.ReforgingMenu")
                    && hasMethod(targetClassName, "m_6199_");
        }
        if (mixinClassName.contains("placebo.PlaceboContainerMenuMixin")) {
            return isClassPresent("dev.shadowsoffire.placebo.menu.PlaceboContainerMenu")
                    && hasField(targetClassName, "level");
        }
        if (mixinClassName.contains("placebo.PlaceboBlockEntityMenuMixin")) {
            return isClassPresent("dev.shadowsoffire.placebo.menu.BlockEntityMenu")
                    && hasMethod(targetClassName, "<init>");
        }
        if (mixinClassName.contains("crockpot.CrockPotMenuMixin")) {
            return hasField(targetClassName, "blockEntity");
        }
        if (mixinClassName.contains("ironfurnaces.BlockIronFurnaceTileBaseMixin")) {
            return isClassPresent("ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase");
        }
        // These mixins are attached to non-RS mods but their method bodies
        // contain RS API signatures. Skip them before Mixin can load those
        // signatures in BD-only or no-RS installations.
        if (mixinClassName.contains("sophisticatedbackpacks.InventoryInteractionHelperMixin")
                || mixinClassName.contains("reliquary.PyromancerStaffMixin")) {
            return isClassPresent("com.refinedmods.refinedstorage.api.network.INetwork")
                    && isClassPresent("com.refinedmods.refinedstorage.api.util.Action");
        }
        if (mixinClassName.contains("forbidden.ClibanoMainBlockEntityAccessor")) {
            return isClassPresent("com.stal111.forbidden_arcanus.common.block.entity.clibano.ClibanoMainBlockEntity")
                    && hasMethod(targetClassName, "getBurnDuration");
        }
        if (mixinClassName.contains("wizardsreborn.ArcaneWorkbenchBlockEntityAccessor")) {
            return isClassPresent("mod.maxbogomol.wizards_reborn.common.block.arcane_workbench.ArcaneWorkbenchBlockEntity")
                    && hasField(targetClassName, "itemHandler")
                    && hasField(targetClassName, "itemOutputHandler")
                    && hasField(targetClassName, "startCraft")
                    && hasField(targetClassName, "wissenInCraft")
                    && hasField(targetClassName, "wissen")
                    && hasMethod(targetClassName, "wissenWandFunction");
        }
        if (mixinClassName.contains("wizardsreborn.ArcaneIteratorBlockEntityAccessor")) {
            return hasField(targetClassName, "startCraft")
                    && hasField(targetClassName, "wissenInCraft")
                    && hasField(targetClassName, "wissenIsCraft")
                    && hasField(targetClassName, "experienceIsCraft")
                    && hasField(targetClassName, "healthIsCraft")
                    && hasField(targetClassName, "wissen")
                    && hasMethod(targetClassName, "wissenWandFunction")
                    && hasMethod(targetClassName, "getPedestals")
                    && hasMethod(targetClassName, "getMainPedestal");
        }
        if (mixinClassName.contains("wizardsreborn.WissenCrystallizerBlockEntityAccessor")) {
            return hasField(targetClassName, "startCraft")
                    && hasField(targetClassName, "wissenInCraft")
                    && hasField(targetClassName, "wissen")
                    && hasMethod(targetClassName, "wissenWandFunction");
        }
        if (mixinClassName.contains("wizardsreborn.CrystalBlockEntityAccessor")) {
            return hasField(targetClassName, "startRitual")
                    && hasField(targetClassName, "cooldown")
                    && hasMethod(targetClassName, "wissenWandFunction");
        }
        if (mixinClassName.contains("CraftingManagerMixin")
                || mixinClassName.contains("CraftingTaskMixin")
                || mixinClassName.contains("CraftingTaskAccessor")
                || mixinClassName.contains("ItemGridHandlerMixin")) {
            return isClassPresent("com.refinedmods.refinedstorage.apiimpl.autocrafting.CraftingManager")
                    && isClassPresent("dev.ftb.mods.ftbquests.quest.ServerQuestFile")
                    && isClassPresent("dev.ftb.mods.ftbquests.quest.TeamData");
        }
        if (mixinClassName.contains("InventoryHelperExternalItemMixin")) {
            // This mixin belongs to Sophisticated Core's pickup path.  It must
            // remain available without FTB Quests; the old guard accidentally
            // disabled RS backpack pickup in RS-only modpacks.
            return hasMethod(targetClassName, "runPickupOnPickupResponseUpgrades");
        }
        if (mixinClassName.contains("PlayerInventoryProviderMixin")) {
            return hasMethod(targetClassName, "runOnBackpacks");
        }
        if (mixinClassName.contains("ftbquests.SubmitTaskMessageMixin")) {
            return isClassPresent("dev.ftb.mods.ftbquests.net.SubmitTaskMessage")
                    && hasMethod(targetClassName, "handle")
                    && hasMethod(targetClassName, "lambda$handle$0");
        }
        if (mixinClassName.contains("ftbquests.InventoryTaskAutoSubmissionMixin")) {
            return isClassPresent("dev.ftb.mods.ftbquests.util.FTBQuestsInventoryListener")
                    && hasMethod(targetClassName, "lambda$detect$0");
        }
        if (mixinClassName.contains("ftbquests.TeamDataAutoCompletionMixin")) {
            return isClassPresent("dev.ftb.mods.ftbquests.quest.TeamData")
                    && hasMethod(targetClassName, "checkAutoCompletion");
        }
        if (mixinClassName.contains("ftbquests.ClaimAllRewardsMessageMixin")) {
            return isClassPresent("dev.ftb.mods.ftbquests.net.ClaimAllRewardsMessage")
                    && hasMethod(targetClassName, "lambda$handle$1");
        }
        if (mixinClassName.contains("ftbquests.ItemRewardMixin")) {
            return isClassPresent("dev.ftb.mods.ftbquests.quest.reward.ItemReward")
                    && hasMethod(targetClassName, "claim");
        }
        if (mixinClassName.contains("ftbquests.ItemTaskSequenceAccessor")) {
            return isClassPresent("dev.ftb.mods.ftbquests.quest.task.Task")
                    && hasMethod(targetClassName, "checkTaskSequence");
        }
        if (mixinClassName.contains("ftbquests.FTBQuestsNetClientMixin")) {
            return isClassPresent("dev.ftb.mods.ftbquests.client.FTBQuestsNetClient")
                    && hasMethod(targetClassName, "syncTeamData")
                    && hasMethod(targetClassName, "updateTaskProgress");
        }
        if (mixinClassName.contains("ftbquests.ClearRepeatCooldownMessageMixin")) {
            return isClassPresent("dev.ftb.mods.ftbquests.net.ClearRepeatCooldownMessage")
                    && hasMethod(targetClassName, "lambda$handle$0");
        }
        if (mixinClassName.contains("namelesstrinkets")) {
            return isClassPresent("com.cozary.nameless_trinkets.items.trinkets.SuperMagnet")
                    && hasMethod(targetClassName, "curioTick");
        }
        if (mixinClassName.contains("YuushaNineSwordBooks")) {
            // Target is Chapter of Yuusha; body uses SlashBlade's ItemSlashBlade.
            return isClassPresent("mods.flammpfeil.slashblade.item.ItemSlashBlade");
        }
        if (mixinClassName.contains("slashblade.RecipeManagerMixin")) {
            return isClassPresent(
                    "mods.flammpfeil.slashblade.recipe.SlashBladeSmithingRecipe$Serializer");
        }
        if (mixinClassName.contains("slashblade.SlashBladeTEISRGridMixin")) {
            return isClassPresent("mods.flammpfeil.slashblade.client.renderer.SlashBladeTEISR")
                    && hasMethod(targetClassName, "renderBlade")
                    && hasMethod(targetClassName, "renderIcon");
        }
        if (mixinClassName.contains("slashblade.BladeRenderStateGridMixin")) {
            return isClassPresent("mods.flammpfeil.slashblade.client.renderer.util.BladeRenderState")
                    && hasMethod(targetClassName, "renderOverrided");
        }
        if (mixinClassName.contains("AddonEventHandler")) {
            // Target is Enigmatic Addons; body calls Enigmatic Legacy's SuperpositionHandler.
            return isClassPresent("com.aizistral.enigmaticlegacy.handlers.SuperpositionHandler");
        }
        if (mixinClassName.contains("enigmaticaddons.ArtificialFlowerMixin")) {
            return isClassPresent("auviotre.enigmatic.addon.handlers.SuperAddonHandler")
                    && hasMethod(targetClassName, "onEffectApply")
                    && hasMethod("auviotre.enigmatic.addon.handlers.SuperAddonHandler", "getAllItem");
        }
        if (mixinClassName.contains("moonstone.NineSwordBooks")) {
            // Target is Moonstone; body uses Curios' SlotContext.
            return isClassPresent("top.theillusivec4.curios.api.SlotContext");
        }
        if (mixinClassName.contains("terraequipment.AutoPotionTickerMixin")) {
            return isClassPresent("com.inolia_zaicek.terra_equipment.util.AutoPotionTicker")
                    && isClassPresent("com.inolia_zaicek.terra_equipment.item.EffectPotionItem")
                    && isClassPresent("com.inolia_zaicek.terra_equipment.config.TEConfig")
                    && hasMethod(targetClassName, "onPlayerTick");
        }
        if (mixinClassName.contains("sophisticatedbackpacks.StorageUpgradeSlotMixin")) {
            return hasField(targetClassName, "slotIndex");
        }
        if (mixinClassName.contains("sophisticatedbackpacks.StorageContainerMenuBaseMixin")) {
            return hasMethod(targetClassName, "getOpenContainer");
        }
        if (mixinClassName.contains("sophisticatedbackpacks.RestockUpgradeWrapperMixin")
                || mixinClassName.contains("sophisticatedbackpacks.RefillUpgradeWrapperMixin")
                || mixinClassName.contains("sophisticatedbackpacks.FeedingUpgradeWrapperMixin")
                || mixinClassName.contains("sophisticatedbackpacks.CompactingUpgradeWrapperMixin")
                || mixinClassName.contains("sophisticatedbackpacks.PickupUpgradeWrapperMixin")) {
            return hasMethod(targetClassName, "getFilterLogic");
        }
        if (mixinClassName.contains("sophisticatedbackpacks.MagnetUpgradeWrapperMixin")) {
            return hasMethod(targetClassName, "getFilterLogic")
                    && hasMethod(targetClassName, "shouldPickupItems");
        }
        if (mixinClassName.contains("jei.BookmarkOverlayAccessor")) {
            return hasField(targetClassName, "bookmarkList");
        }
        if (mixinClassName.contains("retraining.RetrainingTradeLockMixin")) {
            return isClassPresent("com.mrbysco.retraining.CommonRetraining")
                    && hasMethod(targetClassName, "resetTrades");
        }
        if (mixinClassName.contains("tradecycling.TradeCyclingTradeLockMixin")) {
            return isClassPresent("de.maxhenkel.tradecycling.TradeCyclingMod")
                    && hasMethod(targetClassName, "onCycleTrades");
        }
        if (mixinClassName.contains("traderefresh.TradeRefreshTradeLockMixin")) {
            return isClassPresent("dev.xkmc.traderefresh.network.RefreshToServer")
                    && hasMethod(targetClassName, "handle");
        }
        if (mixinClassName.contains("jei.GuiIconToggleButtonAccessor")) {
            return hasField(targetClassName, "button");
        }
        if (mixinClassName.contains("jei.RecipeGuiLayoutsMixin")) {
            return hasField(targetClassName, "recipeLayoutsWithButtons");
        }
        if (mixinClassName.contains("jei.RecipesGuiMixin")) {
            return hasMethod(targetClassName, "updateLayout");
        }
        if (mixinClassName.contains("jei.IngredientFilterSolCarrotMixin")) {
            return hasField(targetClassName, "ingredientListCached")
                    && hasMethod(targetClassName, "notifyListenersOfChange");
        }
        if (mixinClassName.contains("jei.ElementSearchSolCarrotMixin")) {
            return hasField(targetClassName, "allElements")
                    && hasMethod(targetClassName, "getSearchResults");
        }
        if (mixinClassName.contains("jei.ElementSearchLowMemSolCarrotMixin")) {
            return hasField(targetClassName, "elementInfoList")
                    && hasMethod(targetClassName, "getSearchResults");
        }
        if (mixinClassName.contains("jei.IngredientListRendererGridMixin")) {
            return isClassPresent("mezz.jei.gui.overlay.IngredientListRenderer")
                    && hasMethod(targetClassName, "renderBatch");
        }
        if (mixinClassName.contains("jei.ItemStackRendererGridMixin")) {
            return isClassPresent("mezz.jei.library.render.ItemStackRenderer")
                    && hasMethod(targetClassName, "render");
        }
        if (mixinClassName.contains("refinedstorage.CraftingTaskAccessor")) {
            return hasField(targetClassName, "network");
        }
        if (mixinClassName.contains("beyonddimensions.NetMagnetItemMixin")) {
            return isClassPresent("com.wintercogs.beyonddimensions.common.item.NetMagnetItem")
                    && hasMethod(targetClassName, "workContent");
        }
        if (mixinClassName.contains("beyonddimensions.DimensionsNetGuiAutoEatMixin")) {
            return isClassPresent("com.wintercogs.beyonddimensions.client.gui.DimensionsNetGUI")
                    && (hasMethod(targetClassName, "init") || hasMethod(targetClassName, "m_7856_"));
        }
        if (mixinClassName.contains("refinedstorage.GridTransferMessageAccessor")) {
            return hasField(targetClassName, "recipe");
        }
        if (mixinClassName.contains("majruszsdifficulty.MajruszItemHelperMixin")) {
            return isClassPresent("com.majruszlibrary.item.ItemHelper");
        }
        return true;
    }

    static boolean isYzzzOwnedRefinedStorageMixin(String mixinClassName) {
        return mixinClassName.endsWith(".refinedstorage.CraftingGridBehaviorMixin")
                || mixinClassName.endsWith(".refinedstorage.IngredientTrackerMixin");
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {}

    private static boolean isClassPresent(String className) {
        // Use resource lookup instead of Class.forName() to avoid triggering
        // class loading during mixin transformation — Class.forName() inside
        // shouldApplyMixin causes ReEntrantTransformerError.
        String resource = className.replace('.', '/') + ".class";
        return RSIntegrationMixinPlugin.class.getClassLoader().getResource(resource) != null;
    }

    private static boolean hasField(String className, String fieldName) {
        return readClass(className, node -> node.fields.stream()
                .anyMatch(field -> fieldName.equals(field.name)));
    }

    private static boolean hasMethod(String className, String methodName) {
        return readClass(className, node -> node.methods.stream()
                .anyMatch(method -> methodName.equals(method.name)));
    }

    private interface ClassPredicate {
        boolean test(ClassNode node);
    }

    private static boolean readClass(String className, ClassPredicate predicate) {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream input = RSIntegrationMixinPlugin.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return predicate.test(node);
        } catch (Exception ignored) {
            return false;
        }
    }
}
