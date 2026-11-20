package com.huanghuang.rsintegration.mods.goety;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

/** Shared safety classification for Goety Dark Altar rituals. */
public final class GoetyRitualPolicy {
    public enum Execution {
        AUTOMATIC,
        MANUAL_CONFIRMATION,
        UNSUPPORTED
    }

    private GoetyRitualPolicy() {}

    public static Execution classify(Object recipe, Object ritual) {
        if (ritual == null) return Execution.UNSUPPORTED;
        return classify(recipe, ritual, recipe instanceof Recipe<?> typed
                ? BuiltInRegistries.ITEM.getKey(typed.getResultItem(RegistryAccess.EMPTY).getItem())
                : null);
    }

    static Execution classify(Object recipe, Object ritual, ResourceLocation output) {
        if (ritual == null) return Execution.UNSUPPORTED;
        String name = ritual.getClass().getSimpleName();
        if (name.equals("TeleportRitual")) return Execution.UNSUPPORTED;
        // 部分附属仪式只执行世界效果，JEI 占位物不会由祭坛产出。
        if (output != null && "goety".equals(output.getNamespace())
                && ("jei_dummy/none".equals(output.getPath())
                    || "jei_dummy/sacrifice".equals(output.getPath()))) {
            return Execution.MANUAL_CONFIRMATION;
        }
        if (name.equals("SummonRitual") || name.equals("ConvertRitual")) {
            return Execution.MANUAL_CONFIRMATION;
        }
        try {
            Object value = recipe.getClass().getMethod("requiresSacrifice").invoke(recipe);
            return Boolean.TRUE.equals(value)
                    ? Execution.MANUAL_CONFIRMATION
                    : Execution.AUTOMATIC;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            // 无法检查的方法签名也视为未知仪式，必须由玩家确认。
            return Execution.MANUAL_CONFIRMATION;
        }
    }
}
