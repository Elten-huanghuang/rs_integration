package com.huanghuang.rsintegration.mods.lychee;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.resonance.bridge.ResonanceInventoryBridge;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Collection;

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
    private static final ResourceLocation AETHER_SKYROOT_POISON_BUCKET_ID =
            new ResourceLocation("aether", "skyroot_poison_bucket");
    private static final ResourceLocation HOT_SPRING_BUCKET_ID =
            new ResourceLocation("immortalers_delight", "hot_spring_bucket");

    private LycheeVirtualCatalysts() {}

    public static boolean hasPowderSnowBucket(@Nullable ServerPlayer player) {
        return hasCatalyst(player, POWDER_SNOW_BUCKET);
    }

    public static boolean hasCatalyst(@Nullable ServerPlayer player, int requiredMask) {
        if (player == null || requiredMask == 0) return false;
        var views = ResonanceInventoryBridge.getViews(player);
        int availableMask = catalystMask(views);
        if (views.isEmpty()) {
            RSIntegrationMod.LOGGER.debug("[RSI-Lychee] no Resonance Disk for player={} requiredMask={}",
                    player.getGameProfile().getName(), requiredMask);
        } else {
            RSIntegrationMod.LOGGER.debug("[RSI-Lychee] catalyst check player={} requiredMask={} availableMask={} stacks={}",
                    player.getGameProfile().getName(), requiredMask, availableMask,
                    views.stream().flatMap(view -> view.storedStacks().stream())
                            .map(ResonanceStorageView.StoredStack::stack)
                            .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                            .toList());
        }
        return hasCatalyst(availableMask, requiredMask);
    }

    public static boolean hasCatalyst(int availableMask, int requiredMask) {
        return requiredMask != 0 && (availableMask & requiredMask) == requiredMask;
    }

    public static int catalystMask(Collection<? extends ResonanceStorageView> views) {
        int mask = 0;
        if (views == null) return 0;
        for (ResonanceStorageView view : views) {
            for (ResonanceStorageView.StoredStack stored : view.storedStacks()) {
                ItemStack stack = stored.stack();
                if (stack.isEmpty()) continue;
                mask |= catalystForItemId(BuiltInRegistries.ITEM.getKey(stack.getItem()));
                if (mask == ALL_CATALYSTS) return mask;
            }
        }
        return mask;
    }

    static int catalystForItemId(@Nullable ResourceLocation id) {
        if (POWDER_SNOW_BUCKET_ID.equals(id)) return POWDER_SNOW_BUCKET;
        if (GREEK_FIRE_BUCKET_ID.equals(id)) return GREEK_FIRE_BUCKET;
        if (DWARVEN_OIL_BUCKET_ID.equals(id)) return DWARVEN_OIL_BUCKET;
        if (DEEP_AETHER_POISON_BUCKET_ID.equals(id)
                || AETHER_SKYROOT_POISON_BUCKET_ID.equals(id)) {
            return DEEP_AETHER_POISON_BUCKET;
        }
        if (HOT_SPRING_BUCKET_ID.equals(id)) return HOT_SPRING_BUCKET;
        return 0;
    }
}
