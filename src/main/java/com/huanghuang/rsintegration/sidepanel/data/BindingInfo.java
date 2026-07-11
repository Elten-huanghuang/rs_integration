package com.huanghuang.rsintegration.sidepanel.data;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Client-side snapshot of a bound machine, carried in the side-panel sync packet.
 * Immutable record — created server-side, consumed client-side.
 */
public record BindingInfo(
    String itemKey,          // ItemStack NBT key matching the binding item
    ResourceLocation dim,    // Dimension RegistryKey location (e.g. "minecraft:overworld")
    BlockPos pos,            // Machine block position
    String blockKey,         // Block description ID with optional prefix (e.g. "tacz||block.tacz.workbench_b")
    String displayName,      // Human-readable machine name for UI
    @javax.annotation.Nullable String blockRegKey,  // Block registry key (e.g. "tacz:workbench_b"), nullable for legacy
    @javax.annotation.Nullable ItemStack displayStack  // Full NBT-bearing ItemStack from getCloneItemStack (TACZ etc.)
) {
    public ResourceKey<Level> dimensionKey() {
        return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim);
    }

    /** Serialize to network buffer (called server-side). */
    public static void encode(FriendlyByteBuf buf, BindingInfo info) {
        buf.writeUtf(info.itemKey, 256);
        buf.writeResourceLocation(info.dim);
        buf.writeBlockPos(info.pos);
        buf.writeUtf(info.blockKey, 256);
        buf.writeUtf(info.displayName, 128);
        boolean hasReg = info.blockRegKey != null;
        buf.writeBoolean(hasReg);
        if (hasReg) buf.writeUtf(info.blockRegKey, 128);
        boolean hasDisp = info.displayStack != null && !info.displayStack.isEmpty();
        buf.writeBoolean(hasDisp);
        if (hasDisp) buf.writeItemStack(info.displayStack, false);
    }

    /** Deserialize from network buffer (called client-side). */
    public static BindingInfo decode(FriendlyByteBuf buf) {
        return new BindingInfo(
            buf.readUtf(256),
            buf.readResourceLocation(),
            buf.readBlockPos(),
            buf.readUtf(256),
            buf.readUtf(128),
            buf.readBoolean() ? buf.readUtf(128) : null,
            buf.readBoolean() ? buf.readItem() : null
        );
    }
}
