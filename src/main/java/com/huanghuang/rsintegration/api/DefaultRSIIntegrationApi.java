package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.execution.RecipeExecutionIntegration;
import com.huanghuang.rsintegration.api.execution.ExecutionClaim;
import com.huanghuang.rsintegration.api.execution.ExecutionIntegrationRegistry;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.machine.MachineClaim;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.MachineIntegrationRegistry;
import com.huanghuang.rsintegration.api.recipe.RecipeClaim;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegrationRegistry;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;

@ApiStatus.Internal
public final class DefaultRSIIntegrationApi implements RSIIntegrationApi {
    private final RecipeIntegrationRegistry recipeRegistry = new DefaultRecipeRegistry();
    private final MachineIntegrationRegistry machineRegistry =
            new DefaultMachineRegistry();
    private final ExecutionIntegrationRegistry executionRegistry =
            new DefaultExecutionRegistry();
    private final List<Registered<RecipeIntegration<?>>> recipes = new CopyOnWriteArrayList<>();
    private final List<Registered<MachineIntegration>> machines = new CopyOnWriteArrayList<>();
    private final List<Registered<RecipeExecutionIntegration>> executions =
            new CopyOnWriteArrayList<>();
    private volatile RegistrationPhase phase = RegistrationPhase.CREATED;

    @Override
    public ApiVersion version() {
        return new ApiVersion(API_MAJOR, API_MINOR, 0);
    }

    @Override
    public RegistrationPhase phase() {
        return phase;
    }

    @Override
    public RecipeIntegrationRegistry recipes() {
        return recipeRegistry;
    }

    @Override
    public MachineIntegrationRegistry machines() {
        return machineRegistry;
    }

    @Override
    public ExecutionIntegrationRegistry executions() {
        return executionRegistry;
    }

    @Override
    public RegistrationResult registerRecipe(
            RecipeIntegration<?> integration,
            RegistrationOptions options
    ) {
        return register(RegistrationKind.RECIPE, integration, options);
    }

    @Override
    public RegistrationResult registerMachine(
            MachineIntegration integration,
            RegistrationOptions options
    ) {
        return register(RegistrationKind.MACHINE, integration, options);
    }

    @Override
    public RegistrationResult registerExecution(
            RecipeExecutionIntegration integration,
            RegistrationOptions options
    ) {
        return register(RegistrationKind.EXECUTION, integration, options);
    }

    public synchronized void beginRegistration() {
        if (phase == RegistrationPhase.CREATED) {
            phase = RegistrationPhase.REGISTRATION;
        }
    }

    public synchronized void freezeRegistration() {
        if (phase == RegistrationPhase.REGISTRATION) {
            phase = RegistrationPhase.FROZEN;
        }
    }

    public synchronized void stop() {
        phase = RegistrationPhase.STOPPED;
    }

    public synchronized void resumeAfterStop() {
        if (phase == RegistrationPhase.STOPPED) {
            phase = RegistrationPhase.FROZEN;
        }
    }

    synchronized List<RSIIntegrationServices.RecipeRegistration>
    recipeRegistrations() {
        return recipes.stream()
                .map(entry -> new RSIIntegrationServices.RecipeRegistration(
                        entry.integration(), entry.options()))
                .toList();
    }

    synchronized List<RSIIntegrationServices.MachineRegistration>
    machineRegistrations() {
        return machines.stream()
                .map(entry -> new RSIIntegrationServices.MachineRegistration(
                        entry.integration(), entry.options()))
                .toList();
    }

