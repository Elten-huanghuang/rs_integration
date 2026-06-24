package com.huanghuang.rsintegration.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BindingStorage {

    public static final String KEY_BINDINGS = "aec_bindings";

    private BindingStorage() {}

    public record BindingEntry(ResourceLocation dim, BlockPos pos, String blockKey) {
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("dim", dim.toString());
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            tag.putString("block", blockKey);
            return tag;
        }

        public static BindingEntry fromTag(CompoundTag tag) {
            String dimStr = tag.getString("dim");
            ResourceLocation dim = ResourceLocation.tryParse(dimStr);
            if (dim == null) {
                com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.warn(
                        "[RSI] BindingStorage: corrupt dimension string '{}' — binding entry skipped", dimStr);
                return null;
            }
            BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            String blockKey = tag.getString("block");
            return new BindingEntry(dim, pos, blockKey);
        }

        }

        public static List<BindingEntry> getBindings(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        migrateFromLegacy(tag);
        if (!tag.contains(KEY_BINDINGS, Tag.TAG_LIST)) return Collections.emptyList();
        ListTag list = tag.getList(KEY_BINDINGS, Tag.TAG_COMPOUND);
        List<BindingEntry> entries = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            BindingEntry entry = BindingEntry.fromTag(list.getCompound(i));
            if (entry != null) entries.add(entry);
        }
        return Collections.unmodifiableList(entries);
    }

    public static boolean addBinding(ItemStack stack, ResourceLocation dim, BlockPos pos, String blockKey) {
        CompoundTag tag = stack.getOrCreateTag();
        migrateFromLegacy(tag);
        ListTag list = tag.getList(KEY_BINDINGS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            if (dim.toString().equals(entryTag.getString("dim"))
                    && pos.getX() == entryTag.getInt("x")
                    && pos.getY() == entryTag.getInt("y")
                    && pos.getZ() == entryTag.getInt("z")) {
                return false;
            }
        }
        BindingEntry entry = new BindingEntry(dim, pos, blockKey);
        list.add(entry.toTag());
        tag.put(KEY_BINDINGS, list);
        return true;
    }

    public static boolean removeBinding(ItemStack stack, ResourceLocation dim, BlockPos pos) {
        CompoundTag tag = stack.getOrCreateTag();
        migrateFromLegacy(tag);
        if (!tag.contains(KEY_BINDINGS, Tag.TAG_LIST)) return false;
        ListTag list = tag.getList(KEY_BINDINGS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            if (dim.toString().equals(entryTag.getString("dim"))
                    && pos.getX() == entryTag.getInt("x")
                    && pos.getY() == entryTag.getInt("y")
                    && pos.getZ() == entryTag.getInt("z")) {
                list.remove(i);
                tag.put(KEY_BINDINGS, list);
                return true;
            }
        }
        return false;
    }

    public static boolean hasBinding(ItemStack stack, ResourceLocation dim, BlockPos pos) {
        if (!stack.hasTag()) return false;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(KEY_BINDINGS, Tag.TAG_LIST)) return false;
        ListTag list = tag.getList(KEY_BINDINGS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            if (dim.toString().equals(entryTag.getString("dim"))
                    && pos.getX() == entryTag.getInt("x")
                    && pos.getY() == entryTag.getInt("y")
                    && pos.getZ() == entryTag.getInt("z")) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasAnyBinding(ItemStack stack) {
        if (!stack.hasTag()) return false;
        CompoundTag tag = stack.getTag();
        if (tag == null) return false;
        return tag.contains(KEY_BINDINGS, Tag.TAG_LIST) && !tag.getList(KEY_BINDINGS, Tag.TAG_COMPOUND).isEmpty();
    }

    private static void migrateFromLegacy(CompoundTag tag) {
        if (!tag.contains("aec_bound_x")) return;
        String dimStr = tag.getString("aec_bound_dim");
        ResourceLocation dim = ResourceLocation.tryParse(dimStr);
        if (dim == null) {
            com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.warn(
                    "[RSI] BindingStorage: corrupt legacy dimension '{}' — binding dropped", dimStr);
            tag.remove("aec_bound_dim");
            tag.remove("aec_bound_x");
            tag.remove("aec_bound_y");
            tag.remove("aec_bound_z");
            tag.remove("aec_bound_block");
            return;
        }
        BlockPos pos = new BlockPos(
                tag.getInt("aec_bound_x"),
                tag.getInt("aec_bound_y"),
                tag.getInt("aec_bound_z"));
        String blockKey = tag.getString("aec_bound_block");

        tag.remove("aec_bound_dim");
        tag.remove("aec_bound_x");
        tag.remove("aec_bound_y");
        tag.remove("aec_bound_z");
        tag.remove("aec_bound_block");

        ListTag list = tag.getList(KEY_BINDINGS, Tag.TAG_COMPOUND);
        BindingEntry entry = new BindingEntry(dim, pos, blockKey);
        list.add(entry.toTag());
        tag.put(KEY_BINDINGS, list);
    }
}
