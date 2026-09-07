package com.huanghuang.rsintegration.crafting;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public record CraftFailureContext(ZonedDateTime receivedAt, String mode, Map<String, String> clientVersions) {
    public CraftFailureContext {
        clientVersions = Collections.unmodifiableMap(new LinkedHashMap<>(clientVersions));
    }

    static CraftFailureContext capture(CraftProgressSnapshot snapshot, ItemStack target, Boolean graphMode) {
        ZonedDateTime received = ZonedDateTime.now();
        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("minecraft", SharedConstants.getCurrentVersion().getName());
        Set<String> relevant = new LinkedHashSet<>();
        relevant.add("forge");
        relevant.add("rs_integration");
        if (!target.isEmpty()) relevant.add(BuiltInRegistries.ITEM.getKey(target.getItem()).getNamespace());
        for (var node : snapshot.nodes().stream().limit(CraftFailureReport.MAX_REPORT_NODES).toList()) {
            if (relevant.size() >= 32) break;
            ResourceLocation recipe = ResourceLocation.tryParse(node.recipeId());
            if (recipe != null) relevant.add(recipe.getNamespace());
            ItemStack output = node.displayOutput();
            if (!output.isEmpty()) {
                relevant.add(BuiltInRegistries.ITEM.getKey(output.getItem()).getNamespace());
            }
            ResourceLocation adapter = ResourceLocation.tryParse(node.modTypeId());
            if (adapter != null) relevant.add(node.modTypeId().contains(":")
                    ? adapter.getNamespace() : adapter.getPath());
        }
        ModList mods = ModList.get();
        relevant.stream().limit(32).filter(id -> !id.equals("minecraft")).forEach(id ->
                versions.put(id, mods == null ? "unknown" : mods.getModContainerById(id)
                        .map(mod -> mod.getModInfo().getVersion().toString()).orElse("unknown")));
        return new CraftFailureContext(received, graphMode == null ? "unknown" : graphMode ? "graph" : "flat", versions);
    }

    static CraftFailureContext unknown(String version) {
        return new CraftFailureContext(null, "unknown", Map.of("rs_integration", version));
    }
}
