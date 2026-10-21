package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponseDraft;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.MachineFavoritesSavedData;
import com.huanghuang.rsintegration.network.binding.MachineFavoriteKey;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Server-thread validation for cached and background planning results. */
public final class PlanningStateValidator {
    private PlanningStateValidator() {}

    public static boolean revalidate(ServerPlayer player, PlanningSnapshot snapshot,
                                     ResourceKey<Level> dimension, BlockPos lookupPos,
                                     PlanRequestService requests) {
        return revalidate(player, snapshot, dimension, lookupPos, requests, null);
    }

    public static boolean revalidate(ServerPlayer player, PlanningSnapshot snapshot,
                                     ResourceKey<Level> dimension, BlockPos lookupPos,
                                     PlanRequestService requests,
                                     @Nullable StorageReference selectedReference) {
        if (player.hasDisconnected() || player.isRemoved()
                || !CraftPlanningRevision.isCurrent(snapshot.recipeRevision())
                || !requests.isCurrent(player.getUUID(), snapshot.requestGeneration())) {
            return false;
        }
        CraftStorageEndpoint currentEndpoint = selectedReference == null
                ? CraftPacketUtils.resolveEndpointForCraft(player, dimension, lookupPos)
                .orElse(null)
                : null;
        Map<StackKey, Long> currentAvailable;
        if (selectedReference != null) {
            var endpoint = CraftStorageEndpoints
                    .resolve(selectedReference, player);
            currentAvailable = endpoint.isPresent()
                    ? MaterialSources.listAllAvailable(player, endpoint.orElseThrow())
                    : Map.of();
        } else {
            currentAvailable = MaterialSources.listAllAvailable(player, currentEndpoint);
        }
        String fingerprint = selectedReference == null
                ? networkFingerprint(reference(currentEndpoint), currentAvailable)
                : networkFingerprint(selectedReference, currentAvailable);
        return snapshot.networkFingerprint().equals(fingerprint)
                && snapshot.bindingFingerprint().equals(bindingFingerprint(player, dimension, lookupPos));
    }

    /** Revalidates a preview snapshot for execution without tying it to a preview request generation. */
    public static boolean revalidateForExecution(ServerPlayer player, PlanningSnapshot snapshot,
                                                 ResourceKey<Level> dimension, BlockPos lookupPos) {
        return revalidateForExecution(player, snapshot, dimension, lookupPos, null);
    }

    /**
     * Revalidates an execution snapshot against the same explicitly selected
     * storage backend used during planning.  A null reference retains the
     * legacy current-RS lookup for packets that predate backend selection.
     */
    public static boolean revalidateForExecution(ServerPlayer player, PlanningSnapshot snapshot,
                                                 ResourceKey<Level> dimension, BlockPos lookupPos,
                                                 @Nullable StorageReference selectedReference) {
        return revalidateForExecution(player, snapshot, null, dimension, lookupPos,
                selectedReference);
    }

    /**
     * Revalidates an execution cache while allowing unrelated inventory churn.
     *
     * <p>A preview is a reservation candidate, not a lock on every item in the
     * network.  Comparing the complete inventory fingerprint made two players
     * invalidate each other's plans even when they used different materials.
     * The plan graph is therefore used to verify only the supplies that this
     * execution actually consumes; network identity, recipe revision and
     * machine binding remain authoritative.</p>
     */
    public static boolean revalidateForExecution(ServerPlayer player, PlanningSnapshot snapshot,
                                                 @Nullable PlanResponse plan,
                                                 ResourceKey<Level> dimension, BlockPos lookupPos,
                                                 @Nullable StorageReference selectedReference) {
        if (player.hasDisconnected() || player.isRemoved()
                || !CraftPlanningRevision.isCurrent(snapshot.recipeRevision())) {
            return false;
        }
        return revalidateRelevantGraph(player, snapshot,
                plan == null ? null : plan.graph(), dimension, lookupPos, selectedReference);
    }

