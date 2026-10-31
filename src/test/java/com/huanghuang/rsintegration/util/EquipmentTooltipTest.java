package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipmentTooltipTest extends BootstrapTest {
    @Test
    void shiftExpandsEveryGuideAndLeavesExistingStateAndArgumentsVisible() {
        for (var kind : EquipmentTooltip.Kind.values()) {
            for (boolean expanded : new boolean[]{false, true}) {
                var lines = new ArrayList<Component>(List.of(Component.literal("设备名称"),
                        Component.literal("refinedstorage #123456"),
                        Component.translatable("item.rs_integration.rs_void_upgrade.rules", 17),
                        Component.literal("\u00a79\u00a7oRS Integration")));
                String rules = lines.get(2).getString();
                EquipmentTooltip.decorate(lines, kind, expanded);
                assertEquals("设备名称", lines.get(0).getString());
                assertEquals("refinedstorage #123456", lines.get(1).getString());
                assertEquals(rules, lines.get(2).getString());
                assertEquals("RS Integration", lines.get(3).getString(), "旧式格式码不能被逐字显示成 9o");
                assertTrue(contains(lines, kind.prefix() + "summary"));
                assertEquals(!expanded, contains(lines, "item.rs_integration.equipment.guide.expand"));
                for (int i = 1; i <= kind.details(); i++) {
                    assertEquals(expanded, contains(lines, kind.prefix() + "detail_" + i));
                }
                assertTrue(lines.get(0).getStyle().isBold());
                for (Component line : lines) {
                    line.visit((style, text) -> {
                        if (!text.isBlank()) assertNotNull(style.getColor(), text);
                        return Optional.empty();
                    }, Style.EMPTY);
                }
            }
        }
    }

    @Test
    void unboundDisabledAndUncertainFluidWarningsRemainWarmAndDistinct() {
        for (String key : List.of("item.sophisticatedbackpacks.rs_network.unbound",
                "item.sophisticatedbackpacks.rs_upgrade.disabled",
                "gui.rs_integration.magnet.fluid_uncertain")) {
            var lines = new ArrayList<Component>(List.of(Component.literal("设备"),
                    Component.translatable(key), Component.literal("其他故障").withStyle(ChatFormatting.RED)));
            EquipmentTooltip.decorate(lines, EquipmentTooltip.Kind.MAGNET, false);
            for (Component warning : lines.subList(1, 3)) {
                warning.visit((style, text) -> {
                    if (text.isBlank()) return Optional.empty();
                    int rgb = style.getColor().getValue();
                    assertTrue((rgb >> 16 & 255) > (rgb & 255), "警示不能被普通青蓝色覆盖");
                    return Optional.empty();
                }, Style.EMPTY);
            }
        }
        var lines = new ArrayList<Component>(List.of(Component.literal("虚空升级")));
        EquipmentTooltip.decorate(lines, EquipmentTooltip.Kind.VOID, true);
        int rgb = lines.get(lines.size() - 1).getStyle().getColor().getValue();
        assertTrue((rgb >> 16 & 255) > (rgb & 255));
    }

    @Test
    void advancedIdsAndNbtReceiveThemeColorsForAllSpecialItems() {
        for (String item : List.of("resonant_experience_wand", "order_terminal",
                "resonance_storage_disk", "resonance_network_generator", "unified_storage_disk",
                "resonant_mana_pool", "resonant_source_tank", "rs_pickup_upgrade",
                "rs_magnet_upgrade", "rs_refill_upgrade", "rs_feeding_upgrade", "rs_void_upgrade")) {
            var id = new ResourceLocation("rs_integration", item);
            var name = Component.literal("名称");
            var lines = new ArrayList<Component>(List.of(name,
                    Component.literal(id.toString()).withStyle(ChatFormatting.DARK_GRAY),
                    Component.translatable("item.nbt_tags", 4).withStyle(ChatFormatting.DARK_GRAY),
                    Component.translatable("item.durability", 17, 100).withStyle(ChatFormatting.DARK_GRAY)));
            var original = lines.stream().map(Component::getString).toList();
            EquipmentTooltip.tintMetadata(lines, id);
            assertSame(name, lines.get(0));
            assertEquals(original, lines.stream().map(Component::getString).toList());
            for (Component line : lines.subList(1, 4)) {
                line.visit((style, text) -> {
                    if (text.isBlank()) return Optional.empty();
                    assertNotNull(style.getColor());
                    assertTrue(style.getColor().getValue() != ChatFormatting.DARK_GRAY.getColor());
                    return Optional.empty();
                }, Style.EMPTY);
            }
        }
    }

    @Test
    void metadataTintLeavesUnrelatedItemsAndOtherNamespacesUntouched() {
        for (var id : List.of(new ResourceLocation("minecraft", "diamond"),
                new ResourceLocation("other_mod", "resonant_experience_wand"),
                new ResourceLocation("rs_integration", "fluid_refund"))) {
            var line = Component.literal(id.toString()).withStyle(ChatFormatting.DARK_GRAY);
            var lines = new ArrayList<Component>(List.of(line));
            EquipmentTooltip.tintMetadata(lines, id);
            assertSame(line, lines.get(0));
        }
    }

    private static boolean contains(List<Component> lines, String key) {
        String text = Component.translatable(key).getString();
        return lines.stream().anyMatch(line -> line.getString().contains(text));
    }
}
