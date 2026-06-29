package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.RemoteGuiAuth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Validates that a player is authorized to interact with a remote container.
 *
 * <p>Works in tandem with {@link RemoteGuiAuth} and
 * {@code ContainerDistanceMixin}.  Before opening a remote machine GUI,
 * call {@link #validateAndAuthorize} to ensure the player has valid auth
 * and is within the configured distance limit.</p>
 */
public final class ContainerDistanceCheck {

    private ContainerDistanceCheck() {}

    /**
     * Pre-authorize a player for a machine GUI, validate chunk, and validate
     * BlockEntity existence.  Returns the BlockEntity or null (with error
     * message sent to player).
     * <p>
     * May return null even for valid machines that have no BlockEntity
     * (e.g., Smithing Table).  Callers MUST check {@code getBlockEntitySafe}
     * as well as the block state when a null BlockEntity is returned.
     */
    public static net.minecraft.world.level.block.entity.BlockEntity validateAndAuthorize(
            ServerPlayer player, ResourceKey<Level> dim, BlockPos pos) {
        var server = player.getServer();
        if (server == null) return null;

        var level = server.getLevel(dim);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.error.dim_not_loaded"));
            return null;
        }

        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(pos).getBlock());
        RemoteGuiAuth.authorize(player, dim, pos, blockId != null ? blockId.toString() : "?");

        // Silently try to get BE — may be null for blocks without one (e.g., smithing table)
        if (!level.hasChunkAt(pos)) {
            player.sendSystemMessage(Component.translatable("rsi.error.chunk_unloaded"));
            return null;
        }
        return level.getBlockEntity(pos);
    }

    private static boolean isWithinRange(ServerPlayer player, ResourceKey<Level> dim, BlockPos pos) {
        int maxDist = RSIntegrationConfig.MACHINE_GUI_MAX_DISTANCE.get();
        if (maxDist <= 0) return true; // Unlimited

        ResourceKey<Level> playerDim = player.level().dimension();
        if (!playerDim.equals(dim)) return false; // Cross-dimension always blocked when distance limited

        double dx = player.getX() - pos.getX();
        double dy = player.getY() - pos.getY();
        double dz = player.getZ() - pos.getZ();
        return (dx * dx + dy * dy + dz * dz) <= ((double) maxDist * maxDist);
    }
}