    private static boolean revalidateRelevantGraph(ServerPlayer player, PlanningSnapshot snapshot,
                                                   @Nullable PlanGraphView graph,
                                                   ResourceKey<Level> dimension, BlockPos lookupPos,
                                                   @Nullable StorageReference selectedReference) {
        CraftStorageEndpoint endpoint = selectedReference == null
                ? CraftPacketUtils.resolveEndpointForCraft(player, dimension, lookupPos)
                .orElse(null)
                : null;
        if (selectedReference != null) {
            var resolved = CraftStorageEndpoints.resolve(selectedReference, player);
            if (resolved.isEmpty()) return false;
            endpoint = resolved.orElseThrow();
        }
        String fingerprint = selectedReference == null
                ? networkFingerprint(reference(endpoint), Map.of())
                : networkFingerprint(selectedReference, Map.of());
        if (!networkIdentity(snapshot.networkFingerprint()).equals(networkIdentity(fingerprint))) {
            return false;
        }
        if (!snapshot.bindingFingerprint().equals(bindingFingerprint(player, dimension, lookupPos))) {
            return false;
        }
        if (graph == null) return true;
        if (!graph.unresolved().isEmpty()) return false;
        Map<StackKey, Long> required = requiredInitialSupply(graph);
        Map<StackKey, Long> currentAvailable = MaterialSources.listAvailableForTypes(
                player, endpoint, itemTypes(required));
        return hasRequiredInitialSupply(required, currentAvailable);
    }

    /**
     * 校验已完成预览。预览不是库存锁：有图时只核验实际初始供料，
     * 无图或缺材料时只核验请求、网络与绑定；执行阶段会重新规划。
     */
    public static boolean revalidatePreview(ServerPlayer player, PlanningSnapshot snapshot,
                                            PlanResponseDraft draft,
                                            ResourceKey<Level> dimension, BlockPos lookupPos,
                                            PlanRequestService requests,
                                            @Nullable StorageReference selectedReference) {
        if (player.hasDisconnected() || player.isRemoved()
                || !CraftPlanningRevision.isCurrent(snapshot.recipeRevision())
                || !requests.isCurrent(player.getUUID(), snapshot.requestGeneration())) {
            return false;
        }
        PlanGraphView graph = draft.graph();
        if (draft.success() && graph != null) {
            return revalidateRelevantGraph(player, snapshot, graph, dimension, lookupPos,
                    selectedReference);
        }

        // 无图预览（尤其缺材料计划）只校验请求代次、网络身份和机器绑定。
        // 预览不是库存锁；库存变化会在真正执行时重新规划并做原子预留。
        // 若在这里要求完整库存相等，无关产物变化或中途补料会把已经算好的
        // 缺材料树静默丢弃，客户端只能一直停在“规划已取消”。
        return revalidateRelevantGraph(player, snapshot, null, dimension, lookupPos,
                selectedReference);
    }

    public static boolean sameState(PlanningSnapshot left, PlanningSnapshot right) {
        return left.recipeRevision() == right.recipeRevision()
                && left.recipeId().equals(right.recipeId())
                && left.availableItems().equals(right.availableItems())
                && left.forcedRecipes().equals(right.forcedRecipes())
                && left.preferences().equals(right.preferences())
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
                || !cached.preferences().equals(current.preferences())
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
                                            Map<StackKey, Long> available) {
        PlanGraphView graph = plan.graph();
        if (graph == null) {
            // Pure responses intentionally do not carry a server DAG. Their
            // material table includes craftable intermediates, so it cannot be
            // used as an initial-supply bill. The execution ledger performs
            // the authoritative atomic extraction instead.
            return true;
        }
        return graph.unresolved().isEmpty()
                && hasRequiredInitialSupply(requiredInitialSupply(graph), available);
    }

    private static Map<StackKey, Long> requiredInitialSupply(PlanGraphView graph) {
        if (graph == null) return Map.of();
        Map<StackKey, Long> required = new HashMap<>();
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
        return Map.copyOf(required);
    }

    private static boolean hasRequiredInitialSupply(Map<StackKey, Long> required,
                                                    Map<StackKey, Long> available) {
        for (Map.Entry<StackKey, Long> entry : required.entrySet()) {
            if (available.getOrDefault(entry.getKey(), 0L) < entry.getValue()) return false;
        }
        return true;
    }

    private static Set<Item> itemTypes(Map<StackKey, Long> required) {
        Set<Item> result = new HashSet<>();
        for (StackKey key : required.keySet()) result.add(key.item());
        return Set.copyOf(result);
    }

