package com.example.rsiintegration.examplemod;

import com.huanghuang.rsintegration.api.RSIIntegrationRegistrationEvent;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.execution.ExecutionPlan;
import com.huanghuang.rsintegration.api.execution.ExecutionPlanResult;
import com.huanghuang.rsintegration.api.execution.ExecutionRequestContext;
import com.huanghuang.rsintegration.api.execution.ExecutionResult;
import com.huanghuang.rsintegration.api.execution.OperationContext;
import com.huanghuang.rsintegration.api.execution.RecipeExecutionIntegration;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.MachineDescriptorResult;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeContext;
import com.huanghuang.rsintegration.api.recipe.RecipeInspectionResult;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

/**
 * 独立第三方示例模组。
 *
 * <p>示例只依赖 RSI 公共 API、Minecraft 和 Forge 类型，不引用内部
 * crafting、delegate、计划图或网络实现。</p>
 */
@Mod(RSIIntegrationExampleMod.MOD_ID)
public final class RSIIntegrationExampleMod {
    static final String MOD_ID = "rsi_api_example";
    private static final String EXAMPLE_PACKAGE =
            "com.example.rsiintegration.examplemod.";

    public RSIIntegrationExampleMod() {
        MinecraftForge.EVENT_BUS.addListener(
                this::registerIntegrations);
    }

    private void registerIntegrations(
            RSIIntegrationRegistrationEvent event
    ) {
        event.api().registerRecipe(new ExampleRecipeIntegration());
        event.api().registerMachine(new ExampleMachineIntegration());
        event.api().registerExecution(
                new ExampleExecutionIntegration());
    }

    private static boolean isExampleRecipe(Recipe<?> recipe) {
        return recipe != null
                && recipe.getClass().getName()
                .startsWith(EXAMPLE_PACKAGE);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    private static IntegrationDiagnostic diagnostic(
            String reason
    ) {
        return new IntegrationDiagnostic(
                IntegrationDiagnosticCode.INTEGRATION_REPORTED_FAILURE,
                IntegrationSeverity.ERROR,
                id("example"),
                Map.of("reason", reason)
        );
    }

    private static final class ExampleRecipeIntegration
            implements RecipeIntegration<Recipe<?>> {
        @Override
        public ResourceLocation id() {
            return RSIIntegrationExampleMod.id("recipe");
        }

        @Override
        public boolean supports(Recipe<?> recipe) {
            return isExampleRecipe(recipe);
        }

        @Override
        public RecipeInspectionResult inspect(
                RecipeContext context,
                Recipe<?> recipe
        ) {
            return RecipeInspectionResult.rejected(
                    diagnostic("replace with RecipeView projection"));
        }
    }

    private static final class ExampleMachineIntegration
            implements MachineIntegration {
        @Override
        public ResourceLocation id() {
            return RSIIntegrationExampleMod.id("machine");
        }

        @Override
        public boolean supports(BlockEntity blockEntity) {
            return blockEntity != null
                    && blockEntity.getClass().getName()
                    .startsWith(EXAMPLE_PACKAGE);
        }

        @Override
        public MachineDescriptorResult describe(
                MachineContext context,
                BlockEntity blockEntity
        ) {
            return MachineDescriptorResult.rejected(
                    diagnostic("replace with explicit machine ports"));
        }
    }

    private static final class ExampleExecutionIntegration
            implements RecipeExecutionIntegration {
        @Override
        public ResourceLocation id() {
            return RSIIntegrationExampleMod.id("execution");
        }

        @Override
        public boolean supports(
                RecipeView recipe,
                MachineContext machine
        ) {
            return recipe != null && machine != null;
        }

        @Override
        public ExecutionPlanResult createPlan(
                ExecutionRequestContext context
        ) {
            return ExecutionPlanResult.rejected(
                    diagnostic("replace with resolved I/O routes"));
        }

        @Override
        public ExecutionResult start(
                OperationContext context,
                ExecutionPlan plan
        ) {
            return ExecutionResult.rejected(
                    diagnostic("replace with real machine execution"));
        }
    }
}
