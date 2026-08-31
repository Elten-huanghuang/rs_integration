package com.huanghuang.rsintegration.crafting;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-authoritative narrowing of a multi-option recipe ingredient. */
public final class MaterialLocks {
    public static final int MAX_LOCKS = 128;
    public static final int MAX_KEY_LENGTH = 160;

    private MaterialLocks() {}

    /**
     * Identifies one logical ingredient inside one recipe. Equal tag predicates in
     * the same recipe intentionally share a key, while child recipes remain scoped.
     */
    public static String key(ResourceLocation recipeId, Ingredient ingredient) {
        return recipeId + "#" + sha256(candidateFingerprint(ingredient));
    }

    public static Map<String, ItemStack> immutableCopy(@Nullable Map<String, ItemStack> locks) {
        if (locks == null || locks.isEmpty()) return Map.of();
        if (locks.size() > MAX_LOCKS) throw new IllegalArgumentException("too many material locks");
        Map<String, ItemStack> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ItemStack> entry : locks.entrySet()) {
            String key = entry.getKey();
            ItemStack stack = entry.getValue();
            if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH
                    || stack == null || stack.isEmpty()) continue;
            copy.put(key, stack.copyWithCount(1));
        }
        return Map.copyOf(copy);
    }

    /**
     * Returns the original ingredient when no lock exists. A lock is accepted only
     * when the server's own candidate list contains the selected item; the canonical
     * server candidate supplies all NBT and the client-provided stack is never used.
     */
    public static Ingredient narrow(ResourceLocation recipeId, Ingredient original,
                                    @Nullable Map<String, ItemStack> locks) {
        if (original == null || original.isEmpty() || locks == null || locks.isEmpty()) {
            return original;
        }
        String recipePrefix = recipeId + "#";
        if (locks.keySet().stream().noneMatch(key -> key.startsWith(recipePrefix))) return original;
        ItemStack requested = locks.get(key(recipeId, original));
        if (requested == null || requested.isEmpty()) return original;
        ItemStack canonical = canonicalCandidate(original, requested);
        if (canonical == null) {
            throw new IllegalArgumentException("invalid material lock for recipe " + recipeId);
        }
        return CraftingResolver.ingredientOf(canonical, IngredientMatcher.requiresNbt(original));
    }

    public static List<IngredientSpec> narrowSpecs(ResourceLocation recipeId,
                                                   List<IngredientSpec> specs,
                                                   @Nullable Map<String, ItemStack> locks) {
        if (specs == null || specs.isEmpty() || locks == null || locks.isEmpty()) return specs;
        List<IngredientSpec> narrowed = new ArrayList<>(specs.size());
        for (IngredientSpec spec : specs) {
            if (spec == null || spec.isEmpty()) {
                narrowed.add(spec);
            } else {
                narrowed.add(new IngredientSpec(narrow(recipeId, spec.ingredient(), locks),
                        spec.count(), spec.role()));
            }
        }
        return List.copyOf(narrowed);
    }

    @Nullable
    public static ItemStack canonicalCandidate(Ingredient original, ItemStack requested) {
        boolean strictNbt = IngredientMatcher.requiresNbt(original);
        for (ItemStack candidate : original.getItems()) {
            if (candidate == null || candidate.isEmpty()
                    || candidate.getItem() != requested.getItem()) continue;
            if (strictNbt && !ItemStack.isSameItemSameTags(candidate, requested)) continue;
            ItemStack canonical = candidate.copyWithCount(1);
            if (IngredientMatcher.test(original, canonical)) return canonical;
        }
        return null;
    }

    /** Immutable cache representation independent of mutable ItemStack instances. */
    public static Map<String, String> cacheTokens(@Nullable Map<String, ItemStack> locks) {
        Map<String, ItemStack> safe = immutableCopy(locks);
        if (safe.isEmpty()) return Map.of();
        Map<String, String> tokens = new LinkedHashMap<>();
        safe.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                tokens.put(entry.getKey(), stackToken(entry.getValue())));
        return Map.copyOf(tokens);
    }

    private static String candidateFingerprint(Ingredient ingredient) {
        List<String> candidates = new ArrayList<>();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack != null && !stack.isEmpty()) candidates.add(stackToken(stack));
        }
        candidates.sort(Comparator.naturalOrder());
        return String.join("\n", candidates);
    }

    private static String stackToken(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return (id == null ? "minecraft:air" : id.toString())
                + (stack.getTag() == null ? "" : "|" + stack.getTag());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            char[] hex = "0123456789abcdef".toCharArray();
            for (byte b : digest) {
                out.append(hex[(b >>> 4) & 0x0f]).append(hex[b & 0x0f]);
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
