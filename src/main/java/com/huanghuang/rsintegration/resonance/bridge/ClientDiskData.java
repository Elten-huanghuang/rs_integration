package com.huanghuang.rsintegration.resonance.bridge;

import com.huanghuang.rsintegration.mods.lychee.LycheeVirtualCatalysts;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** 服务端同步的共振盘客户端缓存，供资源提示和 TaCZ 换弹预检查使用。 */
public final class ClientDiskData {

    private static int gemCount;
    private static int lycheeCatalystMask;
    private static int abilityMask;
    private static long revision = -1L;
    private static List<ItemStack> taczAmmo = List.of();

    private ClientDiskData() {}

    public static int getGemCount() {
        return gemCount;
    }

    public static void setGemCount(int count) {
        gemCount = count;
    }

    public static boolean hasPowderSnowBucket() {
        return hasLycheeCatalyst(
                LycheeVirtualCatalysts.POWDER_SNOW_BUCKET);
    }

    public static boolean hasLycheeCatalyst(int requiredMask) {
        return hasCatalyst(requiredMask);
    }

    public static boolean hasCatalyst(int requiredMask) {
        return LycheeVirtualCatalysts
                .hasCatalyst(lycheeCatalystMask, requiredMask);
    }

    public static int lycheeCatalystMask() {
        return lycheeCatalystMask;
    }

    public static boolean hasAbility(int ability) {
        return (abilityMask & ability) == ability;
    }

    public static int abilityMask() {
        return abilityMask;
    }

    public static long revision() {
        return revision;
    }

    public static void apply(int count, int catalystMask, int newAbilityMask,
                             long newRevision) {
        apply(count, catalystMask, newAbilityMask, newRevision, List.of());
    }

    public static List<ItemStack> getTaczAmmo() {
        return taczAmmo.stream().map(ItemStack::copy).toList();
    }

    public static void apply(int count, int catalystMask, int newAbilityMask,
                             long newRevision, List<ItemStack> ammo) {
        if (newRevision < revision) return;
        gemCount = count;
        lycheeCatalystMask = catalystMask;
        abilityMask = newAbilityMask;
        revision = newRevision;
        taczAmmo = ammo.stream().map(ItemStack::copy).toList();
    }

    public static void clear() {
        gemCount = 0;
        lycheeCatalystMask = 0;
        abilityMask = 0;
        revision = -1L;
        taczAmmo = List.of();
    }
}
