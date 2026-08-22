package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.HashMap;
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
        INetwork currentNetwork = net.minecraftforge.fml.ModList.get().isLoaded(
                com.huanghuang.rsintegration.util.ModIds.REFINED_STORAGE)
                ? CraftPacketUtils.resolveNetworkForCraft(player, dimension, lookupPos)
                : null;
        Map<StackKey, Integer> currentAvailable = MaterialSources.listAllAvailable(player, currentNetwork);
        return snapshot.networkFingerprint().equals(networkFingerprint(currentNetwork, currentAvailable))
                && snapshot.bindingFingerprint().equals(bindingFingerprint(player, dimension, lookupPos));
    }

    /** Revalidates a preview snapshot for execution without tying it to a preview request generation. */
    public static boolean revalidateForExecution(ServerPlayer player, PlanningSnapshot snapshot,
                                                 ResourceKey<Level> dimension, BlockPos lookupPos) {
        if (player.hasDisconnected() || player.isRemoved()
                || !CraftPlanningRevision.isCurrent(snapshot.recipeRevision())) {
            return false;
        }
        INetwork currentNetwork = net.minecraftforge.fml.ModList.get().isLoaded(
                com.huanghuang.rsintegration.util.ModIds.REFINED_STORAGE)
                ? CraftPacketUtils.resolveNetworkForCraft(player, dimension, lookupPos)
                : null;
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

    /**
     * Preview reuse may ignore unrelated inventory churn, but only when the cached DAG declares
     * every initial item it consumes and those exact supplies are still present. Execution never
     * trusts this shortcut and performs its normal authoritative resolution.
     */
    public static boolean sameRelevantState(PlanningSnapshot cached, PlanningSnapshot current,
                                            PlanResponse plan) {
        if (cached.recipeRevision() != current.recipeRevision()
                || !cached.recipeId().equals(current.recipeId())
                || !cached.forcedRecipes().equals(current.forcedRecipes())
                || !cached.bindingFingerprint().equals(current.bindingFingerprint())
                || !networkIdentity(cached.networkFingerprint())
                .equals(networkIdentity(current.networkFingerprint()))) {
            return false;
        }
        if (cached.availableItems().equals(current.availableItems())) return true;
        return plan != null && plan.success() && hasRequiredInitialSupply(plan,
                current.availableItems());
    }

    static boolean hasRequiredInitialSupply(PlanResponse plan,
                                            Map<StackKey, Integer> available) {
        PlanGraphView graph = plan.graph();
        if (graph == null || !graph.unresolved().isEmpty()) return false;
        Map<StackKey, Integer> required = new HashMap<>();
        for (PlanGraphView.EdgeView edge : graph.edges()) {
            if (edge.source().initial()) mergeRequirement(required, edge.material(), edge.quantity());
        }
        for (PlanGraphView.RootView root : graph.roots()) {
            for (PlanGraphView.RootEdgeView allocation : root.allocations()) {
                if (allocation.source().initial()) {
                    mergeRequirement(required, allocation.material(), allocation.quantity());
                }
            }
        }
        for (Map.Entry<StackKey, Integer> entry : required.entrySet()) {
            if (available.getOrDefault(entry.getKey(), 0) < entry.getValue()) return false;
        }
        return true;
    }

    private static void mergeRequirement(Map<StackKey, Integer> required,
                                         net.minecraft.world.item.ItemStack stack, int quantity) {
        if (stack == null || stack.isEmpty() || quantity <= 0) return;
        String tag = stack.getTag() == null || stack.getTag().isEmpty()
                ? null : stack.getTag().toString();
        required.merge(new StackKey(stack.getItem(), tag), quantity, (left, right) -> {
            long total = (long) left + right;
            return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        });
    }

    private static String networkIdentity(String fingerprint) {
        int separator = fingerprint == null ? -1 : fingerprint.indexOf(':');
        return separator < 0 ? String.valueOf(fingerprint) : fingerprint.substring(0, separator);
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
