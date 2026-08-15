package com.huanghuang.rsintegration.mods.lychee;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.huanghuang.rsintegration.resonance.passive.PassiveEffectEngine;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/** Presence-only substrate catalysts stored in ordinary Resonance Disk slots. */
public final class LycheeVirtualCatalysts {

    public static final int POWDER_SNOW_BUCKET = 1;
    public static final int GREEK_FIRE_BUCKET = 1 << 1;
    public static final int DWARVEN_OIL_BUCKET = 1 << 2;
    public static final int DEEP_AETHER_POISON_BUCKET = 1 << 3;
    public static final int HOT_SPRING_BUCKET = 1 << 4;

    private static final int ALL_CATALYSTS = POWDER_SNOW_BUCKET | GREEK_FIRE_BUCKET
            | DWARVEN_OIL_BUCKET | DEEP_AETHER_POISON_BUCKET | HOT_SPRING_BUCKET;

    private static final ResourceLocation POWDER_SNOW_BUCKET_ID =
            new ResourceLocation("minecraft", "powder_snow_bucket");
    private static final ResourceLocation GREEK_FIRE_BUCKET_ID =
            new ResourceLocation("locusazzurro_icaruswings", "greek_fire_bucket");
    private static final ResourceLocation DWARVEN_OIL_BUCKET_ID =
            new ResourceLocation("embers", "dwarven_oil_bucket");
    private static final ResourceLocation DEEP_AETHER_POISON_BUCKET_ID =
            new ResourceLocation("deep_aether", "poison_bucket");
    private static final ResourceLocation HOT_SPRING_BUCKET_ID =
            new ResourceLocation("immortalers_delight", "hot_spring_bucket");

    private LycheeVirtualCatalysts() {}

    public static boolean hasPowderSnowBucket(@Nullable ServerPlayer player) {
        return hasCatalyst(player, POWDER_SNOW_BUCKET);
    }

    public static boolean hasPowderSnowBucket(@Nullable ResonanceDiskWrapper disk) {
        return hasCatalyst(catalystMask(disk), POWDER_SNOW_BUCKET);
    }

    public static boolean hasCatalyst(@Nullable ServerPlayer player, int requiredMask) {
        if (player == null || requiredMask == 0) return false;
        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        return network != null && hasCatalyst(
                catalystMask(PassiveEffectEngine.findResonanceDisk(network)), requiredMask);
    }

    public static boolean hasCatalyst(int availableMask, int requiredMask) {
        return requiredMask != 0 && (availableMask & requiredMask) == requiredMask;
    }

    public static int catalystMask(@Nullable ResonanceDiskWrapper disk) {
        if (disk == null) return 0;
        int mask = 0;
        for (ItemStack stack : disk.getInternalStacks()) {
            if (stack.isEmpty()) continue;
            mask |= catalystForItemId(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            if (mask == ALL_CATALYSTS) break;
        }
        return mask;
    }

    static int catalystForItemId(@Nullable ResourceLocation id) {
        if (POWDER_SNOW_BUCKET_ID.equals(id)) return POWDER_SNOW_BUCKET;
        if (GREEK_FIRE_BUCKET_ID.equals(id)) return GREEK_FIRE_BUCKET;
        if (DWARVEN_OIL_BUCKET_ID.equals(id)) return DWARVEN_OIL_BUCKET;
        if (DEEP_AETHER_POISON_BUCKET_ID.equals(id)) return DEEP_AETHER_POISON_BUCKET;
        if (HOT_SPRING_BUCKET_ID.equals(id)) return HOT_SPRING_BUCKET;
        return 0;
    }
}
