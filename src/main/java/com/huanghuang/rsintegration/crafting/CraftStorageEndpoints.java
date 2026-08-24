package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import com.refinedmods.refinedstorage.api.network.INetwork;

/** Resolves the backend-selected storage endpoint for recursive crafting. */
public final class CraftStorageEndpoints {
    private CraftStorageEndpoints() {}

    /** Transitional bridge for legacy call sites; native RS stays isolated here. */
    public static CraftStorageEndpoint fromLegacyNetwork(@Nonnull INetwork network) {
        return new LegacyRsCraftStorageEndpoint(network);
    }

    /** Transitional insert bridge for non-delegate compatibility services. */
    @Nonnull
    public static ItemStack insertLegacy(@Nonnull INetwork network,
                                         @Nonnull ServerPlayer player,
                                         @Nonnull ItemStack stack,
                                         boolean simulate) {
        return fromLegacyNetwork(network).insert(player, stack, simulate)
                .remainder().orElse(ItemStack.EMPTY);
    }

    /** Transitional exact-extraction bridge for non-crafting compatibility services. */
    @Nonnull
    public static ItemStack extractExactLegacy(@Nonnull INetwork network,
                                               @Nonnull ServerPlayer player,
                                               @Nonnull ItemStack template,
                                               int amount,
                                               boolean simulate) {
        var result = fromLegacyNetwork(network).extractExact(player, template, amount, simulate);
        return result.extractedStacks().stream().reduce(ItemStack.EMPTY, (left, right) -> {
            if (left.isEmpty()) return right.copy();
            ItemStack merged = left.copy();
            if (ItemStack.isSameItemSameTags(merged, right)) merged.grow(right.getCount());
            return merged;
        });
    }

    /**
     * Returns the first backend-default session in registry order. Backend
     * selection remains centralized in StorageBackendRegistry; this helper does
     * not inspect or reference any native storage implementation.
     */
    public static Optional<CraftStorageEndpoint> resolveDefault(@Nonnull ServerPlayer player) {
        List<com.huanghuang.rsintegration.storage.StorageSession> sessions =
                RSIntegrationMod.STORAGE_BACKENDS.registry().resolveDefaultSessionsForPlayer(player);
        if (!sessions.isEmpty()) {
            return Optional.of(new SessionCraftStorageEndpoint(sessions.get(0)));
        }

        // Some backends (notably BD) distinguish a player's primary network
        // from networks the player can access.  A player can therefore have
        // a valid network while no primary network is selected.  Resolve the
        // first authorized discovery descriptor rather than treating that
        // state as "no storage".
        for (var descriptor : RSIntegrationMod.STORAGE_BACKENDS.registry()
                .discoverNetworksForPlayer(player)) {
            Optional<CraftStorageEndpoint> resolved = resolve(descriptor.reference(), player);
            if (resolved.isPresent()) return resolved;
        }
        return Optional.empty();
    }

    /** Resolves a persisted backend-qualified reference without exposing native backend types. */
    public static Optional<CraftStorageEndpoint> resolve(@Nonnull StorageReference reference,
                                                          @Nonnull ServerPlayer player) {
        // A persisted RS coordinate is a locator, not an authorization token.
        // Require the same network to be authenticated by a current terminal,
        // active grid, side-panel listener, or explicit machine binding.
        if ("refinedstorage".equals(reference.backendId().value())) {
            boolean currentAccess = RSIntegrationNetwork.hasCurrentNetworkAccess(player, reference);
            if (!currentAccess) {
                // Packets created by pre-v1 builds may still carry the old
                // raw BlockPos reference. It is not safe to parse that value
                // as a locator, but a currently authenticated RS terminal is
                // an explicit credential and can refresh it to the canonical
                // session reference. Do not use a retained side-panel value
                // or another backend as this recovery source.
                INetwork current = RSIntegrationNetwork.resolveCurrentNetworkFromPlayer(player);
                if (current != null && !reference.networkId().startsWith("v1|")) {
                    CraftStorageEndpoint refreshed = fromLegacyNetwork(current);
                    RSIntegrationMod.LOGGER.info(
                            "[RSI-Storage] refreshed legacy RS reference player={} old={} new={}",
                            player.getGameProfile().getName(), reference.networkId(),
                            refreshed.session().reference().networkId());
                    return Optional.of(refreshed);
                }
                RSIntegrationMod.LOGGER.debug(
                        "[RSI-Storage] rejected RS reference without current access player={} id={}",
                        player.getGameProfile().getName(), reference.networkId());
                return Optional.empty();
            }
        }
        Optional<CraftStorageEndpoint> resolved = RSIntegrationMod.STORAGE_BACKENDS.registry().resolve(reference, player)
                .session()
                .map(SessionCraftStorageEndpoint::new);
        if (resolved.isPresent()) return resolved;

        // A legacy reference may still survive in an old client packet even
        // when the first access check did not run (for example during a
        // protocol upgrade). Refresh that form from the current RS credential
        // only. Canonical v1 references remain strict to preserve selection
        // between multiple RS networks.
        if ("refinedstorage".equals(reference.backendId().value())
                && !reference.networkId().startsWith("v1|")) {
            INetwork current = RSIntegrationNetwork.resolveCurrentNetworkFromPlayer(player);
            if (current != null) {
                CraftStorageEndpoint refreshed = fromLegacyNetwork(current);
                RSIntegrationMod.LOGGER.info(
                        "[RSI-Storage] refreshed unavailable RS reference from current credential player={} old={} new={}",
                        player.getGameProfile().getName(), reference.networkId(),
                        refreshed.session().reference().networkId());
                return Optional.of(refreshed);
            }
        }
        return Optional.empty();
    }
}
