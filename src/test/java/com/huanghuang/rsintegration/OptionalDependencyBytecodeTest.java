package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.crafting.plan.PlanWarnings;
import com.huanghuang.rsintegration.mixin.jei.RecipeGuiLayoutsMixin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;

class OptionalDependencyBytecodeTest {
    @Test
    void sharedJeiAndPlanningClassesDoNotLinkBotaniaTypes() throws IOException {
        assertNoBotaniaTypeReference(RecipeGuiLayoutsMixin.class);
        assertNoBotaniaTypeReference(PlanWarnings.class);
    }

    private static void assertNoBotaniaTypeReference(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var input = type.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing class resource " + resource);
            String constantPool = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(constantPool.contains("vazkii/botania"),
                    () -> type.getName() + " directly links optional Botania bytecode");
        }
    }
}
