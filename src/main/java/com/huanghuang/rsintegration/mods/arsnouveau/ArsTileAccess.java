package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.reflection.probes.ArsNouveauReflection;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Central access helper for Ars Nouveau machine internals.
 *
 * <p>Every method here touches ONLY Ars-Nouveau-owned classes/fields via
 * reflection (safe — the mod's own symbols are not SRG-remapped) and casts to
 * vanilla types ({@link Container}, {@link BlockEntity}) for everything else.
 * The two batch delegates ({@link ArsImbuementBatchDelegate},
 * {@link ArsApparatusBatchDelegate}) call through here and never reflect
 * directly, so all knowledge of Ars field names is confined to this one file.</p>
 *
 * <p>Reflection targets (verified against decompiled Ars Nouveau 4.12.6):</p>
 * <ul>
 *   <li>{@code EnchantingApparatusTile.counter} (private int), {@code isCrafting} (public boolean)</li>
 *   <li>{@code ImbuementTile.craftTicks} (package int), {@code stack} (public ItemStack)</li>
 *   <li>{@code AbstractSourceMachine.getSource()} / {@code getMaxSource()} (public int, inherited by ImbuementTile)</li>
 *   <li>{@code pedestalList()} (apparatus, radius 3) / {@code getNearbyPedestals()} (imbuement, radius 1)</li>
 *   <li>{@code getPedestalItems()} → {@code List<ItemStack>} on both machines</li>
 * </ul>
 */
public final class ArsTileAccess {

    /** Apparatus craft length (counter completes when it exceeds this). */
    public static final int APPARATUS_CRAFT_LENGTH = 210;
    /** Imbuement craft tick countdown length. */
    public static final int IMBUEMENT_CRAFT_TICKS = 100;

    private ArsTileAccess() {}

    // ── machine-type identification ───────────────────────────────

    public static boolean isApparatus(@Nullable BlockEntity be) {
        Class<?> c = ArsNouveauReflection.apparatusTileClass;
        return be != null && c != null && c.isInstance(be);
    }

    public static boolean isImbuement(@Nullable BlockEntity be) {
        Class<?> c = ArsNouveauReflection.imbuementTileClass;
        return be != null && c != null && c.isInstance(be);
    }

    // ── crafting-state reads (Apparatus) ──────────────────────────

    /** Apparatus {@code isCrafting} flag (public boolean). Defaults to false when unreadable. */
    public static boolean isApparatusCrafting(BlockEntity be) {
        return Reflect.<Boolean>getField(be, "isCrafting").orElse(Boolean.FALSE);
    }

    /** Apparatus {@code counter} (private int, 0..210+). Returns -1 when unreadable. */
    public static int apparatusCounter(BlockEntity be) {
        OptionalInt v = Reflect.getIntField(be, "counter");
        return v.isPresent() ? v.getAsInt() : -1;
    }

    // ── crafting-state reads (Imbuement) ──────────────────────────

    /** Imbuement {@code craftTicks} countdown (package int, 100..0). Returns -1 when unreadable. */
    public static int imbuementCraftTicks(BlockEntity be) {
        OptionalInt v = Reflect.getIntField(be, "craftTicks");
        return v.isPresent() ? v.getAsInt() : -1;
    }

    /**
     * Imbuement input {@code stack} (public ItemStack field). Read the field
     * directly rather than {@code getItem(0)} — the tile has no {@code isCrafting}
     * masking on {@code m_8020_}, so the field is the true input.
     */
    public static ItemStack imbuementInput(BlockEntity be) {
        return Reflect.<ItemStack>getField(be, "stack").orElse(ItemStack.EMPTY);
    }

    // ── Source (inherited from AbstractSourceMachine) ─────────────

    /** Current stored Source. Returns -1 when unreadable (probe absent / wrong tile). */
    public static int getSource(BlockEntity be) {
        Optional<Integer> v = Reflect.invoke(be, "getSource");
        return v.orElse(-1);
    }

    /** Maximum Source capacity. Returns -1 when unreadable. */
    public static int getMaxSource(BlockEntity be) {
        Optional<Integer> v = Reflect.invoke(be, "getMaxSource");
        return v.orElse(-1);
    }

    // ── pedestal enumeration ──────────────────────────────────────

    /**
     * The pedestal positions this machine scans. Apparatus uses
     * {@code pedestalList()} (radius 3); Imbuement uses
     * {@code getNearbyPedestals()} (radius 1). Returns an empty list when
     * neither method is present.
     */
    @SuppressWarnings("unchecked")
    public static List<BlockPos> pedestalPositions(BlockEntity be) {
        Optional<List<BlockPos>> apparatus = Reflect.invoke(be, "pedestalList");
        if (apparatus.isPresent()) return apparatus.get();
        Optional<List<BlockPos>> imbuement = Reflect.invoke(be, "getNearbyPedestals");
        return imbuement.orElseGet(ArrayList::new);
    }

    /**
     * The pedestal item stacks currently placed around the machine, filtered by
     * Ars itself. Both tiles expose {@code getPedestalItems()}.
     */
    @SuppressWarnings("unchecked")
    public static List<ItemStack> pedestalItems(BlockEntity be) {
        Optional<List<ItemStack>> items = Reflect.invoke(be, "getPedestalItems");
        return items.orElseGet(ArrayList::new);
    }

    // ── recipe-type classification (dodges the instanceof subtype trap) ──

    /**
     * The Ars recipe-type registry id (e.g. {@code ars_nouveau:imbuement}),
     * or {@code null} when the recipe's type has no registry name. Classifying
     * by the registered {@link RecipeType} avoids the
     * {@code reactive_enchantment extends EnchantmentRecipe} instanceof trap.
     */
    @Nullable
    public static String recipeTypeId(Recipe<?> recipe) {
        RecipeType<?> type = recipe.getType();
        net.minecraft.resources.ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.RECIPE_TYPES.getKey(type);
        return key != null ? key.toString() : null;
    }

    public static boolean isImbuementRecipe(Recipe<?> recipe) {
        return ArsRecipeClassifier.isImbuement(recipeTypeId(recipe));
    }

    public static boolean isApparatusRecipe(Recipe<?> recipe) {
        return ArsRecipeClassifier.isApparatus(recipeTypeId(recipe));
    }
}
