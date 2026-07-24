package com.huanghuang.rsintegration.mods.apotheosis;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class ApothSpawnerModels {
    public static final int MAX_ENTRIES = 32;
    public static final int MAX_MESSAGE = 256;

    private ApothSpawnerModels() {}

    public record Entry(ResourceLocation recipeId, String statId, ItemStack material,
                        int currentValue, int targetValue, int applications,
                        int available, boolean complete, boolean supported) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeResourceLocation(recipeId);
            buf.writeUtf(statId, 64);
            buf.writeItem(material);
            buf.writeVarInt(currentValue);
            buf.writeVarInt(targetValue);
            buf.writeVarInt(applications);
            buf.writeVarInt(available);
            buf.writeBoolean(complete);
            buf.writeBoolean(supported);
        }

        public static Entry decode(FriendlyByteBuf buf) {
            return new Entry(buf.readResourceLocation(), buf.readUtf(64), buf.readItem(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readBoolean(), buf.readBoolean());
        }
    }

    public static void writeEntries(FriendlyByteBuf buf, List<Entry> entries) {
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many spawner upgrades");
        buf.writeVarInt(entries.size());
        entries.forEach(entry -> entry.encode(buf));
    }

    public static List<Entry> readEntries(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) throw new io.netty.handler.codec.DecoderException("Invalid upgrade count");
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) entries.add(Entry.decode(buf));
        return entries;
    }
}
