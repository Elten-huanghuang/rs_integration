package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.network.IMachineGuiOpener;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkHooks;

/**
 * GUI opener for blocks that do NOT implement {@link MenuProvider} but still
 * have a client-side accessible GUI via custom network packets.
 *
 * <p>Some mod blocks (e.g. puzzle blocks, item-display blocks) open their
 * screen via a mod-specific packet that doesn't rely on the vanilla
 * {@code AbstractContainerMenu} chain.  Those blocks cannot use
 * {@link StandardMenuProviderOpener} because they don't implement
 * {@code MenuProvider}.</p>
 *
 * <h3>Wiring status</h3>
 * <b>Not wired</b> — {@link BlockGuiRegistry} currently has no registration
 * for this opener.  All supported mods either:
 * <ul>
 *   <li>Implement {@code MenuProvider} on their block entities (handled by
 *       {@code StandardMenuProviderOpener}), or</li>
 *   <li>Register a mod-specific opener via
 *       {@code BlockGuiRegistry.register(type, customOpener)} during mod init</li>
 * </ul>
 *
 * <h3>When to wire</h3>
 * When adding support for a mod whose machine block:
 * <ol>
 *   <li>Does NOT implement {@code MenuProvider}</li>
 *   <li>Opens its GUI purely on the client side (the packet handler is
 *       registered by the mod, not by RSI)</li>
 *   <li>Has no need for extra data in the open-screen packet beyond
 *       {@code BlockPos} (otherwise use {@link ModSpecificPacketOpener})</li>
 * </ol>
 * Wiring step:
 * <pre>{@code
 *   BlockGuiRegistry.register(SomeModBlocks.SOME_BLOCK_ENTITY.get(),
 *       ClientOnlyOpener.INSTANCE);
 * }</pre>
 * The {@link com.huanghuang.rsintegration.network.RemoteGuiAuth} guard
 * (already set by {@code BlockGuiRegistry.openGui} before calling this
 * opener) ensures the container distance check passes.
 */
public enum ClientOnlyOpener implements IMachineGuiOpener {
    INSTANCE;

    @Override
    public void openGui(ServerPlayer player, BlockEntity machine, ResourceKey<Level> dim, BlockPos pos) {
        if (machine instanceof MenuProvider provider) {
            NetworkHooks.openScreen(player, provider, pos);
        }
    }
}
