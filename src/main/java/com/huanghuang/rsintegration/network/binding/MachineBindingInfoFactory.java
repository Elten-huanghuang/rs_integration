package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.network.binding.MachineBindingService.PlayerEndpoint;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** 服务端机器端点快照到客户端 BindingInfo 的唯一映射。 */
public final class MachineBindingInfoFactory {
    private MachineBindingInfoFactory() {}

    public static List<BindingInfo> fromEndpoints(
            List<PlayerEndpoint> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) return List.of();
        List<BindingInfo> result = new ArrayList<>(endpoints.size());
        for (PlayerEndpoint endpoint : endpoints) {
            result.add(fromEndpoint(endpoint, null));
        }
        return List.copyOf(result);
    }

    public static BindingInfo fromEndpoint(
            PlayerEndpoint endpoint, @Nullable String itemKeyOverride) {
        var entry = endpoint.entry();
        String itemKey = itemKeyOverride != null
                ? itemKeyOverride
                : endpoint.itemKey() == null
                ? "minecraft:air" : endpoint.itemKey().toString();
        return new BindingInfo(
                itemKey, entry.dim(), entry.pos(), entry.blockKey(),
                resolveDisplayName(
                        entry.blockKey(), entry.blockRegKey(),
                        entry.displayStack()),
                entry.blockRegKey(), entry.displayStack(),
                endpoint.bookId(), endpoint.endpointId());
    }

    public static BindingStorage.BindingEntry toEntry(BindingInfo info) {
        return new BindingStorage.BindingEntry(
                info.dim(), info.pos().immutable(), info.blockKey(),
                info.blockRegKey(),
                info.displayStack() == null ? null : info.displayStack().copy());
    }

    private static String resolveDisplayName(
            @Nullable String blockKey, @Nullable String blockRegKey,
            @Nullable ItemStack displayStack) {
        if (blockKey != null && blockKey.startsWith("pmmo_salvage||")) {
            return "rsi.batch.mod.pmmo_salvage";
        }
        if (displayStack != null && !displayStack.isEmpty()
                && displayStack.getHoverName().getContents()
                instanceof TranslatableContents translatable) {
            String key = translatable.getKey();
            String mapped = blockRegKey == null ? null
                    : BindingEventHandler.MULTI_PART_ROOT_MAP.get(blockRegKey);
            if (!key.equals(mapped)) return key;
        }
        if (blockRegKey != null) {
            String effectiveKey = BindingEventHandler.MULTI_PART_ROOT_MAP
                    .getOrDefault(blockRegKey, blockRegKey);
            ResourceLocation id = ResourceLocation.tryParse(effectiveKey);
            if (id != null) {
                var block = ForgeRegistries.BLOCKS.getValue(id);
                if (block != null) return block.getDescriptionId();
            }
        }
        if (blockKey == null || blockKey.isEmpty()) return "?";
        int separator = blockKey.indexOf("||");
        return separator >= 0
                ? blockKey.substring(separator + 2) : blockKey;
    }
}
