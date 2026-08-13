package net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.common;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/** Test fixture matching Sophisticated Core's generated display recipe naming. */
public final class CraftingDisplaySpec$SpecShapedRecipeFixture {
    private final SpecFixture spec;

    public CraftingDisplaySpec$SpecShapedRecipeFixture(ResourceLocation replacedRecipeId) {
        this.spec = new SpecFixture(Set.of(replacedRecipeId));
    }

    public SpecFixture spec() {
        return spec;
    }

    public record SpecFixture(Set<ResourceLocation> replacedRecipeIds) {}
}
