package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.feature.autoeat.AutoEatFoodBatch;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** External sources for the server-side one-click eating service, since Integration API 1.4. */
public final class RSIAutoEatFoodSources {
    private RSIAutoEatFoodSources() {}

    /** Register during common setup. Duplicate IDs are rejected. */
    public static boolean register(ResourceLocation id, Source source) {
        return AutoEatFoodBatch.register(id, source);
    }

    /** An empty wantedItems set requests all foods. Both callbacks run on the server thread. */
    public record Context(ServerPlayer player, Set<Item> wantedItems) {
        public Context {
            Objects.requireNonNull(player);
            wantedItems = Set.copyOf(wantedItems);
        }
    }

    public interface Source {
        /** Read-only, one exact count-one template per available variant, once per batch. */
        List<ItemStack> candidates(Context context);

        /** Revalidate and charge for one unit. Transfer ownership only through the result. */
        TakeResult takeOne(Context context, ItemStack exactTemplate);
    }

    public sealed interface TakeResult {
        /** A separately owned count-one stack matching the quoted identity. */
        record Taken(ItemStack stack) implements TakeResult {}
        /** No charge and no delivery. RSI can try the next source. */
        record Unavailable() implements TakeResult {}
        /** Charge or delivery uncertain. RSI stops the entire request. */
        record Indeterminate() implements TakeResult {}
    }
}
