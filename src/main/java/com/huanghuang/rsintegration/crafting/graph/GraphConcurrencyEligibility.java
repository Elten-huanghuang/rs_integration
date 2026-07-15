package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;

import java.util.Locale;
import java.util.Set;

/** Recipe-aware allowlist for delegates whose class covers mixed-safe subtypes. */
public final class GraphConcurrencyEligibility {
    private static final Set<String> WORLD_CAPTURE_TYPES = Set.of(
            "malum", "malum_spirit_crucible", "malum_runic_workbench",
            "touhou_little_maid", "youkaishomecoming_steamer",
            "youkaishomecoming_cooking_small", "youkaishomecoming_cooking_short",
            "youkaishomecoming_cooking_large", "farmersdelight_skillet",
            "vanilla_campfire", "goety");

    private static final Set<String> RITUAL_TYPES = Set.of(
            "goety", "forbidden_arcanus", "malum", "touhou_little_maid",
            "aether_altar", "embers_alchemy");

    private GraphConcurrencyEligibility() {}

    public record Context(String modTypeId, String recipeClassName, boolean inferMode) {
        public Context {
            modTypeId = normalize(modTypeId);
            recipeClassName = recipeClassName == null ? "" : recipeClassName;
        }
    }

    public static BatchConcurrencyCapabilities capabilities(Context context) {
        if (context == null) return null;
        String type = context.modTypeId();
        String recipeClass = context.recipeClassName();

        if (context.inferMode()) {
            if (!"embers_alchemy".equals(type)) return null;
            return capability(BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                    BatchConcurrencyCapabilities.SideEffects.INFER, 3);
        }
        if ("eidolon".equals(type)) {
            if (recipeClass.endsWith("WorktableRecipe")) return null;
            if (!recipeClass.endsWith("ItemRitualRecipe")
                    && !recipeClass.endsWith("GenericRitualRecipe")
                    && !recipeClass.contains("Crucible")
                    && !recipeClass.contains("Brazier")) return null;
            return capability(BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                    BatchConcurrencyCapabilities.SideEffects.ADJACENT_MACHINE, 3);
        }
        if ("wizards_reborn".equals(type)) {
            if (recipeClass.endsWith("CrystalInfusionRecipe")
                    || recipeClass.endsWith("ArcaneIteratorRecipe")) return null;
            if (!recipeClass.endsWith("CrystalRitualRecipe")) return null;
            return capability(BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                    BatchConcurrencyCapabilities.SideEffects.ADJACENT_MACHINE, 3);
        }
        if (WORLD_CAPTURE_TYPES.contains(type)) {
            return capability(BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                    RITUAL_TYPES.contains(type)
                            ? BatchConcurrencyCapabilities.SideEffects.ADJACENT_MACHINE
                            : BatchConcurrencyCapabilities.SideEffects.MACHINE_LOCAL,
                    RITUAL_TYPES.contains(type) ? 3 : 0);
        }
        if (RITUAL_TYPES.contains(type)) {
            return capability(BatchConcurrencyCapabilities.OutputOwnership.DELEGATE_RESULT,
                    BatchConcurrencyCapabilities.SideEffects.ADJACENT_MACHINE, 3);
        }
        return null;
    }

    static boolean isRecipeAwareType(String modTypeId) {
        String type = normalize(modTypeId);
        return WORLD_CAPTURE_TYPES.contains(type) || RITUAL_TYPES.contains(type)
                || "eidolon".equals(type) || "wizards_reborn".equals(type);
    }

    private static BatchConcurrencyCapabilities capability(
            BatchConcurrencyCapabilities.OutputOwnership output,
            BatchConcurrencyCapabilities.SideEffects sideEffects,
            int supportRadius) {
        java.util.ArrayList<net.minecraft.core.BlockPos> supportOffsets = new java.util.ArrayList<>();
        if (supportRadius > 0) {
            for (int x = -supportRadius; x <= supportRadius; x++) {
                for (int z = -supportRadius; z <= supportRadius; z++) {
                    if (x == 0 && z == 0) continue;
                    supportOffsets.add(new net.minecraft.core.BlockPos(x, 0, z));
                }
            }
        }
        return new BatchConcurrencyCapabilities(
                BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                output,
                BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                sideEffects,
                BatchConcurrencyCapabilities.PreparationContract.RETRY_SAFE,
                supportOffsets);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
