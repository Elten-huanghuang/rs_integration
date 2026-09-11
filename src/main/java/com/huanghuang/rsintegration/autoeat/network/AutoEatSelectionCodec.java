package com.huanghuang.rsintegration.autoeat.network;

import com.huanghuang.rsintegration.autoeat.AutoEatPreferences;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

final class AutoEatSelectionCodec {
    private AutoEatSelectionCodec() {}

    static List<ResourceLocation> copy(Collection<ResourceLocation> items) {
        if (items == null || items.isEmpty()) return List.of();
        LinkedHashSet<ResourceLocation> unique = new LinkedHashSet<>();
        for (ResourceLocation item : items) {
            if (item != null) unique.add(item);
            if (unique.size() >= AutoEatPreferences.MAX_SELECTED_ITEMS) break;
        }
        return List.copyOf(unique);
    }

    static void write(FriendlyByteBuf buffer, Collection<ResourceLocation> items) {
        List<ResourceLocation> selected = copy(items);
        buffer.writeVarInt(selected.size());
        selected.forEach(buffer::writeResourceLocation);
    }

    static List<ResourceLocation> read(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > AutoEatPreferences.MAX_SELECTED_ITEMS) {
            throw new DecoderException("auto-eat selection size out of range: " + size);
        }
        List<ResourceLocation> selected = new ArrayList<>(size);
        for (int i = 0; i < size; i++) selected.add(buffer.readResourceLocation());
        return copy(selected);
    }
}
