package com.huanghuang.rsintegration.mods.apotheosis;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Wire-safe models shared by the Apotheosis library import screens and packets. */
public final class ApotheosisLibraryModels {
    public static final int MAX_ENTRIES = 256;
    public static final int MAX_IMPORT_IDS = 256;
    public static final int MAX_ENTRY_COUNT = 2_147_483_647;
    public static final int MAX_ERROR_LENGTH = 128;
    public static final int MAX_ENCHANTMENTS_PER_ENTRY = 16;
    public static final int MAX_TRANSLATION_KEY_LENGTH = 128;

    private ApotheosisLibraryModels() {}

    public enum EntryStatus {
        IMPORTABLE,
        INVALID_BOOK,
        SPECIAL_NBT,
        LIBRARY_REJECTED
    }

    public record EnchantmentInfo(ResourceLocation id, String translationKey, int level) {
        public void encode(FriendlyByteBuf buf) {
            validate();
            buf.writeResourceLocation(id);
            buf.writeUtf(translationKey, MAX_TRANSLATION_KEY_LENGTH);
            buf.writeVarInt(level);
        }

        public static EnchantmentInfo decode(FriendlyByteBuf buf) {
            EnchantmentInfo info = new EnchantmentInfo(buf.readResourceLocation(),
                    buf.readUtf(MAX_TRANSLATION_KEY_LENGTH), buf.readVarInt());
            info.validate();
            return info;
        }

        private void validate() {
            if (id == null || translationKey == null || translationKey.isBlank()
                    || translationKey.length() > MAX_TRANSLATION_KEY_LENGTH || level <= 0 || level > 255) {
                throw new io.netty.handler.codec.DecoderException("Invalid Apotheosis enchantment info");
            }
        }
    }

    public record Entry(int id, ItemStack stack, int count, EntryStatus status,
                        List<EnchantmentInfo> enchantments) {
        public Entry {
            enchantments = List.copyOf(enchantments);
        }

        public void encode(FriendlyByteBuf buf) {
            validate(id, stack, count, status, enchantments);
            buf.writeVarInt(id);
            buf.writeItem(stack);
            buf.writeVarInt(count);
            buf.writeEnum(status);
            buf.writeVarInt(enchantments.size());
            enchantments.forEach(info -> info.encode(buf));
        }

        public static Entry decode(FriendlyByteBuf buf) {
            int id = buf.readVarInt();
            ItemStack stack = buf.readItem();
            int count = buf.readVarInt();
            EntryStatus status = buf.readEnum(EntryStatus.class);
            int enchantmentCount = buf.readVarInt();
            if (enchantmentCount < 0 || enchantmentCount > MAX_ENCHANTMENTS_PER_ENTRY) {
                throw new io.netty.handler.codec.DecoderException("Invalid Apotheosis enchantment count");
            }
            List<EnchantmentInfo> enchantments = new ArrayList<>(enchantmentCount);
            for (int i = 0; i < enchantmentCount; i++) enchantments.add(EnchantmentInfo.decode(buf));
            validate(id, stack, count, status, enchantments);
            return new Entry(id, stack, count, status, enchantments);
        }

        private static void validate(int id, ItemStack stack, int count, EntryStatus status,
                                     List<EnchantmentInfo> enchantments) {
            if (id < 0 || id >= MAX_ENTRIES || stack == null || stack.isEmpty()
                    || count <= 0 || count > MAX_ENTRY_COUNT || status == null
                    || enchantments == null || enchantments.size() > MAX_ENCHANTMENTS_PER_ENTRY) {
                throw new io.netty.handler.codec.DecoderException("Invalid Apotheosis library entry");
            }
        }
    }

    public record ImportStats(int imported, int skipped, int refunded, int dropped) {}
}
