package com.huanghuang.rsintegration.crafting.plan;

import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Selects the unresolved raw-material leaves shown by the plan material bill. */
public final class MissingMaterialBookmarkList {
    private MissingMaterialBookmarkList() {}

    public static List<ItemStack> from(PlanResponse plan) {
        return plan.materials().entrySet().stream()
                .filter(entry -> entry.getValue().missingCount() > 0)
                .map(entry -> entry.getKey().stack(1))
                .toList();
    }

    /** Resolve plain missing diagnostics into localized, individually bookmarkable labels. */
    public static List<TextEntry> textEntries(PlanResponse plan) {
        List<ItemStack> candidates = from(plan);
        List<TextEntry> result = new ArrayList<>();
        Set<Integer> usedCandidates = new HashSet<>();
        List<String> missing = plan.missing() == null ? List.of() : plan.missing();

        for (String raw : missing) {
            String label = raw == null ? "" : raw.trim();
            Match match = resolve(label, candidates, usedCandidates,
                    missing.size() == 1 && candidates.size() == 1);
            if (match == null) {
                if (!label.isEmpty()) result.add(new TextEntry(label, ItemStack.EMPTY, 0));
                continue;
            }
            // A diagnostic may resolve to a registered item that is not part
            // of this plan's shortage bill. Keep it bookmarkable, but do not
            // mark a real candidate as consumed.
            if (match.index() >= 0) {
                usedCandidates.add(match.index());
            }
            ItemStack stack = match.stack().copyWithCount(1);
            PlanResponse.Availability availability = plan.availability(stack);
            int shortage = availability == null ? 1
                    : Math.max(1, availability.missingCount());
            result.add(new TextEntry(stack.getHoverName().getString(), stack, shortage));
        }

        // The resolver's diagnostic list is intentionally compact and can be
        // empty when the material bill itself is what discovered the shortage
        // (for example, a direct terminal preview or a partially supplied
        // alternative).  The material bill is authoritative for the concrete
        // item/count pair, so append every shortage that was not represented by
        // a diagnostic entry.  This keeps the warning complete without
        // reintroducing duplicate rows for the same shortage.
        for (int i = 0; i < candidates.size(); i++) {
            if (usedCandidates.contains(i)) continue;
            ItemStack stack = candidates.get(i);
            PlanResponse.Availability availability = plan.availability(stack);
            int shortage = availability == null ? 1
                    : Math.max(1, availability.missingCount());
            result.add(new TextEntry(stack.getHoverName().getString(),
                    stack.copyWithCount(1), shortage));
        }
        return List.copyOf(result);
    }

    private static Match resolve(String raw, List<ItemStack> candidates,
                                 Set<Integer> usedCandidates, boolean singleFallback) {
        String normalized = normalize(raw);
        ResourceLocation id = ResourceLocation.tryParse(normalized);
        if (id != null) {
            var item = ForgeRegistries.ITEMS.getValue(id);
            if (item != null && item != Items.AIR) {
                for (int i = 0; i < candidates.size(); i++) {
                    if (!usedCandidates.contains(i) && candidates.get(i).getItem() == item) {
                        return new Match(i, candidates.get(i));
                    }
                }
                return new Match(-1, new ItemStack(item));
            }
        }

        int containedMatch = -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (usedCandidates.contains(i)) continue;
            ItemStack candidate = candidates.get(i);
            String name = normalize(candidate.getHoverName().getString());
            ResourceLocation candidateId = ForgeRegistries.ITEMS.getKey(candidate.getItem());
            String idText = candidateId == null ? "" : candidateId.toString();
            if (normalized.equals(name) || normalized.equals(idText)) {
                return new Match(i, candidate);
            }
            if (!name.isEmpty() && normalized.contains(name)) {
                if (containedMatch >= 0) return null;
                containedMatch = i;
            }
        }
        if (containedMatch >= 0) return new Match(containedMatch, candidates.get(containedMatch));
        if (singleFallback) return new Match(0, candidates.get(0));
        return null;
    }

    private static String normalize(String value) {
        String stripped = ChatFormatting.stripFormatting(value);
        return (stripped == null ? "" : stripped.trim()).toLowerCase(Locale.ROOT);
    }

    public record TextEntry(String label, ItemStack bookmark, int missingCount) {
        public TextEntry {
            label = label == null ? "" : label;
            bookmark = bookmark == null ? ItemStack.EMPTY : bookmark.copyWithCount(1);
            missingCount = Math.max(0, missingCount);
        }

        public boolean bookmarkable() {
            return !bookmark.isEmpty();
        }
    }

    private record Match(int index, ItemStack stack) {}
}
