package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.RSIntegrationMod;
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

    public record BindingEntry(ResourceLocation dim, BlockPos pos, String blockKey,
                                @javax.annotation.Nullable String blockRegKey,
                                @javax.annotation.Nullable ItemStack displayStack) {
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("dim", dim.toString());
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            tag.putString("block", blockKey);
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
            return new BindingEntry(dim, pos, blockKey, regKey, dispStack);
        }

        }

        /** Read-only — called from server and client (JEI) threads.
         *  Does NOT mutate the tag.  Concurrent reads are safe because
         *  {@link #addBinding}/{@link #removeBinding} use Copy-on-Write
         *  (clone → mutate → swap via {@code ItemStack.setTag}) and
         *  {@code ItemStack.tag} is a volatile reference, guaranteeing
         *  that readers always see a fully-constructed tag snapshot.
         *  Falls back to reading legacy format without migrating. */
        public static List<BindingEntry> getBindings(ItemStack stack) {
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

    /** Copy-on-write: clone the tag, mutate the copy, then atomically swap.
     *  Prevents the client render thread (JEI) from seeing a half-mutated
     *  CompoundTag when reading {@link #getBindings} concurrently. */
    public static boolean addBinding(ItemStack stack, ResourceLocation dim, BlockPos pos,
                                      String blockKey, @javax.annotation.Nullable String blockRegKey,
                                      @javax.annotation.Nullable ItemStack displayStack) {
        CompoundTag oldTag = stack.getTag();
        CompoundTag tag = oldTag != null ? oldTag.copy() : new CompoundTag();
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
        BindingEntry entry = new BindingEntry(dim, pos, blockKey, blockRegKey, displayStack);
        list.add(entry.toTag());
        tag.put(KEY_BINDINGS, list);
        stack.setTag(tag);
        RSIntegrationMod.LOGGER.debug("[RSI-Bind] addBinding: item={} dim={} pos={} blockKey={} regKey={} totalBindings={}",
                stack.getItem(), dim, pos, blockKey, blockRegKey, list.size());
        return true;
    }

    /** Copy-on-write: same as {@link #addBinding} — clone, mutate, swap. */
    public static boolean removeBinding(ItemStack stack, ResourceLocation dim, BlockPos pos) {
        CompoundTag oldTag = stack.getTag();
        if (oldTag == null) return false;
        if (!oldTag.contains(KEY_BINDINGS, Tag.TAG_LIST) && !oldTag.contains("aec_bound_x")) return false;
        CompoundTag tag = oldTag.copy();
        migrateFromLegacy(tag);
        ListTag list = tag.getList(KEY_BINDINGS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            if (dim.toString().equals(entryTag.getString("dim"))
                    && pos.getX() == entryTag.getInt("x")
                    && pos.getY() == entryTag.getInt("y")
                    && pos.getZ() == entryTag.getInt("z")) {
                list.remove(i);
                tag.put(KEY_BINDINGS, list);
                stack.setTag(tag);
                RSIntegrationMod.LOGGER.debug("[RSI-Bind] removeBinding: item={} dim={} pos={} remaining={}",
                        stack.getItem(), dim, pos, list.size());
                return true;
            }
        }
        return false;
    }

    /** Copy-on-write replacement for one binding's machine identity. */
    public static boolean replaceBindingBlockKey(ItemStack stack, ResourceLocation dim, BlockPos pos,
                                                 String expectedBlockKey, String replacementBlockKey) {
        CompoundTag oldTag = stack.getTag();
        if (oldTag == null || expectedBlockKey.equals(replacementBlockKey)) return false;
        CompoundTag tag = oldTag.copy();
        if (!tag.contains(KEY_BINDINGS, Tag.TAG_LIST) && tag.contains("aec_bound_x")) {
            if (!dim.toString().equals(tag.getString("aec_bound_dim"))
                    || pos.getX() != tag.getInt("aec_bound_x")
                    || pos.getY() != tag.getInt("aec_bound_y")
                    || pos.getZ() != tag.getInt("aec_bound_z")
                    || !expectedBlockKey.equals(tag.getString("aec_bound_block"))) {
                return false;
            }
            migrateFromLegacy(tag);
        }
        ListTag list = tag.getList(KEY_BINDINGS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            if (dim.toString().equals(entryTag.getString("dim"))
                    && pos.getX() == entryTag.getInt("x")
                    && pos.getY() == entryTag.getInt("y")
                    && pos.getZ() == entryTag.getInt("z")
                    && expectedBlockKey.equals(entryTag.getString("block"))) {
                entryTag.putString("block", replacementBlockKey);
                tag.put(KEY_BINDINGS, list);
                stack.setTag(tag);
                return true;
            }
        }
        return false;
    }

    public static String replaceBlockKeyPrefix(String blockKey, String replacementPrefix) {
        int separator = blockKey == null ? -1 : blockKey.indexOf("||");
        return separator < 0 ? blockKey : replacementPrefix + blockKey.substring(separator);
    }

    public static boolean hasBinding(ItemStack stack, ResourceLocation dim, BlockPos pos) {
        if (!stack.hasTag()) return false;
        CompoundTag tag = stack.getTag();
        if (tag == null) return false;
        if (tag.contains(KEY_BINDINGS, Tag.TAG_LIST)) {
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
        // Legacy format fallback (matches getBindings behaviour)
        if (tag.contains("aec_bound_x")) {
            return dim.toString().equals(tag.getString("aec_bound_dim"))
                    && pos.getX() == tag.getInt("aec_bound_x")
                    && pos.getY() == tag.getInt("aec_bound_y")
                    && pos.getZ() == tag.getInt("aec_bound_z");
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
            RSIntegrationMod.LOGGER.warn(
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
        BindingEntry entry = new BindingEntry(dim, pos, blockKey, null, null);
        list.add(entry.toTag());
        tag.put(KEY_BINDINGS, list);
    }
}
