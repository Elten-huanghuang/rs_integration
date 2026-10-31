package com.huanghuang.rsintegration.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 仅按注册名装饰提示，不加载 Botania、Ars 或背包模组的可选类型。 */
public final class EquipmentTooltip {
    public enum Kind {
        MANA_POOL("resonant_mana_pool", DiskTooltipEffects.Theme.MANA, 3),
        SOURCE_TANK("resonant_source_tank", DiskTooltipEffects.Theme.SOURCE, 3),
        PICKUP("rs_pickup_upgrade", DiskTooltipEffects.Theme.DIMENSION, 3),
        MAGNET("rs_magnet_upgrade", DiskTooltipEffects.Theme.DIMENSION, 3),
        REFILL("rs_refill_upgrade", DiskTooltipEffects.Theme.DIMENSION, 3),
        FEEDING("rs_feeding_upgrade", DiskTooltipEffects.Theme.DIMENSION, 3),
        VOID("rs_void_upgrade", DiskTooltipEffects.Theme.VOID, 4);

        private final String path;
        private final DiskTooltipEffects.Theme theme;
        private final int details;

        Kind(String path, DiskTooltipEffects.Theme theme, int details) {
            this.path = path;
            this.theme = theme;
            this.details = details;
        }

        public String path() { return path; }
        public DiskTooltipEffects.Theme theme() { return theme; }
        public int details() { return details; }
        public String prefix() { return "item.rs_integration." + path + ".guide."; }
    }

    private EquipmentTooltip() {}

    public static Kind kindFor(ResourceLocation id) {
        if (id == null || !"rs_integration".equals(id.getNamespace())) return null;
        return switch (id.getPath()) {
            case "resonant_mana_pool" -> Kind.MANA_POOL;
            case "resonant_source_tank" -> Kind.SOURCE_TANK;
            case "rs_pickup_upgrade" -> Kind.PICKUP;
            case "rs_magnet_upgrade" -> Kind.MAGNET;
            case "rs_refill_upgrade" -> Kind.REFILL;
            case "rs_feeding_upgrade" -> Kind.FEEDING;
            case "rs_void_upgrade" -> Kind.VOID;
            default -> null;
        };
    }

    public static DiskTooltipEffects.Theme themeFor(ResourceLocation id) {
        if (id == null || !"rs_integration".equals(id.getNamespace())) return null;
        Kind kind = kindFor(id);
        if (kind != null) return kind.theme();
        return switch (id.getPath()) {
            case "resonance_storage_disk", "resonance_network_generator" -> DiskTooltipEffects.Theme.RESONANCE;
            case "unified_storage_disk" -> DiskTooltipEffects.Theme.GUIXU;
            case "resonant_experience_wand" -> DiskTooltipEffects.Theme.EXPERIENCE;
            case "order_terminal" -> DiskTooltipEffects.Theme.FOOD;
            default -> null;
        };
    }

    public static void tintMetadata(List<Component> tooltip, ResourceLocation id) {
        var theme = themeFor(id);
        if (theme == null) return;
        for (int i = 0; i < tooltip.size(); i++) {
            Component line = tooltip.get(i);
            boolean metadata = id.toString().equals(line.getString());
            if (line.getContents() instanceof TranslatableContents contents) {
                metadata |= switch (contents.getKey()) {
                    case "item.nbt_tags", "item.durability" -> true;
                    default -> false;
                };
            }
            if (metadata) {
                tooltip.set(i, DiskTooltipEffects.flow(line, theme, DiskTooltipEffects.Tone.MUTED, 0));
            }
        }
    }

    public static void decorate(List<Component> tooltip, Kind kind, boolean expanded) {
        if (tooltip.isEmpty()) return;
        // 逐字着色覆盖旧组件中的固定灰色，同时保留已有状态、数量和其他模组附加行。
        for (int i = 0; i < tooltip.size(); i++) {
            Component line = tooltip.get(i);
            if (line.getString().isEmpty()) continue;
            if (i == 0) {
                tooltip.set(i, DiskTooltipEffects.flow(line, kind.theme(),
                        DiskTooltipEffects.Tone.MAIN, 0).copy().withStyle(ChatFormatting.BOLD));
            } else if (warning(line)) {
                tooltip.set(i, DiskTooltipEffects.flow(line, kind.theme(),
                        DiskTooltipEffects.Tone.WARNING, i * 0.07F));
            } else {
                tooltip.set(i, DiskTooltipEffects.flow(line, kind.theme(),
                        DiskTooltipEffects.Tone.MUTED, i * 0.07F));
            }
        }
        tooltip.add(Component.empty());
        tooltip.add(DiskTooltipEffects.flow(kind.prefix() + "summary", kind.theme(),
                DiskTooltipEffects.Tone.MAIN, 0));
        if (!expanded) {
            tooltip.add(DiskTooltipEffects.flow("item.rs_integration.equipment.guide.expand",
                    kind.theme(), DiskTooltipEffects.Tone.HINT, 0.3F));
            return;
        }
        tooltip.add(DiskTooltipEffects.flow(kind.prefix() + "title", kind.theme(),
                DiskTooltipEffects.Tone.HINT, 0).copy().withStyle(ChatFormatting.BOLD));
        for (int i = 1; i <= kind.details(); i++) {
            tooltip.add(DiskTooltipEffects.tint(Component.literal("• ").append(
                    Component.translatable(kind.prefix() + "detail_" + i)),
                    kind.theme(), kind == Kind.VOID && i == 4
                            ? DiskTooltipEffects.Tone.WARNING : DiskTooltipEffects.Tone.MUTED));
        }
    }

    private static boolean warning(Component line) {
        if (line.getContents() instanceof TranslatableContents contents) {
            String key = contents.getKey().toLowerCase(Locale.ROOT);
            if (key.endsWith(".unbound") || key.endsWith(".disabled") || key.endsWith(".fluid_uncertain")) {
                return true;
            }
        }
        return line.visit((style, text) -> {
            if (text.isBlank() || style.getColor() == null) return Optional.<Boolean>empty();
            int rgb = style.getColor().getValue();
            return (rgb >> 16 & 255) > (rgb >> 8 & 255) * 1.35
                    && (rgb >> 16 & 255) > (rgb & 255) * 1.35
                    ? Optional.of(true) : Optional.empty();
        }, Style.EMPTY).orElse(false);
    }
}
