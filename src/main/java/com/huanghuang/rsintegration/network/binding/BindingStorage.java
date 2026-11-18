package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class BindingStorage {

    public static final String KEY_BINDINGS = "aec_bindings";
    public static final int MAX_CUSTOM_NAME_LENGTH = 64;

    private BindingStorage() {}

    /** 自定义名称只接受单行纯文本；空名称表示恢复机器原名。 */
    public static String normalizeCustomName(@Nullable String name) {
        if (name == null) return "";
        String normalized = SharedConstants.filterText(name).strip();
        int end = Math.min(normalized.length(), MAX_CUSTOM_NAME_LENGTH);
        if (end < normalized.length() && end > 0
                && Character.isHighSurrogate(normalized.charAt(end - 1))) end--;
        return normalized.substring(0, end).strip();
    }

    public record BindingEntry(ResourceLocation dim, BlockPos pos, String blockKey,
                                @Nullable String blockRegKey,
                                @Nullable ItemStack displayStack,
                                String customName) {
        public BindingEntry {
            customName = normalizeCustomName(customName);
        }

        public BindingEntry(ResourceLocation dim, BlockPos pos, String blockKey,
                            @Nullable String blockRegKey, @Nullable ItemStack displayStack) {
            this(dim, pos, blockKey, blockRegKey, displayStack, "");
        }

        public BindingEntry copy() {
            return new BindingEntry(dim, pos, blockKey, blockRegKey,
                    displayStack == null ? null : displayStack.copy(), customName);
        }

        public BindingEntry withCustomName(String name) {
            return new BindingEntry(dim, pos, blockKey, blockRegKey, displayStack, name);
        }

        /** 普通方块图标可由 blockRegKey 重建，无需把完整 ItemStack NBT 存进索引和同步包。 */
        public BindingEntry compactDisplayStack() {
            if (displayStack == null || displayStack.isEmpty()) {
                return displayStack == null ? this : new BindingEntry(
                        dim, pos, blockKey, blockRegKey, null, customName);
            }
            CompoundTag displayTag = displayStack.getTag();
            if (blockRegKey == null || (displayTag != null && !displayTag.isEmpty())
                    || displayStack.getDamageValue() != 0) {
                return this;
            }
            ResourceLocation blockId = ResourceLocation.tryParse(blockRegKey);
            Block block = blockId == null ? null : ForgeRegistries.BLOCKS.getValue(blockId);
            if (block == null || block == Blocks.AIR || !displayStack.is(block.asItem())) {
                return this;
            }
            return new BindingEntry(dim, pos, blockKey, blockRegKey, null, customName);
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("dim", dim.toString());
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            tag.putString("block", blockKey);
            if (!customName.isEmpty()) tag.putString("customName", customName);
            if (blockRegKey != null) tag.putString("reg", blockRegKey);
            if (displayStack != null && !displayStack.isEmpty()) {
                tag.put("disp", displayStack.save(new CompoundTag()));
            }
            return tag;
        }

        public static BindingEntry fromTag(CompoundTag tag) {
            String dimStr = tag.getString("dim");
            ResourceLocation dim = ResourceLocation.tryParse(dimStr);
            if (dim == null) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI] BindingStorage: corrupt dimension string '{}' — binding entry skipped", dimStr);
                return null;
            }
            BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            String blockKey = tag.getString("block");
            String regKey = null;
            if (tag.contains("reg")) {
                String raw = tag.getString("reg");
                if (ResourceLocation.tryParse(raw) != null) {
                    regKey = raw;
                } else {
                    RSIntegrationMod.LOGGER.warn(
                            "[RSI] BindingStorage: invalid reg key '{}' — treating as null", raw);
                }
            }
            ItemStack dispStack = null;
            if (tag.contains("disp", Tag.TAG_COMPOUND)) {
                dispStack = ItemStack.of(tag.getCompound("disp"));
            }
            return new BindingEntry(dim, pos, blockKey, regKey, dispStack,
                    tag.getString("customName"));
        }
    }

    public static List<BindingEntry> getBindings(MinecraftServer server, ItemStack stack) {
        return MachineBindingService.getBindings(server, stack);
    }

    /** 仅供无服务端上下文的兼容路径和客户端首次同步前回退使用。 */
    public static List<BindingEntry> readLegacyBindings(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return Collections.emptyList();
        }
        if (tag.contains(KEY_BINDINGS, Tag.TAG_LIST)) {
            ListTag list = tag.getList(KEY_BINDINGS, Tag.TAG_COMPOUND);
            List<BindingEntry> entries = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                BindingEntry entry = BindingEntry.fromTag(list.getCompound(i));
                if (entry != null) entries.add(entry);
            }
            return Collections.unmodifiableList(entries);
        }
        // Read-only legacy fallback: parse old format without mutating the tag.
        // Migration to the new format happens on the next addBinding/removeBinding call.
        if (tag.contains("aec_bound_x")) {
            BindingEntry entry = readLegacyEntry(tag);
            if (entry != null) {
                return Collections.singletonList(entry);
            }
        }
        return Collections.emptyList();
    }

    /** Parse a legacy-format binding entry without modifying the tag.
     *  Legacy format: aec_bound_dim, aec_bound_x/y/z, aec_bound_block
     *  as top-level keys in the CompoundTag. */
    private static BindingEntry readLegacyEntry(CompoundTag tag) {
        String dimStr = tag.getString("aec_bound_dim");
        ResourceLocation dim = ResourceLocation.tryParse(dimStr);
        if (dim == null) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI] BindingStorage: corrupt legacy dimension '{}' — binding skipped", dimStr);
            return null;
        }
        BlockPos pos = new BlockPos(
                tag.getInt("aec_bound_x"),
                tag.getInt("aec_bound_y"),
                tag.getInt("aec_bound_z"));
        String blockKey = tag.getString("aec_bound_block");
        return new BindingEntry(dim, pos, blockKey, null, null);
    }

    public static boolean addBinding(MinecraftServer server, ItemStack stack, ResourceLocation dim, BlockPos pos,
                                     String blockKey, @Nullable String blockRegKey,
                                     @Nullable ItemStack displayStack, @Nullable UUID ownerId) {
        return MachineBindingService.addBinding(server, stack, dim, pos, blockKey, blockRegKey, displayStack, ownerId);
    }

    public static boolean removeBinding(MinecraftServer server, ItemStack stack, ResourceLocation dim, BlockPos pos) {
        return MachineBindingService.removeBinding(server, stack, dim, pos);
    }

    public static boolean replaceBindingBlockKey(MinecraftServer server, ItemStack stack, ResourceLocation dim,
                                                 BlockPos pos, String expectedBlockKey, String replacementBlockKey) {
        return MachineBindingService.replaceBindingBlockKey(server, stack, dim, pos, expectedBlockKey, replacementBlockKey);
    }

    public static String replaceBlockKeyPrefix(String blockKey, String replacementPrefix) {
        int separator = blockKey == null ? -1 : blockKey.indexOf("||");
        return separator < 0 ? blockKey : replacementPrefix + blockKey.substring(separator);
    }

    public static boolean hasBinding(MinecraftServer server, ItemStack stack, ResourceLocation dim, BlockPos pos) {
        return getBindings(server, stack).stream().anyMatch(e -> e.dim().equals(dim) && e.pos().equals(pos));
    }
}
