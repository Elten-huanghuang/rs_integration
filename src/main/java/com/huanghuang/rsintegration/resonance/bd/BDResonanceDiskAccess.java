package com.huanghuang.rsintegration.resonance.bd;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.storage.StorageResolutionResult;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.bd.BeyondDimensionsReflection;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.UUID;

/** Resolves the single active BD resonance disk carried by a player. */
public final class BDResonanceDiskAccess {
    public static final String DISK_TAG = "BDResonance";
    public static final String UUID_TAG = "DiskUUID";
    public static final String NET_ID_TAG = "NetId";
    public static final String SCHEMA_TAG = "Schema";
    public static final int SCHEMA = 1;

    private BDResonanceDiskAccess() {}

    @Nullable
    public static BDResonanceDiskView resolve(ServerPlayer player) {
        if (ModItems.DIMENSIONAL_RESONANCE_DISK == null) return null;
        ItemStack stack = findDiskStack(player);
        if (stack.isEmpty()) return null;
        if (!isBound(stack)) return null;
        UUID diskId = getDiskId(stack);
        int networkId = getNetworkId(stack);
        int rebound = rebindAfterMergedNetwork(player, stack, diskId, networkId);
        if (rebound >= 0) {
            networkId = rebound;
            UUID reboundDiskId = getDiskId(stack);
            if (reboundDiskId != null) diskId = reboundDiskId;
        }
        if (!BeyondDimensionsReflection.isAuthorizedNetwork(player, networkId)) return null;
        BDResonanceDiskData data = BDResonanceDiskData.get(player.server);
        data.getOrCreate(diskId);
        return new BDResonanceDiskView(player, diskId, data, networkId);
    }

    public static ItemStack findDiskStack(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) if (isDisk(stack)) return stack;
        for (ItemStack stack : player.getInventory().offhand) if (isDisk(stack)) return stack;
        for (ItemStack stack : player.getInventory().armor) if (isDisk(stack)) return stack;
        for (ItemStack stack : com.huanghuang.rsintegration.util.CuriosAccess.stacks(player)) {
            if (isDisk(stack)) return stack;
        }
        return ItemStack.EMPTY;
    }

    public static boolean isDisk(ItemStack stack) {
        return !stack.isEmpty() && ModItems.DIMENSIONAL_RESONANCE_DISK != null
                && stack.is(ModItems.DIMENSIONAL_RESONANCE_DISK.get());
    }

    @Nullable
    public static UUID getDiskId(ItemStack stack) {
        return isDisk(stack) ? getTaggedDiskId(stack) : null;
    }

    public static int getNetworkId(ItemStack stack) {
        return isDisk(stack) ? getTaggedNetworkId(stack) : -1;
    }

    public static boolean isBound(ItemStack stack) {
        return isDisk(stack) && hasCompleteBindingTags(stack);
    }

    @Nullable
    static UUID getTaggedDiskId(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag()) return null;
        var root = stack.getTag();
        if (!root.contains(DISK_TAG, Tag.TAG_COMPOUND)) return null;
        var disk = root.getCompound(DISK_TAG);
        return disk.hasUUID(UUID_TAG) ? disk.getUUID(UUID_TAG) : null;
    }

    static int getTaggedNetworkId(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag()) return -1;
        var root = stack.getTag();
        if (!root.contains(DISK_TAG, Tag.TAG_COMPOUND)) return -1;
        var disk = root.getCompound(DISK_TAG);
        return disk.contains(NET_ID_TAG, Tag.TAG_INT) ? disk.getInt(NET_ID_TAG) : -1;
    }

    static boolean hasCompleteBindingTags(ItemStack stack) {
        return getTaggedDiskId(stack) != null && getTaggedNetworkId(stack) >= 0;
    }

    public static void bind(ItemStack stack, UUID diskId, int networkId) {
        var tag = stack.getOrCreateTag().getCompound(DISK_TAG);
        tag.putUUID(UUID_TAG, diskId);
        tag.putInt(NET_ID_TAG, networkId);
        tag.putInt(SCHEMA_TAG, SCHEMA);
        stack.getOrCreateTag().put(DISK_TAG, tag);
    }

    public static int resolvePrimaryNetworkId(ServerPlayer player) {
        StorageResolutionResult result = BeyondDimensionsReflection.resolvePrimary(
                player, new StorageBackendId("beyonddimensions"));
        if (!result.resolved() || result.session().isEmpty()) return -1;
        try {
            return Integer.parseInt(result.session().orElseThrow().reference().networkId());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /**
     * BD merge destroys the absorbed id. The player membership is moved to the
     * surviving target network, so lazily follow that target on next access.
     * Existing-but-unauthorized networks are deliberately never rebound.
     */
    public static int rebindAfterMergedNetwork(ServerPlayer player, ItemStack stack,
                                                UUID diskId, int networkId) {
        if (BeyondDimensionsReflection.networkExists(networkId)) return networkId;
        int replacement = resolvePrimaryNetworkId(player);
        if (replacement < 0 || replacement == networkId
                || !BeyondDimensionsReflection.isAuthorizedNetwork(player, replacement)) return -1;
        return bindToNetwork(player, stack, diskId, replacement) ? replacement : -1;
    }

    /** Binds a disk and coalesces it with the network's existing resonance disk. */
    public static boolean bindToNetwork(ServerPlayer player, ItemStack stack,
                                        UUID diskId, int networkId) {
        BDResonanceDiskData data = BDResonanceDiskData.get(player.server);
        data.bind(diskId, player.getUUID(), networkId);
        // The item NBT is the portable source of truth. This must happen even
        // when there is no pre-existing disk to trigger the coalescing branch.
        bind(stack, diskId, networkId);
        coalesceWithNetwork(player, stack, diskId, networkId);
        player.getInventory().setChanged();

        UUID boundDiskId = getDiskId(stack);
        BDResonanceDiskData.DiskRecord record = boundDiskId == null
                ? null : data.find(boundDiskId);
        return isBound(stack) && getNetworkId(stack) == networkId
                && record != null && record.boundNetId() == networkId;
    }

    /** Returns true when the item was redirected into an existing disk. */
    public static boolean coalesceWithNetwork(ServerPlayer player, ItemStack stack,
                                              UUID diskId, int networkId) {
        BDResonanceDiskData data = BDResonanceDiskData.get(player.server);
        UUID existing = data.findDiskIdByNetwork(networkId, diskId);
        if (existing == null) return false;
        if (data.mergeInto(diskId, existing)) {
            bind(stack, existing, networkId);
            return true;
        }
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                "rsi.resonance.bd.merge_full"));
        return false;
    }
}
