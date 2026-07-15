package com.huanghuang.rsintegration.mixin.plugin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

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
        if (mixinClassName.contains("InventoryHelperExternalItemMixin")) {
            return isClassPresent("dev.ftb.mods.ftbquests.quest.ServerQuestFile")
                    && isClassPresent("dev.ftb.mods.ftbquests.quest.TeamData")
                    && isClassPresent("dev.ftb.mods.ftbquests.quest.task.ItemTask");
        }
        if (mixinClassName.contains("namelesstrinkets")) {
            return isClassPresent("com.cozary.nameless_trinkets.items.trinkets.SuperMagnet");
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
}