    private synchronized RegistrationResult register(
            RegistrationKind kind,
            @Nullable Object integration,
            @Nullable RegistrationOptions options
    ) {
        RegistrationPhase currentPhase = phase;
        if (currentPhase == RegistrationPhase.FROZEN) {
            return rejected(RegistrationStatus.FROZEN, currentPhase, null,
                    IntegrationDiagnosticCode.REGISTRATION_FROZEN, kind,
                    "registration_frozen");
        }
        if (currentPhase != RegistrationPhase.REGISTRATION) {
            return rejected(RegistrationStatus.PHASE_INVALID, currentPhase, null,
                    IntegrationDiagnosticCode.REGISTRATION_PHASE_INVALID, kind,
                    "registration_phase");
        }
        if (integration == null) {
            return rejected(RegistrationStatus.REJECTED, currentPhase, null,
                    IntegrationDiagnosticCode.REGISTRATION_INVALID_DECLARATION, kind,
                    "null_integration");
        }
        ResourceLocation id;
        try {
            id = integrationId(kind, integration);
        } catch (RuntimeException exception) {
            return rejected(RegistrationStatus.REJECTED, currentPhase, null,
                    IntegrationDiagnosticCode.REGISTRATION_INVALID_DECLARATION, kind,
                    "invalid_id");
        }
        if (id == null) {
            return rejected(RegistrationStatus.REJECTED, currentPhase, null,
                    IntegrationDiagnosticCode.REGISTRATION_INVALID_DECLARATION, kind,
                    "invalid_id");
        }
        if (kind == RegistrationKind.MACHINE
                && !hasMachineBindings((MachineIntegration) integration)) {
            return rejected(RegistrationStatus.REJECTED, currentPhase, id,
                    IntegrationDiagnosticCode.REGISTRATION_INVALID_DECLARATION,
                    kind, "invalid_machine_binding");
        }
        RegistrationOptions safeOptions = options == null
                ? RegistrationOptions.defaults() : options;
        List<? extends Registered<?>> target = registrations(kind);
        if (target.stream().anyMatch(entry -> entry.id().equals(id))) {
            return rejected(RegistrationStatus.REJECTED, phase, id,
                    IntegrationDiagnosticCode.REGISTRATION_DUPLICATE_ID, kind,
                    "duplicate_id");
        }
        addRegistration(kind, new Registered<>(id, safeOptions, integration));
        return new RegistrationResult(
                RegistrationStatus.ACCEPTED, id, phase, List.of());
    }

    private List<? extends Registered<?>> registrations(RegistrationKind kind) {
        return switch (kind) {
            case RECIPE -> recipes;
            case MACHINE -> machines;
            case EXECUTION -> executions;
        };
    }

    @SuppressWarnings("unchecked")
    private void addRegistration(RegistrationKind kind, Registered<?> registration) {
        switch (kind) {
            case RECIPE -> recipes.add((Registered<RecipeIntegration<?>>) registration);
            case MACHINE -> machines.add((Registered<MachineIntegration>) registration);
            case EXECUTION ->
                    executions.add((Registered<RecipeExecutionIntegration>) registration);
        }
    }

    private static ResourceLocation integrationId(
            RegistrationKind kind, Object integration) {
        return switch (kind) {
            case RECIPE -> ((RecipeIntegration<?>) integration).id();
            case MACHINE -> ((MachineIntegration) integration).id();
            case EXECUTION -> ((RecipeExecutionIntegration) integration).id();
        };
    }

