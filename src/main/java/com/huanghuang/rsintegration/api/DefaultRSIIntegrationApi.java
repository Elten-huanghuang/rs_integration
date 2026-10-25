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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

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
        if (currentPhase == RegistrationPhase.FROZEN) {
            return rejected(RegistrationStatus.FROZEN, currentPhase, id,
                    IntegrationDiagnosticCode.REGISTRATION_FROZEN, kind,
                    "registration_frozen");
        }
        if (currentPhase != RegistrationPhase.REGISTRATION) {
            return rejected(RegistrationStatus.PHASE_INVALID, currentPhase, id,
                    IntegrationDiagnosticCode.REGISTRATION_PHASE_INVALID, kind,
                    "registration_phase");
        }
        RegistrationOptions safeOptions = options == null
                ? RegistrationOptions.defaults() : options;
        synchronized (this) {
            List<? extends Registered<?>> target = registrations(kind);
            if (target.stream().anyMatch(entry -> entry.id().equals(id))) {
                return rejected(RegistrationStatus.REJECTED, phase, id,
                        IntegrationDiagnosticCode.REGISTRATION_DUPLICATE_ID, kind,
                        "duplicate_id");
            }
            addRegistration(kind, new Registered<>(id, safeOptions, integration));
        }
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
            List<Registered<RecipeIntegration<?>>> matches = new ArrayList<>();
            for (Registered<RecipeIntegration<?>> entry : recipes) {
                if (entry.integration().supports(recipe)) matches.add(entry);
            }
            matches.sort(Comparator
                    .comparingInt((Registered<RecipeIntegration<?>> entry) ->
                            entry.options().priority()).reversed()
                    .thenComparing(entry -> entry.id().toString()));
            return matches.stream().map(Registered::integration).toList();
        }

        @Override
        public RecipeClaim claim(Recipe<?> recipe) {
            List<Registered<RecipeIntegration<?>>> matches = new ArrayList<>();
            for (Registered<RecipeIntegration<?>> entry : recipes) {
                if (entry.integration().supports(recipe)) matches.add(entry);
            }
            if (matches.isEmpty()) {
                return new RecipeClaim(null, List.of(), List.of());
            }
            int highest = matches.stream()
                    .mapToInt(entry -> entry.options().priority())
                    .max().orElseThrow();
            List<Registered<RecipeIntegration<?>>> top = matches.stream()
                    .filter(entry -> entry.options().priority() == highest)
                    .sorted(Comparator.comparing(entry -> entry.id().toString()))
                    .toList();
            List<RecipeIntegration<?>> candidates = top.stream()
                    .map(Registered::integration).toList();
            if (top.size() == 1) {
                return new RecipeClaim(candidates.get(0), candidates, List.of());
            }
            ResourceLocation recipeId = recipe.getId();
            IntegrationDiagnostic diagnostic = new IntegrationDiagnostic(
                    IntegrationDiagnosticCode.RECIPE_CLAIM_AMBIGUOUS,
                    IntegrationSeverity.ERROR,
                    null,
                    Map.of(
                            "recipe", recipeId == null ? "unknown" : recipeId.toString(),
                            "priority", Integer.toString(highest)
                    )
            );
            return new RecipeClaim(null, candidates, List.of(diagnostic));
        }
    }

    private final class DefaultMachineRegistry implements MachineIntegrationRegistry {
        @Override
        public List<MachineIntegration> findAll(BlockEntity blockEntity) {
            return matchingMachines(blockEntity).stream()
                    .map(Registered::integration)
                    .toList();
        }

        @Override
        public MachineClaim claim(BlockEntity blockEntity) {
            List<Registered<MachineIntegration>> matches =
                    matchingMachines(blockEntity);
            if (matches.isEmpty()) {
                return new MachineClaim(null, List.of(), List.of());
            }
            int highest = matches.stream()
                    .mapToInt(entry -> entry.options().priority())
                    .max().orElseThrow();
            List<Registered<MachineIntegration>> top = matches.stream()
                    .filter(entry -> entry.options().priority() == highest)
                    .sorted(Comparator.comparing(entry -> entry.id().toString()))
                    .toList();
            List<MachineIntegration> candidates = top.stream()
                    .map(Registered::integration)
                    .toList();
            if (top.size() == 1) {
                return new MachineClaim(candidates.get(0), candidates, List.of());
            }
            IntegrationDiagnostic diagnostic = new IntegrationDiagnostic(
                    IntegrationDiagnosticCode.MACHINE_CLAIM_AMBIGUOUS,
                    IntegrationSeverity.ERROR,
                    null,
                    Map.of(
                            "blockEntity", blockEntity.getClass().getName(),
                            "priority", Integer.toString(highest)
                    )
            );
            return new MachineClaim(null, candidates, List.of(diagnostic));
        }

        private List<Registered<MachineIntegration>> matchingMachines(
                BlockEntity blockEntity
        ) {
            List<Registered<MachineIntegration>> matches = new ArrayList<>();
            for (Registered<MachineIntegration> entry : machines) {
                if (entry.integration().supports(blockEntity)) matches.add(entry);
            }
            matches.sort(Comparator
                    .comparingInt((Registered<MachineIntegration> entry) ->
                            entry.options().priority()).reversed()
                    .thenComparing(entry -> entry.id().toString()));
            return matches;
        }
    }

    private final class DefaultExecutionRegistry
            implements ExecutionIntegrationRegistry {
        @Override
        public List<RecipeExecutionIntegration> findAll(
                RecipeView recipe,
                MachineContext machine
        ) {
            return matchingExecutions(recipe, machine).stream()
                    .map(Registered::integration)
                    .toList();
        }

        @Override
        public ExecutionClaim claim(RecipeView recipe, MachineContext machine) {
            List<Registered<RecipeExecutionIntegration>> matches =
                    matchingExecutions(recipe, machine);
            if (matches.isEmpty()) {
                return new ExecutionClaim(null, List.of(), List.of());
            }
            int highest = matches.stream()
                    .mapToInt(entry -> entry.options().priority())
                    .max().orElseThrow();
            List<Registered<RecipeExecutionIntegration>> top = matches.stream()
                    .filter(entry -> entry.options().priority() == highest)
                    .sorted(Comparator.comparing(entry -> entry.id().toString()))
                    .toList();
            List<RecipeExecutionIntegration> candidates = top.stream()
                    .map(Registered::integration)
                    .toList();
            if (top.size() == 1) {
                return new ExecutionClaim(candidates.get(0), candidates, List.of());
            }
            IntegrationDiagnostic diagnostic = new IntegrationDiagnostic(
                    IntegrationDiagnosticCode.EXECUTION_CLAIM_AMBIGUOUS,
                    IntegrationSeverity.ERROR,
                    null,
                    Map.of(
                            "recipe", recipe.recipeId().toString(),
                            "machine", machine.integrationId().toString(),
                            "priority", Integer.toString(highest)
                    )
            );
            return new ExecutionClaim(null, candidates, List.of(diagnostic));
        }

        private List<Registered<RecipeExecutionIntegration>> matchingExecutions(
                RecipeView recipe,
                MachineContext machine
        ) {
            List<Registered<RecipeExecutionIntegration>> matches =
                    new ArrayList<>();
            for (Registered<RecipeExecutionIntegration> entry : executions) {
                if (entry.integration().supports(recipe, machine)) {
                    matches.add(entry);
                }
            }
            matches.sort(Comparator
                    .comparingInt((Registered<RecipeExecutionIntegration> entry) ->
                            entry.options().priority()).reversed()
                    .thenComparing(entry -> entry.id().toString()));
            return matches;
        }
    }
}