    private static Set<Item> dependencyItemTypes(
            ImmutableRecipeGraph graph) {
        if (graph == null || graph.recipesById().isEmpty()) return Set.of();
        Set<Item> result = new HashSet<>();
        for (ImmutableRecipeGraph.RecipeNode recipe : graph.recipesById().values()) {
            Item output = BuiltInRegistries.ITEM
                    .get(recipe.output().itemId());
            if (output != null && output != Items.AIR) result.add(output);
            for (ImmutableRecipeGraph.IngredientRef input : recipe.inputs()) {
                for (ImmutableRecipeGraph.MaterialRef alternative : input.alternatives()) {
                    Item item = BuiltInRegistries.ITEM
                            .get(alternative.itemId());
                    if (item != null && item != Items.AIR) result.add(item);
                }
            }
        }
        return Set.copyOf(result);
    }

    static boolean sameRelevantInventory(Map<StackKey, Long> previous,
                                         Map<StackKey, Long> current,
                                         Set<Item> itemTypes) {
        if (itemTypes == null || itemTypes.isEmpty()) return false;
        Map<StackKey, Long> expected = new HashMap<>();
        previous.forEach((key, count) -> {
            if (itemTypes.contains(key.item()) && count != null && count > 0) {
                expected.put(key, count);
            }
        });
        Map<StackKey, Long> actual = new HashMap<>();
        current.forEach((key, count) -> {
            if (itemTypes.contains(key.item()) && count != null && count > 0) {
                actual.put(key, count);
            }
        });
        return expected.equals(actual);
    }

    private static void mergeRequirement(Map<StackKey, Long> required,
                                         ItemStack stack, long quantity) {
        if (stack == null || stack.isEmpty() || quantity <= 0L) return;
        String tag = stack.getTag() == null || stack.getTag().isEmpty()
                ? null : stack.getTag().toString();
        required.merge(new StackKey(stack.getItem(), tag), quantity,
                SaturatingLongMath::add);
    }

    private static String networkIdentity(String fingerprint) {
        int separator = fingerprint == null ? -1 : fingerprint.indexOf(':');
        return separator < 0 ? String.valueOf(fingerprint) : fingerprint.substring(0, separator);
    }

    /** Stable fingerprint for a backend-neutral selected network. */
    public static String networkFingerprint(@Nullable StorageReference reference,
                                             Map<StackKey, Long> available) {
        int inventoryHash = 1;
        for (Map.Entry<StackKey, Long> entry : available.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(StackKey::toString))).toList()) {
            inventoryHash = 31 * inventoryHash + entry.getKey().hashCode();
            inventoryHash = 31 * inventoryHash + Long.hashCode(entry.getValue());
        }
        return (reference == null ? "none" : reference.backendId().value() + "@" + reference.networkId())
                + ':' + Integer.toHexString(inventoryHash);
    }

    @Nullable
    private static StorageReference reference(@Nullable CraftStorageEndpoint endpoint) {
        if (endpoint == null) return null;
        try {
            return endpoint.session().reference();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    public static String bindingFingerprint(ServerPlayer player, ResourceKey<Level> dimension,
                                            BlockPos pos) {
        ServerLevel level = player.getServer().getLevel(dimension);
        if (level == null || !level.hasChunkAt(pos)) return "missing";
        var blockEntity = level.getBlockEntity(pos);
        String type = blockEntity == null ? "none" : blockEntity.getType().toString();
        var persisted = AltarBindingRegistry.findBindingEntry(player, dimension.location(), pos);
        long blockedHash = 0x9E3779B97F4A7C15L;
        for (MachineFavoriteKey blocked : MachineFavoritesSavedData.get(player.server)
                .getBlocked(player.getUUID())) {
            blockedHash = blockedHash * 31 + blocked.dimension().hashCode();
            blockedHash = blockedHash * 31 + blocked.pos().asLong();
            blockedHash = blockedHash * 31 + blocked.blockKey().hashCode();
        }
        return dimension.location() + ":" + pos.asLong() + ':' + type + ':'
                + level.getBlockState(pos).getBlock() + ':'
                + (persisted == null ? "none" : persisted.blockKey())
                + ":blocked=" + Long.toHexString(blockedHash);
    }
}
