package com.example.rsiintegration;

import com.huanghuang.rsintegration.api.ResourceMatchMode;
import com.huanghuang.rsintegration.api.resonance.ResonanceEntry;
import com.huanghuang.rsintegration.api.resonance.ResonanceExtraction;
import com.huanghuang.rsintegration.api.resonance.ResonanceInventory;
import com.huanghuang.rsintegration.api.resonance.ResonanceResult;
import com.huanghuang.rsintegration.api.resonance.RSIResonanceApi;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class PublicResonanceExample {
    private PublicResonanceExample() {}

    public static List<ResonanceEntry> findExact(
            ServerPlayer player,
            ItemStack template
    ) {
        RSIResonanceApi api = RSIResonanceApi.get().orElse(null);
        if (api == null) return List.of();
        ResonanceResult<ResonanceInventory> opened =
                api.openInventory(player);
        if (!opened.successful()) return List.of();
        return opened.value().find(
                template,
                ResourceMatchMode.EXACT_IDENTITY
        );
    }

    public static ItemStack consumeOne(
            ServerPlayer player,
            ItemStack template
    ) {
        RSIResonanceApi api = RSIResonanceApi.get().orElse(null);
        if (api == null) return ItemStack.EMPTY;
        ResonanceResult<ResonanceInventory> opened =
                api.openInventory(player);
        if (!opened.successful()) return ItemStack.EMPTY;
        ResonanceInventory inventory = opened.value();
        List<ResonanceEntry> matches = inventory.find(
                template,
                ResourceMatchMode.EXACT_IDENTITY
        );
        if (matches.isEmpty()) return ItemStack.EMPTY;
        long entryId = matches.get(0).entryId();
        if (!inventory.extract(entryId, 1, true).successful()) {
            return ItemStack.EMPTY;
        }
        ResonanceResult<ResonanceExtraction> extracted =
                inventory.extract(entryId, 1, false);
        return extracted.successful()
                ? extracted.value().stack() : ItemStack.EMPTY;
    }
}
