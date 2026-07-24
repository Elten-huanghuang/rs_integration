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
        if (mixinClassName.contains("crockpot.CrockPotMenuMixin")) {
            return hasField(targetClassName, "blockEntity");
        }
        if (mixinClassName.contains("ironfurnaces.BlockIronFurnaceTileBaseMixin")) {
            return isClassPresent("ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase");
        }
        if (mixinClassName.contains("forbidden.ClibanoMainBlockEntityAccessor")) {
            return isClassPresent("com.stal111.forbidden_arcanus.common.block.entity.clibano.ClibanoMainBlockEntity")
                    && hasMethod(targetClassName, "getBurnDuration");
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
            return isClassPresent("dev.ftb.mods.ftbquests.quest.ServerQuestFile")
                    && isClassPresent("dev.ftb.mods.ftbquests.quest.TeamData")
                    && isClassPresent("dev.ftb.mods.ftbquests.quest.task.ItemTask");
        }
        if (mixinClassName.contains("namelesstrinkets")) {
            return isClassPresent("com.cozary.nameless_trinkets.items.trinkets.SuperMagnet")
                    && hasMethod(targetClassName, "curioTick");
        }
        if (mixinClassName.contains("YuushaNineSwordBooks")) {
            // Target is Chapter of Yuusha; body uses SlashBlade's ItemSlashBlade.
            return isClassPresent("mods.flammpfeil.slashblade.item.ItemSlashBlade");
        }
        if (mixinClassName.contains("AddonEventHandler")) {
            // Target is Enigmatic Addons; body calls Enigmatic Legacy's SuperpositionHandler.
            return isClassPresent("com.aizistral.enigmaticlegacy.handlers.SuperpositionHandler");
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
        if (mixinClassName.contains("jei.GuiIconToggleButtonAccessor")) {
            return hasField(targetClassName, "button");
        }
        if (mixinClassName.contains("jei.RecipeGuiLayoutsMixin")) {
            return hasField(targetClassName, "recipeLayoutsWithButtons");
        }
        if (mixinClassName.contains("jei.RecipesGuiMixin")) {
            return hasMethod(targetClassName, "updateLayout");
        }
        if (mixinClassName.contains("refinedstorage.CraftingTaskAccessor")) {
            return hasField(targetClassName, "network");
        }
        if (mixinClassName.contains("refinedstorage.GridTransferMessageAccessor")) {
            return hasField(targetClassName, "recipe");
        }
        if (mixinClassName.contains("majruszsdifficulty.MajruszItemHelperMixin")) {
            return isClassPresent("com.majruszlibrary.item.ItemHelper");
        }
        return true;
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
