package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.crafting.RegisteredMaterialSources;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** External material sources for native recursive crafting, shared by both storage backends. */
public final class RSICraftingMaterialSources {
    private RSICraftingMaterialSources() {}

    /** Register during common setup. Native stock is used first, then extensions by source ID. */
    public static boolean register(ResourceLocation id, RSIPlanExtensions.Source toggle, Source source) {
        return RegisteredMaterialSources.register(id, toggle, source);
    }

    public interface Source {
        /** Read player state on the server thread and return detached stock and spending limits. */
        Snapshot snapshot(Context context);

        /**
         * Claim the exact quantity on the server thread. An empty result means insufficient stock.
         * Only hold capacity here. Earlier allocations are from this same source and reservation batch.
         */
        Optional<Reservation> reserve(Context context, Allocation requested, List<Allocation> earlier);
    }

    public enum Phase { PLAN, REVALIDATE, TASK }
    public record Plan(ResourceLocation recipeId, long generation) {}

    /** An empty itemTypes set requests all variants. Network is present if one was selected. */
    public record Context(ServerPlayer player, Optional<RSIPlanExtensions.Network> network,
                          Set<RSIPlanExtensions.Source> enabledSources, Set<Item> itemTypes,
                          Phase phase, Optional<Plan> plan, Optional<UUID> taskId) {
        public Context {
            Objects.requireNonNull(player);
            Objects.requireNonNull(network);
            enabledSources = Set.copyOf(enabledSources);
            itemTypes = Set.copyOf(itemTypes);
            Objects.requireNonNull(phase);
            Objects.requireNonNull(plan);
            Objects.requireNonNull(taskId);
        }
    }

    /** Pool IDs are local to a source. Multiple variants can spend the same pool. */
    public record Snapshot(Map<ResourceLocation, BigInteger> balances, List<Offer> offers) {
        public static final Snapshot EMPTY = new Snapshot(Map.of(), List.of());

        public Snapshot {
            balances = Map.copyOf(balances);
            offers = List.copyOf(offers);
            if (balances.values().stream().anyMatch(value -> value.signum() < 0)) {
                throw new IllegalArgumentException("negative material balance");
            }
            for (Offer offer : offers) {
                if (offer.unitCost().signum() > 0 && !balances.containsKey(offer.pool())) {
                    throw new IllegalArgumentException("unknown material pool " + offer.pool());
                }
            }
        }
    }

    /** A free offer uses just its per-variant limit. Paid offers also spend their pool balance. */
    public record Offer(ItemStack target, long limit, ResourceLocation pool, BigInteger unitCost) {
        public Offer {
            target = Objects.requireNonNull(target).copyWithCount(1);
            Objects.requireNonNull(pool);
            Objects.requireNonNull(unitCost);
            if (target.isEmpty() || limit <= 0 || unitCost.signum() < 0) {
                throw new IllegalArgumentException("invalid material offer");
            }
        }

        @Override
        public ItemStack target() { return target.copy(); }
    }

    public record Allocation(ItemStack target, long count) {
        public Allocation {
            target = Objects.requireNonNull(target).copyWithCount(1);
            if (target.isEmpty() || count <= 0) throw new IllegalArgumentException("invalid material allocation");
        }

        @Override
        public ItemStack target() { return target.copy(); }
    }

    public interface Reservation {
        /** Revalidate the remaining claim on the server thread without taking any materials. */
        boolean validate();

        /** Revalidate and take materials. Return the actual fragments, even on partial extraction. */
        Extraction commit();

        /** Release the unused claim. Called once even after commit. Any returned items remain paid. */
        void release();
    }

    /** Uncertain extraction stops automatic retries. Include any fragments whose extraction is confirmed. */
    public record Extraction(List<ItemStack> items, boolean uncertain) {
        public Extraction {
            items = items.stream().map(ItemStack::copy).toList();
        }

        @Override
        public List<ItemStack> items() { return items.stream().map(ItemStack::copy).toList(); }
    }
}
