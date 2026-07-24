package com.huanghuang.rsintegration.mods.apotheosis;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.mods.apotheosis.ApothSpawnerPlanTarget;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.mods.apotheosis.ApothSpawnerModels.Entry;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApothSpawnerStatePacket;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.util.Action;
import dev.shadowsoffire.apotheosis.Apoth;
import dev.shadowsoffire.apotheosis.spawn.modifiers.SpawnerModifier;
import dev.shadowsoffire.apotheosis.spawn.modifiers.StatModifier;
import dev.shadowsoffire.apotheosis.spawn.spawner.ApothSpawnerTile;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ApothSpawnerUpgradeService {
    private static final double MAX_DISTANCE_SQR = 64.0;
    private static final long PREVIEW_TTL_MS = 120_000L;
    private static final Map<UUID, PendingPlan> PENDING = new ConcurrentHashMap<>();

    private ApothSpawnerUpgradeService() {}

    public record Snapshot(ResourceLocation dimension, BlockPos pos, List<Entry> entries, String message) {}
    private record PendingPlan(ResourceLocation dimension, BlockPos pos,
                               Map<ResourceLocation, Integer> selected, long expiresAt) {}

    public static void preview(ServerPlayer player, ResourceLocation dimension, BlockPos pos,
                               Map<ResourceLocation, Integer> selected) {
        Context context = context(player, dimension, pos);
        INetwork network = network(player);
        if (context == null || network == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "rsi.apotheosis.spawner.context_changed"));
            return;
        }
        Map<ResourceLocation, SpawnerModifier> recipes = new HashMap<>();
        for (SpawnerModifier modifier : modifiers(context.level)) recipes.put(modifier.getId(), modifier);
        List<IngredientSpec> specs = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Integer> request : selected.entrySet()) {
            SpawnerModifier modifier = recipes.get(request.getKey());
            if (modifier == null || request.getValue() <= 0) continue;
            Entry state = describe(modifier, context.tile, network);
            if (state == null || !state.supported() || state.complete()) continue;
            int required = Math.min(state.applications(), request.getValue());
            if (required > state.available()) specs.add(new IngredientSpec(modifier.getMainhandInput(), required));
        }
        List<String> missing = new ArrayList<>();
        var graph = specs.isEmpty() ? null : CraftingResolver.resolveGraphForSpecsWithTypes(specs,
                MaterialSources.listAllAvailable(player, network), context.level, player, network,
                missing, null, false);
        PENDING.put(player.getUUID(), new PendingPlan(dimension, pos, Map.copyOf(selected),
                System.currentTimeMillis() + PREVIEW_TTL_MS));
        List<PlanStep> steps = graph == null ? List.of() : graph.nodes().stream().map(node -> {
            ItemStack output = node.syntheticOutput();
            if ((output == null || output.isEmpty()) && !node.outputs().isEmpty()) {
                var declared = node.outputs().get(0);
                output = declared.material().toStack(declared.quantity());
            }
            List<ItemStack> inputs = node.inputs().stream().map(input -> {
                ItemStack display = input.displayHint().copy();
                if (display.isEmpty() && input.ingredient().getItems().length > 0) {
                    display = input.ingredient().getItems()[0].copy();
                }
                display.setCount(input.quantity());
                return display;
            }).toList();
            return new PlanStep(node.recipeId(), output == null ? ItemStack.EMPTY : output,
                    node.executions(), inputs, node.alternativeIds(),
                    com.huanghuang.rsintegration.ModType.byId(node.modTypeId()), 0, false,
                    0, 0, node.alternativeModTypeIds());
        }).toList();
        boolean ok = graph == null || graph.unresolvedDemands().isEmpty();
        ItemStack target = new ItemStack(net.minecraft.world.item.Items.SPAWNER);
        PlanResponse response = new PlanResponse(ok, target.getHoverName().getString(), target,
                steps, Map.of(), missing, ApothSpawnerPlanTarget.ID.toString(), null,
                dimension.toString(), pos.getX(), pos.getY(), pos.getZ(), List.of(), 1,
                null, null, null, 0L, false, false, false, null, Set.of(),
                Map.<IngredientKey, Integer>of(), null, graph == null ? null : PlanGraphView.from(graph));
        com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player), new PlanResponsePacket(response));
    }

    public static Snapshot executePreviewed(ServerPlayer player, ResourceLocation dimension, BlockPos pos) {
        PendingPlan pending = PENDING.remove(player.getUUID());
        if (pending == null || pending.expiresAt() < System.currentTimeMillis()
                || !pending.dimension().equals(dimension) || !pending.pos().equals(pos)) {
            return scan(player, dimension, pos, "rsi.apotheosis.spawner.plan_expired");
        }
        return executeOrQueueRecursive(player, dimension, pos, pending.selected());
    }

    public static Snapshot scan(ServerPlayer player, ResourceLocation dimension, BlockPos pos, String message) {
        Context context = context(player, dimension, pos);
        if (context == null) return new Snapshot(dimension, pos, List.of(), "rsi.apotheosis.spawner.context_changed");
        INetwork network = network(player);
        if (network == null) return new Snapshot(dimension, pos, List.of(), "rsi.apotheosis.spawner.no_network");
        List<Entry> entries = new ArrayList<>();
        for (SpawnerModifier modifier : modifiers(context.level)) {
            Entry entry = describe(modifier, context.tile, network);
            if (entry != null) entries.add(entry);
        }
        entries.sort(Comparator.comparingInt((Entry e) -> order(e.statId()))
                .thenComparing(e -> e.recipeId().toString()));
        return new Snapshot(dimension, pos, entries, message == null ? "" : message);
    }

    public static Snapshot execute(ServerPlayer player, ResourceLocation dimension, BlockPos pos,
                                   Map<ResourceLocation, Integer> selected) {
        return execute(player, dimension, pos, selected, true);
    }

    /** Starts one recursive material chain when selected upgrade inputs are missing. */
    public static Snapshot executeOrQueueRecursive(ServerPlayer player, ResourceLocation dimension,
                                                   BlockPos pos, Map<ResourceLocation, Integer> selected) {
        Context context = context(player, dimension, pos);
        INetwork network = network(player);
        if (context == null || network == null) return execute(player, dimension, pos, selected, false);
        if (AsyncCraftManager.getInstance().getChain(player) != null) {
            return scan(player, dimension, pos, "rsi.apotheosis.spawner.craft_busy");
        }
        Map<ResourceLocation, SpawnerModifier> recipes = new HashMap<>();
        for (SpawnerModifier modifier : modifiers(context.level)) recipes.put(modifier.getId(), modifier);
        List<Map.Entry<ResourceLocation, Integer>> orderedRequests = selected.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString))).toList();
        for (Map.Entry<ResourceLocation, Integer> request : orderedRequests) {
            SpawnerModifier modifier = recipes.get(request.getKey());
            if (modifier == null || request.getValue() <= 0) continue;
            Entry state = describe(modifier, context.tile, network);
            if (state == null || !state.supported() || state.complete()) continue;
            int required = Math.min(state.applications(), request.getValue());
            if (state.available() >= required) continue;

            List<String> missing = new ArrayList<>();
            var graph = CraftingResolver.resolveGraphForSpecsWithTypes(
                    List.of(new IngredientSpec(modifier.getMainhandInput(), required)),
                    MaterialSources.listAllAvailable(player, network), context.level, player, network,
                    missing, null, false);
            // An unresolved root is isolated to this upgrade. Continue scanning
            // so another selected upgrade can still recurse successfully.
            if (!graph.unresolvedDemands().isEmpty() || graph.nodes().isEmpty()) continue;
            AsyncCraftChain chain = new AsyncCraftChain(player.getUUID(), player.getServer(), network, graph);
            AsyncCraftManager.getInstance().submit(chain);
            chain.onDone(() -> {
                if (chain.state() == AsyncCraftChain.State.COMPLETED) {
                    // Re-plan from fresh inventory. This avoids double-counting
                    // shared raw materials across independently resolvable roots.
                    Snapshot finished = executeOrQueueRecursive(player, dimension, pos, selected);
                    if (player.connection != null) {
                        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                                ApothSpawnerStatePacket.from(finished));
                    }
                }
            });
            return scan(player, dimension, pos, "rsi.apotheosis.spawner.recursive_started");
        }
        // No further resolvable missing root remains. Apply every selected
        // upgrade whose material is now available; unresolved ones are skipped.
        return execute(player, dimension, pos, selected, false);
    }

    private static Snapshot execute(ServerPlayer player, ResourceLocation dimension, BlockPos pos,
                                    Map<ResourceLocation, Integer> selected, boolean allowRecursive) {
        Context context = context(player, dimension, pos);
        if (context == null) return new Snapshot(dimension, pos, List.of(), "rsi.apotheosis.spawner.context_changed");
        INetwork network = network(player);
        if (network == null) return new Snapshot(dimension, pos, List.of(), "rsi.apotheosis.spawner.no_network");

        int completed = 0;
        int skipped = 0;
        Map<ResourceLocation, SpawnerModifier> recipes = new HashMap<>();
        for (SpawnerModifier modifier : modifiers(context.level)) recipes.put(modifier.getId(), modifier);
        List<ResourceLocation> ordered = selected.keySet().stream()
                .sorted(Comparator.comparing(ResourceLocation::toString)).toList();
        for (ResourceLocation id : ordered) {
            SpawnerModifier modifier = recipes.get(id);
            if (modifier == null || !isPositive(modifier)) { skipped++; continue; }
            Entry state = describe(modifier, context.tile, network);
            if (state == null || state.complete() || !state.supported()) { skipped++; continue; }
            int requested = Math.min(state.applications(), Math.max(0, selected.getOrDefault(id, 0)));
            for (int i = 0; i < requested; i++) {
                Entry fresh = describe(modifier, context.tile, network);
                if (fresh == null || fresh.complete()) break;
                try (ExtractionLedger ledger = new ExtractionLedger()) {
                    ItemStack reserved = ledger.reserveFromNetwork(modifier.getMainhandInput(), 1, network);
                    if (reserved.isEmpty() || !ledger.commit(network, player)) { skipped++; break; }
                    if (!modifier.apply(context.tile)) {
                        ItemStack remaining = network.insertItem(reserved.copy(), reserved.getCount(), Action.PERFORM);
                        if (!remaining.isEmpty()) player.drop(remaining, false);
                        skipped++;
                        break;
                    }
                    completed++;
                }
            }
        }
        context.tile.setChanged();
        context.level.sendBlockUpdated(pos, context.level.getBlockState(pos), context.level.getBlockState(pos), 3);
        String message = completed > 0 ? "rsi.apotheosis.spawner.upgrades_applied" : "rsi.apotheosis.spawner.nothing_done";
        if (skipped > 0) message += ":" + skipped;
        return scan(player, dimension, pos, message);
    }

    private static List<SpawnerModifier> modifiers(ServerLevel level) {
        return level.getRecipeManager().getAllRecipesFor(Apoth.RecipeTypes.MODIFIER).stream()
                .filter(ApothSpawnerUpgradeService::isPositive).toList();
    }

    private static boolean isPositive(SpawnerModifier modifier) {
        return modifier.getOffhandInput().isEmpty() && !modifier.getStatModifiers().isEmpty();
    }

    @Nullable
    private static Entry describe(SpawnerModifier modifier, ApothSpawnerTile tile, INetwork network) {
        if (modifier.getStatModifiers().size() != 1) return unsupported(modifier, network);
        StatModifier<?> change = modifier.getStatModifiers().get(0);
        String statId = change.stat().getId();
        Object current = change.stat().getValue(tile);
        int applications;
        int currentValue;
        int targetValue;
        boolean supported = true;
        if (current instanceof Boolean value && change.value() instanceof Boolean target) {
            currentValue = value ? 1 : 0;
            targetValue = target ? 1 : 0;
            applications = value == target ? 0 : 1;
            supported = target;
        } else if (current instanceof Number now && change.value() instanceof Number delta) {
            int value = now.intValue();
            int step = delta.intValue();
            int min = change.min() instanceof Number n ? n.intValue() : -1;
            int max = change.max() instanceof Number n ? n.intValue() : -1;
            currentValue = value;
            targetValue = step < 0 ? min : max;
            if (targetValue < 0 || step == 0) {
                applications = 0;
                supported = false;
            } else {
                applications = Math.max(0, (Math.abs(value - targetValue) + Math.abs(step) - 1) / Math.abs(step));
            }
        } else {
            return unsupported(modifier, network);
        }
        ItemStack material = bestMaterial(modifier.getMainhandInput(), network);
        int available = countAvailable(modifier.getMainhandInput(), network);
        return new Entry(modifier.getId(), statId, material, currentValue, targetValue,
                applications, available, applications == 0 && supported, supported);
    }

    private static Entry unsupported(SpawnerModifier modifier, INetwork network) {
        String stat = modifier.getStatModifiers().isEmpty() ? "unknown" : modifier.getStatModifiers().get(0).stat().getId();
        return new Entry(modifier.getId(), stat, bestMaterial(modifier.getMainhandInput(), network),
                0, 0, 0, countAvailable(modifier.getMainhandInput(), network), false, false);
    }

    private static ItemStack bestMaterial(Ingredient ingredient, INetwork network) {
        ItemStack best = ItemStack.EMPTY;
        int bestCount = -1;
        for (ItemStack candidate : ingredient.getItems()) {
            int count = countExact(candidate, network);
            if (count > bestCount) { best = candidate.copyWithCount(1); bestCount = count; }
        }
        return best;
    }

    private static int countAvailable(Ingredient ingredient, INetwork network) {
        int total = 0;
        for (var entry : network.getItemStorageCache().getList().getStacks()) {
            ItemStack stack = entry.getStack();
            if (ingredient.test(stack)) total += stack.getCount();
        }
        return total;
    }

    private static int countExact(ItemStack template, INetwork network) {
        int total = 0;
        for (var entry : network.getItemStorageCache().getList().getStacks()) {
            ItemStack stack = entry.getStack();
            if (ItemStack.isSameItemSameTags(stack, template)) total += stack.getCount();
        }
        return total;
    }

    @Nullable
    private static INetwork network(ServerPlayer player) {
        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network == null) return null;
        var security = network.getSecurityManager();
        return security == null || security.hasPermission(Permission.EXTRACT, player) ? network : null;
    }

    @Nullable
    private static Context context(ServerPlayer player, ResourceLocation dimension, BlockPos pos) {
        ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension);
        ServerLevel level = player.server.getLevel(key);
        if (level == null || !level.hasChunkAt(pos) || player.level() != level
                || player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > MAX_DISTANCE_SQR) return null;
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof ApothSpawnerTile tile ? new Context(level, tile) : null;
    }

    private static int order(String id) {
        return switch (id) {
            case "min_delay" -> 0; case "max_delay" -> 1; case "spawn_count" -> 2;
            case "max_nearby_entities" -> 3; case "req_player_range" -> 4; case "spawn_range" -> 5;
            case "ignore_players" -> 6; case "ignore_conditions" -> 7; case "ignore_light" -> 8;
            case "redstone_control" -> 9; case "no_ai" -> 10; case "silent" -> 11; case "baby" -> 12;
            default -> 100;
        };
    }

    private record Context(ServerLevel level, ApothSpawnerTile tile) {}
}
