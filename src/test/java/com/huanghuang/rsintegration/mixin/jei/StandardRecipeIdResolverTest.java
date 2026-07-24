package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.compat.jei.StandardRecipeIdResolver;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StandardRecipeIdResolverTest {

    @Test
    void resolvesThirdPartyGetIdMethod() {
        ResourceLocation id = new ResourceLocation("malum", "spirit_focusing/test");

        assertEquals(id, StandardRecipeIdResolver.resolve(new DirectRecipe(id)));
    }

    @Test
    void resolvesSrgRecipeIdMethod() {
        ResourceLocation id = new ResourceLocation("minecraft", "campfire/test");

        assertEquals(id, StandardRecipeIdResolver.resolve(new SrgRecipe(id)));
    }

    @Test
    void resolvesInheritedNonPublicGetIdMethod() {
        ResourceLocation id = new ResourceLocation("test", "inherited");

        assertEquals(id, StandardRecipeIdResolver.resolve(new InheritedRecipe(id)));
    }

    private record DirectRecipe(ResourceLocation id) {
        public ResourceLocation getId() {
            return id;
        }
    }

    private record SrgRecipe(ResourceLocation id) {
        public ResourceLocation m_6423_() {
            return id;
        }
    }

    private static class RecipeBase {
        private final ResourceLocation id;

        private RecipeBase(ResourceLocation id) {
            this.id = id;
        }

        protected ResourceLocation getId() {
            return id;
        }
    }

    private static final class InheritedRecipe extends RecipeBase {
        private InheritedRecipe(ResourceLocation id) {
            super(id);
        }
    }
}
