package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.Map;

/** Server-thread validation for cached and background planning results. */
public final class PlanningStateValidator {
    private PlanningStateValidator() {}

    public static boolean revalidate(ServerPlayer player, PlanningSnapshot snapshot,
                                     ResourceKey<Level> dimension, BlockPos lookupPos,
                                     PlanRequestService requests) {
        if (player.hasDisconnected() || player.isRemoved()
                || !CraftPlanningRevision.isCurrent(snapshot.recipeRevision())
                || !requests.isCurrent(player.getUUID(), snapshot.requestGeneration())) {
            return false;
        }
        INetwork currentNetwork = CraftPacketUtils.resolveNetworkForCraft(player, dimension, lookupPos);
        Map<StackKey, Integer> currentAvailable = MaterialSources.listAllAvailable(player, currentNetwork);
        return snapshot.networkFingerprint().equals(networkFingerprint(currentNetwork, currentAvailable))
                && snapshot.bindingFingerprint().equals(bindingFingerprint(player, dimension, lookupPos));
    }

    public static boolean sameState(PlanningSnapshot left, PlanningSnapshot right) {
        return left.recipeRevision() == right.recipeRevision()
                && left.recipeId().equals(right.recipeId())
                && left.availableItems().equals(right.availableItems())
                && left.forcedRecipes().equals(right.forcedRecipes())
                && left.networkFingerprint().equals(right.networkFingerprint())
                && left.bindingFingerprint().equals(right.bindingFingerprint());
    }

    public static String networkFingerprint(@Nullable INetwork network,
                                            Map<StackKey, Integer> available) {
        int inventoryHash = 1;
        for (Map.Entry<StackKey, Integer> entry : available.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(StackKey::toString))).toList()) {
            inventoryHash = 31 * inventoryHash + entry.getKey().hashCode();
            inventoryHash = 31 * inventoryHash + entry.getValue();
        }
        return (network == null ? "none" : Integer.toHexString(System.identityHashCode(network)))
                + ':' + Integer.toHexString(inventoryHash);
    }

    public static String bindingFingerprint(ServerPlayer player, ResourceKey<Level> dimension,
                                            BlockPos pos) {
        ServerLevel level = player.getServer().getLevel(dimension);
        if (level == null || !level.hasChunkAt(pos)) return "missing";
        var blockEntity = level.getBlockEntity(pos);
        String type = blockEntity == null ? "none" : blockEntity.getType().toString();
        var persisted = AltarBindingRegistry.findBindingEntry(player, dimension.location(), pos);
        return dimension.location() + ":" + pos.asLong() + ':' + type + ':'
                + level.getBlockState(pos).getBlock() + ':'
                + (persisted == null ? "none" : persisted.blockKey());
    }
}
