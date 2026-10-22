package com.huanghuang.rsintegration.machine.client;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.feature.craftingstation.MachineTypeCompatibility;
import com.huanghuang.rsintegration.machine.client.data.BindingCache;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.Locale;

/** 客户端机器名称解析：优先读取实际绑定方块，缺失时才回退到类型标签。 */
@OnlyIn(Dist.CLIENT)
public final class MachineDisplayNameResolver {
    private MachineDisplayNameResolver() {}

    public static Component displayName(BindingInfo info) {
        return BindingEventHandler.resolveBlockName(
                info.blockKey(), info.blockRegKey(), info.displayStack());
    }

    public static String displayText(BindingInfo info) {
        return displayName(info).getString();
    }

    /**
     * 从当前绑定中寻找最贴近配方的一台机器。
     * 配方路径中的机器子类型只用于同类型多机器场景的排序，不参与类型判定。
     */
    @Nullable
    public static BindingInfo findBestBinding(
            @Nullable ModType type, @Nullable ResourceLocation recipeId) {
        if (type == null || type == ModType.GENERIC || type.isVirtual()) return null;
        BindingInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        BindingInfo blockedFallback = null;
        int blockedFallbackScore = Integer.MIN_VALUE;
        for (BindingInfo info : BindingCache.getInstance().getAll()) {
            ModType bindingType = ModType.fromBlockKey(info.blockKey());
            if (bindingType == null
                    || !MachineTypeCompatibility.isCompatible(
                    type.id(), bindingType.id())) {
                continue;
            }

            int score = subtypeScore(info.blockKey(), recipeId);
            if (MachinePreferenceClient.isBlocked(info)) {
                if (score > blockedFallbackScore) {
                    blockedFallback = info;
                    blockedFallbackScore = score;
                }
                continue;
            }
            if (info.displayStack() != null && !info.displayStack().isEmpty()) score += 5;
            if (score > bestScore) {
                best = info;
                bestScore = score;
            }
        }
        return best != null ? best : blockedFallback;
    }

    @Nullable
    public static Component findDisplayName(
            @Nullable ModType type, @Nullable ResourceLocation recipeId) {
        BindingInfo binding = findBestBinding(type, recipeId);
        return binding == null ? null : displayName(binding);
    }

    private static int subtypeScore(@Nullable String blockKey,
                                    @Nullable ResourceLocation recipeId) {
        if (blockKey == null || recipeId == null) return 0;
        String path = recipeId.getPath().toLowerCase(Locale.ROOT);
        int separator = path.indexOf('/');
        String category = separator > 0 ? path.substring(0, separator) : path;
        if (category.isEmpty() || "kjs".equals(category)) return 0;
        return blockKey.toLowerCase(Locale.ROOT).contains(category)
                ? 100 + category.length() : 0;
    }
}
