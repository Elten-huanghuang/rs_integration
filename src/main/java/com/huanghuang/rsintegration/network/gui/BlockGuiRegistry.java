package com.huanghuang.rsintegration.network.gui;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.machine.StandardMenuProviderOpener;
import com.huanghuang.rsintegration.network.gui.RemoteGuiAuth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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
        if (level == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-MachineGUI] openGui failed: dimension not found {}", dim);
            return false;
        }
        // Try to force-load the chunk first — getBlockState needs the chunk
        if (!level.hasChunkAt(pos)) {
            com.huanghuang.rsintegration.util.ChunkUtils.loadChunk(level, pos);
            if (!level.hasChunkAt(pos)) {
                RSIntegrationMod.LOGGER.warn("[RSI-MachineGUI] openGui failed: chunk not loaded at {} dim={}", pos, dim);
                return false;
            }
        }
        var blockState = level.getBlockState(pos);
        String blockId = ForgeRegistries.BLOCKS.getKey(blockState.getBlock()).toString();
        RSIntegrationMod.LOGGER.debug("[RSI-MachineGUI] openGui: block={} at {} dim={}", blockId, pos, dim);
        // Authorize the remote container access (no distance limit)
        RemoteGuiAuth.authorize(player, dim, pos, blockId);
        boolean success = false;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            // No BlockEntity (smithing table, market, etc.):
            // prefer state.getMenuProvider() which is the vanilla/Forge-approved
            // way to open these.  Block.use() is tried as a fallback for blocks
            // that wire their GUI through the interaction handler (e.g. Eidolon
            // worktable) without exposing a MenuProvider on the block state.
            if (openWithoutBlockEntity(player, dim, pos)) {
                success = true;
            } else if (openViaBlockUse(player, level, pos)) {
                success = true;
            }
        } else {
            // Has BlockEntity (furnace, TACZ, CrockPot, etc.):
            // prefer Block.use() which replicates the exact player interaction
            // (including extra data writers like TACZ's gun-id buffer).
            // Fall back to direct MenuProvider if the block doesn't respond.
            if (openViaBlockUse(player, level, pos)) {
                success = true;
            } else if (openWithBlockEntity(player, be, dim, pos)) {
                success = true;
            }
        }
        if (!success) {
            RSIntegrationMod.LOGGER.warn("[RSI-MachineGUI] openGui failed for {} at {}: be={} beType={} isMenuProvider={}",
                    blockId, pos, be != null ? "present" : "null",
                    be != null ? be.getType().toString() : "N/A",
                    be instanceof MenuProvider);
            RemoteGuiAuth.deauthorize(player.getUUID(), null);
        }
        return success;
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
            player.openMenu(provider);
            return true;
        }
        RSIntegrationMod.LOGGER.warn("[RSI-MachineGUI] No BlockEntity and no MenuProvider at {} (block={})",
                pos, state.getBlock());
        return false;
    }

    /** Final fallback: simulate a right-click to trigger Block.use() GUI opening.
     *  Some mods (e.g. Avaritia) open their GUI through the block's use handler
     *  rather than providing a MenuProvider on the BE or block state. */
    private static boolean openViaBlockUse(ServerPlayer player, Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        // Hit the top face (Y+1.0) rather than block center (Y+0.5).
        // Some mods (e.g. FarmingForBlockheads Market) validate the hit
        // position against the face and reject interior hits as invalid.
        BlockHitResult hit = new BlockHitResult(
                new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5),
                Direction.UP, pos, false);
        // Temporarily empty the main hand so Block.use() doesn't consume the
        // held item (e.g. CrockPot inserting food into the pot on right-click).
        ItemStack saved = player.getMainHandItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        InteractionResult result;
        try {
            result = state.use(level, player, InteractionHand.MAIN_HAND, hit);
        } finally {
            player.setItemInHand(InteractionHand.MAIN_HAND, saved);
        }
        if (result.consumesAction()) {
            RSIntegrationMod.LOGGER.debug("[RSI-MachineGUI] Opened via Block.use() for {} at {}",
                    state.getBlock(), pos);
            return true;
        }
        RSIntegrationMod.LOGGER.warn("[RSI-MachineGUI] Block.use() returned {} for {} at {}",
                result, state.getBlock(), pos);
        return false;
    }

    /** Get the number of registered openers (for diagnostics). */
    public static int size() {
        return OPENERS.size();
    }
}
