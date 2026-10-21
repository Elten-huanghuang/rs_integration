package com.huanghuang.rsintegration.network.binding;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import javax.annotation.Nullable;
import net.minecraft.core.registries.Registries;

import java.util.UUID;

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
    @Nullable String blockRegKey,  // Block registry key (e.g. "tacz:workbench_b"), nullable for legacy
    @Nullable ItemStack displayStack, // Full NBT-bearing ItemStack from getCloneItemStack (TACZ etc.)
    @Nullable UUID bindingBookId,
    @Nullable MachineEndpointId endpointId
) {
    public BindingInfo(String itemKey, ResourceLocation dim, BlockPos pos,
                       String blockKey, String displayName,
                       @Nullable String blockRegKey, @Nullable ItemStack displayStack) {
        this(itemKey, dim, pos, blockKey, displayName, blockRegKey, displayStack, null, null);
    }

    public ResourceKey<Level> dimensionKey() {
        return ResourceKey.create(Registries.DIMENSION, dim);
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
        writeUuid(buf, info.bindingBookId);
        boolean hasEndpoint = info.endpointId != null;
        buf.writeBoolean(hasEndpoint);
        if (hasEndpoint) info.endpointId.encode(buf);
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
            buf.readBoolean() ? buf.readItem() : null,
            readUuid(buf),
            buf.readBoolean() ? MachineEndpointId.decode(buf) : null
        );
    }

    @Nullable
    public BindingInfo withBookIds(UUID bookId, @Nullable MachineEndpointId endpointId) {
        return new BindingInfo(itemKey, dim, pos, blockKey, displayName,
                blockRegKey, displayStack == null ? null : displayStack.copy(),
                bookId, endpointId);
    }

    private static void writeUuid(FriendlyByteBuf buf, @Nullable UUID id) {
        buf.writeBoolean(id != null);
        if (id != null) buf.writeUUID(id);
    }

    @Nullable
    private static UUID readUuid(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUUID() : null;
    }
}
