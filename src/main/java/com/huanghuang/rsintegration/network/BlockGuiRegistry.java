package com.huanghuang.rsintegration.network;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.machine.StandardMenuProviderOpener;
import com.huanghuang.rsintegration.network.RemoteGuiAuth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry mapping BlockEntityType to GUI opener strategies.
 * Each supported mod registers its opener during mod init.
 */
public final class BlockGuiRegistry {
    private static final Map<BlockEntityType<?>, IMachineGuiOpener> OPENERS = new ConcurrentHashMap<>();

    private BlockGuiRegistry() {}

    /** Register a GUI opener for a specific BlockEntityType. */
    public static void register(BlockEntityType<?> type, IMachineGuiOpener opener) {
        OPENERS.put(type, opener);
        RSIntegrationMod.LOGGER.debug("[RSI-MachineGUI] Registered opener for {}", type);
    }

    /** Check if a BlockEntityType has a registered GUI opener. */
    public static boolean hasGui(BlockEntityType<?> type) {
        return OPENERS.containsKey(type);
    }

    /** Open the GUI for a bound machine.
     * @return true if the GUI was successfully opened
     */
    public static boolean openGui(ServerPlayer player, ResourceKey<Level> dim, BlockPos pos) {
        var server = player.getServer();
        if (server == null) return false;
        var level = server.getLevel(dim);
        if (level == null) return false;
        // Authorize the remote container access (no distance limit)
        RemoteGuiAuth.authorize(player, dim, pos,
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
        if (!level.hasChunkAt(pos)) return false;
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            return openWithBlockEntity(player, be, dim, pos);
        }
        return openWithoutBlockEntity(player, dim, pos);
    }

    private static boolean openWithBlockEntity(ServerPlayer player, BlockEntity be,
                                                ResourceKey<Level> dim, BlockPos pos) {
        var opener = OPENERS.get(be.getType());
        if (opener == null) {
            if (be instanceof MenuProvider) {
                opener = StandardMenuProviderOpener.INSTANCE;
            } else {
                RSIntegrationMod.LOGGER.warn("[RSI-MachineGUI] No opener registered for {} at {}", be.getType(), pos);
                return false;
            }
        }
        RSIntegrationMod.LOGGER.debug("[RSI-MachineGUI] Opening GUI for {} at {} dim={}", be.getType(), pos, dim);
        opener.openGui(player, be, dim, pos);
        return true;
    }

    private static boolean openWithoutBlockEntity(ServerPlayer player, ResourceKey<Level> dim, BlockPos pos) {
        var server = player.getServer();
        if (server == null) return false;
        var level = server.getLevel(dim);
        if (level == null) return false;
        var state = level.getBlockState(pos);
        MenuProvider provider = state.getMenuProvider(level, pos);
        if (provider != null) {
            RSIntegrationMod.LOGGER.debug("[RSI-MachineGUI] Opening GUI via block state for {} at {} dim={}",
                    state.getBlock(), pos, dim);
            net.minecraftforge.network.NetworkHooks.openScreen(player, provider, pos);
            return true;
        }
        RSIntegrationMod.LOGGER.warn("[RSI-MachineGUI] No BlockEntity and no MenuProvider at {} (block={})",
                pos, state.getBlock());
        return false;
    }

    /** Get the number of registered openers (for diagnostics). */
    public static int size() {
        return OPENERS.size();
    }
}
