package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.util.Reflect;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.gui.IMachineGuiOpener;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkHooks;

/**
 * GUI opener for mod blocks whose {@code openScreen} call needs extra data
 * in the packet buffer beyond just the {@code BlockPos}.
 *
 * <p>Some mods pass additional context in the open-screen packet — a sub-index,
 * a mode flag, a tile-entity reference — that the standard
 * {@code NetworkHooks.openScreen(player, provider, pos)} does not encode.
 * This opener uses reflection to find and invoke a {@code static openScreen}
 * method on the block class, falling back to the standard path.</p>
 *
 * <h3>Reflection strategy</h3>
 * Looks for a method matching:
 * <pre>{@code void openScreen(ServerPlayer player, BlockPos pos)}</pre>
 * on the block class associated with the machine's {@code BlockState}.
 * If not found, falls back to standard {@code NetworkHooks.openScreen}
 * for MenuProvider block entities.
 *
 * <h3>Wiring status</h3>
 * <b>Not wired</b> — {@link BlockGuiRegistry} currently has no registration
 * for this opener.  All supported mod blocks either:
 * <ul>
 *   <li>Use {@code StandardMenuProviderOpener} (the common case — BE implements
 *       {@code MenuProvider}), or</li>
 *   <li>Register a completely custom {@code IMachineGuiOpener} implementation
 *       that handles their specific packet format</li>
 * </ul>
 * The reflection-based "try multiple patterns" design of this class means it
 * is a best-effort fallback, not a targeted solution for a specific mod.
 *
 * <h3>When to wire</h3>
 * When adding a mod where:
 * <ol>
 *   <li>The block has a static {@code openScreen(ServerPlayer, BlockPos)}
 *       helper method</li>
 *   <li>The block entity may or may not implement {@code MenuProvider}</li>
 *   <li>Writing a fully custom opener per block is overkill — the reflection
 *       fallback is acceptable</li>
 * </ol>
 * Wiring step:
 * <pre>{@code
 *   BlockGuiRegistry.register(SomeModBlocks.SOME_MACHINE.get(),
 *       ModSpecificPacketOpener.INSTANCE);
 * }</pre>
 * If the reflection path fails silently (the mod uses a different method
 * signature), a custom opener is still the safer choice.
 */
public enum ModSpecificPacketOpener implements IMachineGuiOpener {
    INSTANCE;

    @Override
    public void openGui(ServerPlayer player, BlockEntity machine, ResourceKey<Level> dim, BlockPos pos) {
        String className = machine.getClass().getName();
        try {
            var blockClass = machine.getBlockState().getBlock().getClass();
            var openMethod = Reflect.findMethod(blockClass, "openScreen",
                    new Class<?>[]{ServerPlayer.class, BlockPos.class});
            if (openMethod != null) {
                openMethod.invoke(machine.getBlockState().getBlock(), player, pos);
                return;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-MachineGUI] No custom openScreen on {}", className, e);
        }

        if (machine instanceof net.minecraft.world.MenuProvider provider) {
            NetworkHooks.openScreen(player, provider, pos);
        } else {
            RSIntegrationMod.LOGGER.warn("[RSI-MachineGUI] Cannot open GUI for {} — not a MenuProvider",
                    className);
        }
    }
}
