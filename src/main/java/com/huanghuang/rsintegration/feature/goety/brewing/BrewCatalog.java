package com.huanghuang.rsintegration.feature.goety.brewing;

import com.Polarice3.Goety.common.crafting.BrewingRecipe;
import com.Polarice3.Goety.common.crafting.ModRecipeSerializer;
import com.Polarice3.Goety.common.effects.brew.BrewEffects;
import com.Polarice3.Goety.common.effects.brew.block.GrowTreeBlockEffect;
import com.Polarice3.Goety.common.effects.brew.modifiers.BrewModifier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import com.google.gson.JsonParser;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Discovered from the loaded Goety configuration and datapacks, rebuilt for each editor session. */
public final class BrewCatalog {
    public static final int CAPACITY = 0, EFFECT = 1, MODIFIER = 2;
    public record Entry(ResourceLocation item, int kind, String group, int level, Component name, String equivalence, String family, String ingredient, boolean familyAlternatives) {
        public ItemStack stack() { return new ItemStack(ForgeRegistries.ITEMS.getValue(item)); }
        public Entry withIngredient(Ingredient input) { return new Entry(item, kind, group, level, name, equivalence, family, input.toJson().toString(), familyAlternatives); }
    }
    /** One effect or enhancement family; levels and interchangeable catalysts stay inside it. */
    public record Group(int kind, String key, Component name, List<Entry> entries) {
        public List<Integer> levels() { return entries.stream().map(Entry::level).distinct().sorted().toList(); }
    }
    private final List<Entry> entries;
    private final Map<ResourceLocation, Entry> byItem;
    private final Map<ResourceLocation, List<Entry>> alternatives;
    public BrewCatalog(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        var index = new HashMap<ResourceLocation, Entry>(); entries.forEach(e -> index.put(e.item(), e));
        byItem = Map.copyOf(index);
        var choices = new HashMap<ResourceLocation, List<Entry>>();
        for (var requested : entries) {
            var input = Ingredient.fromJson(JsonParser.parseString(requested.ingredient()));
            choices.put(requested.item(), entries.stream().filter(e -> sameFamily(requested, e) &&
                    (requested.familyAlternatives() || input.test(e.stack()))).toList());
        }
        alternatives = Map.copyOf(choices);
    }
    public List<Entry> entries() { return entries; }
    public Optional<Entry> find(ResourceLocation item) { return Optional.ofNullable(byItem.get(item)); }
    public static List<Group> groups(List<Entry> entries, int kind) {
        var grouped = new LinkedHashMap<String, List<Entry>>();
        entries.stream().filter(e -> e.kind() == kind).forEach(e -> grouped.computeIfAbsent(kind == EFFECT
                ? (e.familyAlternatives() ? e.family() : e.family() + "|" + e.ingredient()) : e.family(), ignored -> new ArrayList<>()).add(e));
        return grouped.entrySet().stream().map(e -> new Group(kind, e.getKey(), kind == EFFECT
                ? Component.translatable(e.getValue().get(0).family()) : e.getValue().get(0).name(), List.copyOf(e.getValue()))).toList();
    }
    public Entry availableAlternative(Entry requested, java.util.function.ToLongFunction<Entry> stock) {
        Entry best = requested; long amount = stock.applyAsLong(requested);
        for (var candidate : alternatives(requested)) {
            long available = stock.applyAsLong(candidate);
            if (available > amount) { best = candidate; amount = available; }
        }
        return best;
    }

    public List<Entry> alternatives(Entry requested) { return alternatives.getOrDefault(requested.item(), List.of(requested)); }
    private static boolean sameFamily(Entry a, Entry b) {
        return a.kind() == b.kind() && a.family().equals(b.family()) && a.level() == b.level() && a.equivalence().equals(b.equivalence());
    }

    public static BrewCatalog discover(Level level) {
        var effects = new BrewEffects();
        var recipes = level.getRecipeManager().getAllRecipesFor(ModRecipeSerializer.BREWING_TYPE.get());
        var found = new ArrayList<Entry>();
        var nativeItems = new HashSet<ResourceLocation>();
        for (var item : ForgeRegistries.ITEMS) {
            var id = ForgeRegistries.ITEMS.getKey(item);
            var modifier = effects.getModifier(item);
            if (modifier != null) {
                boolean capacity = modifier.getId().equals(BrewModifier.CAPACITY);
                found.add(new Entry(id, capacity ? CAPACITY : MODIFIER, modifier.getId(), modifier.getLevel(),
                        Component.translatable("gui.rs_integration.modifier." + modifier.getId()), modifier.getId() + ":" + modifier.getLevel(), modifier.getId(), Ingredient.of(item).toJson().toString(), false));
                nativeItems.add(id);
                continue;
            }
            // Native Goety gives datapack catalysts precedence over its built-in catalogue.
            BrewingRecipe recipe = recipes.stream().filter(r -> r.getInput().test(new ItemStack(item))).findFirst().orElse(null);
            if (recipe != null) {
                found.add(new Entry(id, EFFECT, recipe.getOutput().getDescriptionId(), 0, recipe.getOutput().getDisplayName(),
                        recipe.getSoulCost() + ":" + recipe.getCapacityExtra() + ":" + recipe.getDuration(), recipe.getOutput().getDescriptionId(), recipe.getInput().toJson().toString(), false));
            } else {
                var effect = effects.getEffectFromCatalyst(item);
                if (effect != null) {
                    String family = effect instanceof GrowTreeBlockEffect
                            ? "effect.goety.grow_tree" : effect.getDescriptionId();
                    found.add(new Entry(id, EFFECT, effect.getEffectID(), 0, effect.getDisplayName(),
                            effect.getSoulCost() + ":" + effect.getCapacityExtra() + ":" + effect.getDuration(), family, Ingredient.of(item).toJson().toString(), true));
                }
                if (effect != null) nativeItems.add(id);
            }
        }
        // Datapack Ingredient rules are authoritative, including exact items and custom ingredients.
        // Native catalysts have no Ingredient API: prefer an existing tag only when its members
        // are accepted by the same native effect family and cost rules. Never widen an exact datapack input.
        var nativeIndex = new HashMap<ResourceLocation, Entry>(); found.forEach(e -> nativeIndex.put(e.item(), e));
        for (int i = 0; i < found.size(); i++) {
            var e = found.get(i);
            if (!nativeItems.contains(e.item()) || e.familyAlternatives()) continue;
            Ingredient best = null; int bestSize = 1;
            var tags = ForgeRegistries.ITEMS.tags().getReverseTag(e.stack().getItem())
                    .map(reverse -> reverse.getTagKeys().sorted(Comparator.comparing(t -> t.location().toString())).toList())
                    .orElse(List.of());
            for (var tag : tags) {
                var input = Ingredient.of(tag);
                var members = ForgeRegistries.ITEMS.tags().getTag(tag).stream().map(ItemStack::new).toArray(ItemStack[]::new);
                if (members.length <= bestSize) continue;
                boolean compatible = Arrays.stream(members).allMatch(stack -> {
                    var member = nativeIndex.get(ForgeRegistries.ITEMS.getKey(stack.getItem()));
                    return member != null && nativeItems.contains(member.item()) && sameFamily(e, member);
                });
                if (compatible) { best = input; bestSize = members.length; }
            }
            if (best != null) found.set(i, e.withIngredient(best));
        }
        found.sort(Comparator.comparingInt(Entry::kind).thenComparing(Entry::group).thenComparingInt(Entry::level)
                .thenComparing(e -> e.item().toString()));
        return new BrewCatalog(found);
    }
}
