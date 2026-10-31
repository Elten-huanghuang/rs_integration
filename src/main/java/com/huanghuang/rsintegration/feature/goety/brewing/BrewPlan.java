package com.huanghuang.rsintegration.feature.goety.brewing;

import com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

public record BrewPlan(List<ItemStack> sequence, int capacity, int used, int soulCost, int capacityTier,
                       ItemStack output, Component error, List<BrewCatalog.Entry> inputs) {
    public boolean valid() { return error == null; }

    /** Uses Goety's actual insertItem and getBrew rules on a detached, write-isolated cauldron. */
    public static BrewPlan create(Level source, BlockPos pos, BlockState state, BrewCatalog catalog,
                                  int capacityTier, List<ResourceLocation> selected) {
        return create(source, pos, state, catalog, capacityTier, selected, ignored -> 0L);
    }
    /** The smallest native-valid capacity, computed only when selections or an action change. */
    public static BrewPlan recommend(Level source, BlockPos pos, BlockState state, BrewCatalog catalog,
                                     List<ResourceLocation> selected, java.util.function.ToLongFunction<BrewCatalog.Entry> stock) {
        var tiers = catalog.entries().stream().filter(e -> e.kind() == BrewCatalog.CAPACITY)
                .map(BrewCatalog.Entry::level).distinct().sorted().toList();
        BrewPlan last = null;
        for (int tier : tiers) {
            if (tier < 0 || tier > 32) continue;
            last = create(source, pos, state, catalog, tier, selected, stock);
            if (last.valid() || selected.isEmpty()) return last;
        }
        return last == null ? create(source, pos, state, catalog, 0, selected, stock) : last;
    }
    public static BrewPlan create(Level source, BlockPos pos, BlockState state, BrewCatalog catalog,
                                  int capacityTier, List<ResourceLocation> selected,
                                  java.util.function.ToLongFunction<BrewCatalog.Entry> stock) {
        var sequence = new ArrayList<ItemStack>();
        var inputs = new ArrayList<BrewCatalog.Entry>();
        var shadow = new PreviewCauldron(pos, state);
        shadow.setLevel(new BrewPreviewLevel(source, pos, state));
        var capacities = catalog.entries().stream().filter(e -> e.kind() == BrewCatalog.CAPACITY)
                .collect(java.util.stream.Collectors.groupingBy(BrewCatalog.Entry::level, TreeMap::new, java.util.stream.Collectors.toList()));
        if (capacityTier < 0 || capacityTier > 32) return failed("selection", sequence, shadow);
        for (int tier = 0; tier <= capacityTier; tier++) {
            var choices = capacities.get(tier);
            if (choices == null || choices.isEmpty()) return failed("capacity", sequence, shadow);
            sequence.add(catalog.availableAlternative(choices.get(0), stock).stack()); inputs.add(choices.get(0));
        }
        var uniqueGroups = new HashSet<String>();
        var modifiers = new ArrayList<BrewCatalog.Entry>();
        for (var id : selected) {
            var entry = catalog.find(id).orElse(null);
            if (entry == null || entry.kind() == BrewCatalog.CAPACITY) return failed("selection", sequence, shadow);
            if (!uniqueGroups.add(entry.kind() + ":" + entry.family())) return failed("selection", sequence, shadow);
            if (entry.kind() == BrewCatalog.EFFECT) { sequence.add(catalog.availableAlternative(entry, stock).stack()); inputs.add(entry); }
            else modifiers.add(entry);
        }
        // A selected tier means every lower native tier must be supplied in order.
        for (var selectedModifier : modifiers) {
            var shapes = List.of("splash", "lingering", "gas");
            if (shapes.contains(selectedModifier.group())) {
                for (int i = 0; i <= shapes.indexOf(selectedModifier.group()); i++) {
                    final String requiredGroup = shapes.get(i);
                    var step = catalog.entries().stream().filter(e -> e.kind() == BrewCatalog.MODIFIER
                            && e.group().equals(requiredGroup) && e.level() == 0).findFirst();
                    if (step.isEmpty()) return failed("modifier", sequence, shadow);
                    sequence.add(catalog.availableAlternative(step.get(), stock).stack()); inputs.add(step.get());
                }
                continue;
            }
            for (int tier = 0; tier <= selectedModifier.level(); tier++) {
                final int required = tier;
                var step = catalog.entries().stream().filter(e -> e.kind() == BrewCatalog.MODIFIER
                        && e.group().equals(selectedModifier.group()) && e.level() == required).findFirst();
                if (step.isEmpty()) return failed("modifier", sequence, shadow);
                sequence.add(catalog.availableAlternative(step.get(), stock).stack()); inputs.add(step.get());
            }
        }
        // More than one final shape is rejected before any live material is touched.
        long shapes = modifiers.stream().filter(e -> Set.of("splash", "lingering", "gas").contains(e.group())).count();
        if (shapes > 1 || sequence.size() > 64) return failed("modifier", sequence, shadow);
        for (var step : sequence) {
            shadow.mode = shadow.insertItem(step.copy());
            if (shadow.mode == BrewCauldronBlockEntity.Mode.FAILED) return failed("sequence", sequence, shadow);
        }
        if (selected.stream().map(catalog::find).flatMap(Optional::stream).noneMatch(e -> e.kind() == BrewCatalog.EFFECT)) {
            return failed("effect", sequence, shadow);
        }
        return new BrewPlan(List.copyOf(sequence), shadow.getCapacity(), shadow.getCapacityUsed(),
                shadow.totalCost, capacityTier, shadow.getBrew().copy(), null, List.copyOf(inputs));
    }

    private static BrewPlan failed(String reason, List<ItemStack> sequence, BrewCauldronBlockEntity be) {
        return new BrewPlan(List.copyOf(sequence), be.getCapacity(), be.getCapacityUsed(), be.totalCost, -1,
                ItemStack.EMPTY, Component.translatable("gui.rs_integration.error." + reason), List.of());
    }

    private static final class PreviewCauldron extends BrewCauldronBlockEntity {
        PreviewCauldron(BlockPos pos, BlockState state) { super(pos, state); }
        @Override public void markUpdated() {}
        @Override public Mode fail() { return Mode.FAILED; }
        @Override public void setColor(int color) { liquidColor = color; }
        @Override public void setColor(int color, boolean animate) { liquidColor = color; }
    }
}