    private static boolean hasMachineBindings(
            MachineIntegration integration
    ) {
        try {
            var bindings = integration.bindings();
            return bindings != null
                    && !bindings.isEmpty()
                    && bindings.stream().allMatch(Objects::nonNull);
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static RegistrationResult rejected(
            RegistrationStatus status,
            RegistrationPhase phase,
            @Nullable ResourceLocation registeredId,
            IntegrationDiagnosticCode code,
            RegistrationKind kind,
            String reason
    ) {
        IntegrationDiagnostic diagnostic = new IntegrationDiagnostic(
                code,
                IntegrationSeverity.ERROR,
                registeredId,
                Map.of("kind", kind.name(), "reason", reason)
        );
        return new RegistrationResult(
                status, registeredId, phase, List.of(diagnostic));
    }

    private record Registered<T>(
            ResourceLocation id,
            RegistrationOptions options,
            T integration
    ) {
        private Registered {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(integration, "integration");
        }
    }

    private final class DefaultRecipeRegistry implements RecipeIntegrationRegistry {
        @Override
        public List<RecipeIntegration<?>> findAll(Recipe<?> recipe) {
            return scanRecipes(recipe).all();
        }

        @Override
        public RecipeClaim claim(Recipe<?> recipe) {
            var selection = scanRecipes(recipe).select(
                    IntegrationDiagnosticCode.RECIPE_CLAIM_AMBIGUOUS, () -> {
                        ResourceLocation recipeId = recipe.getId();
                        return Map.of("recipe", recipeId == null ? "unknown" : recipeId.toString());
                    });
            return new RecipeClaim(selection.winner(), selection.candidates(), selection.diagnostics());
        }

        private RegistryScan<RecipeIntegration<?>> scanRecipes(Recipe<?> recipe) {
            return scan(recipes, integration -> integration.supports(recipe),
                    IntegrationDiagnosticCode.RECIPE_INSPECTION_FAILED);
        }
    }

    private final class DefaultMachineRegistry implements MachineIntegrationRegistry {
        @Override
        public List<MachineIntegration> findAll(BlockEntity blockEntity) {
            return scanMachines(blockEntity).all();
        }

        @Override
        public MachineClaim claim(BlockEntity blockEntity) {
            var selection = scanMachines(blockEntity).select(
                    IntegrationDiagnosticCode.MACHINE_CLAIM_AMBIGUOUS,
                    () -> Map.of("blockEntity", blockEntity.getClass().getName()));
            return new MachineClaim(selection.winner(), selection.candidates(), selection.diagnostics());
        }

        private RegistryScan<MachineIntegration> scanMachines(BlockEntity blockEntity) {
            return scan(machines, integration -> integration.supports(blockEntity),
                    IntegrationDiagnosticCode.MACHINE_UNSUPPORTED);
        }
    }

    private final class DefaultExecutionRegistry
            implements ExecutionIntegrationRegistry {
        @Override
        public List<RecipeExecutionIntegration> findAll(
                RecipeView recipe,
                MachineContext machine
        ) {
            return scanExecutions(recipe, machine).all();
        }

        @Override
        public ExecutionClaim claim(RecipeView recipe, MachineContext machine) {
            var selection = scanExecutions(recipe, machine).select(
                    IntegrationDiagnosticCode.EXECUTION_CLAIM_AMBIGUOUS,
                    () -> Map.of("recipe", recipe.recipeId().toString(),
                            "machine", machine.integrationId().toString()));
            return new ExecutionClaim(selection.winner(), selection.candidates(), selection.diagnostics());
        }

        private RegistryScan<RecipeExecutionIntegration> scanExecutions(
                RecipeView recipe, MachineContext machine) {
            return scan(executions, integration -> integration.supports(recipe, machine),
                    IntegrationDiagnosticCode.EXECUTION_CONTRACT_VIOLATION);
        }
    }

    /** 三种注册查询共用候选隔离和排序；不同配方或机器必须分别匹配，不能复用命中结果。 */
    private static <T> RegistryScan<T> scan(List<Registered<T>> registrations,
                                           Predicate<T> supports,
                                           IntegrationDiagnosticCode failureCode) {
        List<Registered<T>> matches = new ArrayList<>();
        List<IntegrationDiagnostic> diagnostics = new ArrayList<>();
        for (Registered<T> entry : registrations) {
            try {
                if (supports.test(entry.integration())) matches.add(entry);
            } catch (RuntimeException | LinkageError failure) {
                diagnostics.add(failureDiagnostic(failureCode, entry.id(), failure));
            }
        }
        matches.sort(Comparator.comparingInt((Registered<T> entry) -> entry.options().priority()).reversed()
                .thenComparing(entry -> entry.id().toString()));
        return new RegistryScan<>(List.copyOf(matches), List.copyOf(diagnostics));
    }

    private record RegistryScan<T>(List<Registered<T>> matches, List<IntegrationDiagnostic> diagnostics) {
        private List<T> all() {
            return matches.stream().map(Registered::integration).toList();
        }

        private ClaimSelection<T> select(IntegrationDiagnosticCode ambiguousCode,
                                         Supplier<Map<String, String>> context) {
            if (matches.isEmpty()) return new ClaimSelection<>(null, List.of(), diagnostics);
            int highest = matches.get(0).options().priority();
            // scan 已按优先级和 ID 排序，只读取最高优先级的连续前缀，无须再次排序。
            List<T> candidates = matches.stream()
                    .takeWhile(entry -> entry.options().priority() == highest)
                    .map(Registered::integration).toList();
            if (candidates.size() == 1) {
                return new ClaimSelection<>(candidates.get(0), candidates, diagnostics);
            }
            Map<String, String> details = new LinkedHashMap<>(context.get());
            details.put("priority", Integer.toString(highest));
            List<IntegrationDiagnostic> combined = new ArrayList<>(diagnostics);
            combined.add(new IntegrationDiagnostic(ambiguousCode, IntegrationSeverity.ERROR, null, details));
            return new ClaimSelection<>(null, candidates, List.copyOf(combined));
        }
    }

    private record ClaimSelection<T>(@Nullable T winner, List<T> candidates,
                                     List<IntegrationDiagnostic> diagnostics) {}

    private static IntegrationDiagnostic failureDiagnostic(
            IntegrationDiagnosticCode code,
            ResourceLocation integrationId,
            Throwable failure
    ) {
        return new IntegrationDiagnostic(
                code,
                IntegrationSeverity.ERROR,
                integrationId,
                Map.of("failure", failure.getClass().getName())
        );
    }
}
