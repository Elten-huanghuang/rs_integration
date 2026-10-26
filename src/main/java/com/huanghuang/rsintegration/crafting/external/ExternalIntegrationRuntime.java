package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.RSIIntegrationApi;
import com.huanghuang.rsintegration.api.RSIIntegrationServices;
import com.huanghuang.rsintegration.api.machine.MachineBinding;
import com.huanghuang.rsintegration.api.machine.PlannedConcurrency;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 将冻结后的公共注册安装到 RSI 内部处理器与绑定索引。
 *
 * <p>所有公共配方共用一个内部逻辑 ModType；具体 process、机器和
 * execution 仍由公共 claim 与 descriptor 决定。</p>
 */
public final class ExternalIntegrationRuntime {
    private static volatile boolean initialized;
    private static volatile List<RSIIntegrationServices.MachineRegistration>
            installedMachines = List.of();
    private static volatile List<RSIIntegrationServices.RecipeRegistration>
            installedRecipes = List.of();

    private ExternalIntegrationRuntime() {}

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        RSIIntegrationApi api = RSIIntegrationApi.get().orElse(null);
        if (api == null) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-API] integration runtime initialized without API entry");
            return;
        }
        int recipeHandlers = 0;
        List<RSIIntegrationServices.RecipeRegistration> recipeRegistrations =
                RSIIntegrationServices.recipeRegistrations().stream()
                        .sorted(Comparator
                                .comparingInt((
                                        RSIIntegrationServices.RecipeRegistration registration) ->
                                        registration.options().priority())
                                .reversed()
                                .thenComparing(registration ->
                                        registration.integration().id().toString()))
                        .toList();
        installedRecipes = recipeRegistrations;
        for (RSIIntegrationServices.RecipeRegistration registration
                : recipeRegistrations) {
            ModRecipeHandlers.register(new ExternalRecipeHandler(
                    registration.integration(),
                    ModType.EXTERNAL_INTEGRATION,
                    registration.options().priority(),
                    recipe -> api.recipes().claim(recipe).winner()
                            == registration.integration()
            ));
            recipeHandlers++;
        }

        List<RSIIntegrationServices.MachineRegistration> machineRegistrations =
                RSIIntegrationServices.machineRegistrations().stream()
                        .sorted(Comparator
                                .comparingInt((
                                        RSIIntegrationServices.MachineRegistration registration) ->
                                        registration.options().priority())
                                .reversed()
                                .thenComparing(registration ->
                                        registration.integration().id().toString()))
                        .toList();
        installedMachines = machineRegistrations;
        ExternalMachineBindingIndex.install(machineRegistrations);
        int machineTargets = 0;
        int boundBlockIds = 0;
        for (RSIIntegrationServices.MachineRegistration registration
                : machineRegistrations) {
            ResourceLocation id = registration.integration().id();
            LinkedHashSet<String> classNames = new LinkedHashSet<>();
            LinkedHashSet<String> blockIds = new LinkedHashSet<>();
            boolean supportsGui = false;
            for (MachineBinding binding
                    : registration.integration().bindings()) {
                binding.blockClassNames().forEach(classNames::add);
                binding.blockIds().stream()
                        .map(ResourceLocation::toString)
                        .forEach(blockIds::add);
                supportsGui |= binding.supportsGui();
            }
            BindingEventHandler.registerTarget(
                    new BindingEventHandler.MachineBindingTarget(
                            id.getNamespace(),
                            ModType.EXTERNAL_INTEGRATION,
                            RSIntegrationConfig.ENABLE_BINDING,
                            new ArrayList<>(classNames),
                            new ArrayList<>(blockIds),
                            ModType.EXTERNAL_INTEGRATION.id(),
                            supportsGui,
                            registration.options().priority()
                    )
            );
            machineTargets++;
            boundBlockIds += blockIds.size() + classNames.size();
        }
        RSIntegrationMod.LOGGER.info(
                "[RSI-API] installed {} recipe claims and {} machine bindings ({} block declarations)",
                recipeHandlers, machineTargets, boundBlockIds);
    }

    public static boolean isExternalType(ModType type) {
        return type == ModType.EXTERNAL_INTEGRATION;
    }

    @Nullable
    public static Recipe<?> resolveJeiRecipe(
            @Nullable Object jeiRecipe,
            @Nullable ItemStack displayed,
            @Nullable RegistryAccess registries
    ) {
        if (jeiRecipe == null || registries == null) return null;
        for (RSIIntegrationServices.RecipeRegistration registration
                : installedRecipes) {
            try {
                var resolved = registration.integration()
                        .resolveJeiRecipe(
                                jeiRecipe,
                                displayed == null
                                        ? ItemStack.EMPTY
                                        : displayed,
                                registries
                        );
                if (resolved.isPresent()) {
                    return resolved.get();
                }
            } catch (RuntimeException | LinkageError ignored) {
                // A broken optional resolver must not block the remaining API
                // or the internal JEI compatibility path.
            }
        }
        return null;
    }

    /**
     * 解析公共配方在具体机器绑定前可使用的保守并发合同。
     *
     * <p>process 下所有已声明机器必须存在。多个机器声明会取交集；
     * 任何无法证明相同的所有权、清理或材料语义都会降级为 exclusive。</p>
     */
    @Nullable
    public static BatchConcurrencyCapabilities planningConcurrency(
            Recipe<?> recipe,
            RegistryAccess registries
    ) {
        if (recipe == null || registries == null) return null;
        var handler = ModRecipeHandlers.claimFor(recipe).winner();
        if (!(handler instanceof ExternalRecipeHandler external)) {
            return null;
        }
        var view = external.recipeView(recipe, registries);
        if (view == null) return null;
        return resolvePlanningConcurrency(
                view.processId(),
                installedMachines
        );
    }

    @Nullable
    static BatchConcurrencyCapabilities resolvePlanningConcurrency(
            ResourceLocation process,
            List<RSIIntegrationServices.MachineRegistration> registrations
    ) {
        if (process == null || registrations == null) return null;
        List<PlannedConcurrency> profiles = new ArrayList<>();
        boolean supported = false;
        for (RSIIntegrationServices.MachineRegistration registration
                : registrations) {
            try {
                boolean acceptsProcess = registration.integration()
                        .bindings()
                        .stream()
                        .filter(binding -> binding != null)
                        .anyMatch(binding ->
                                binding.acceptedProcesses().contains(process));
                if (!acceptsProcess) continue;
                supported = true;
                Map<ResourceLocation, PlannedConcurrency> declared =
                        registration.integration().planningConcurrency();
                PlannedConcurrency profile = declared == null
                        ? null : declared.get(process);
                if (profile == null) {
                    // 只要有支持该 process 的集成未声明，就不能证明并行安全。
                    return null;
                }
                profiles.add(profile);
            } catch (RuntimeException | LinkageError failure) {
                return null;
            }
        }
        return supported
                ? mergePlanningConcurrency(profiles)
                : null;
    }

    @Nullable
    static BatchConcurrencyCapabilities mergePlanningConcurrency(
            List<PlannedConcurrency> profiles
    ) {
        if (profiles == null || profiles.isEmpty()) return null;
        BatchConcurrencyCapabilities first =
                toBatch(profiles.get(0));
        BatchConcurrencyCapabilities.MaterialOwnership materials =
                first.materials();
        BatchConcurrencyCapabilities.OutputOwnership output =
                first.outputOwnership();
        BatchConcurrencyCapabilities.CleanupContract cleanup =
                first.cleanup();
        BatchConcurrencyCapabilities.PreparationContract preparation =
                first.preparation();
        BatchConcurrencyCapabilities.SideEffects sideEffects =
                first.sideEffects();
        LinkedHashSet<BlockPos> support =
                new LinkedHashSet<>(first.supportOffsets());
        for (int index = 1; index < profiles.size(); index++) {
            BatchConcurrencyCapabilities next =
                    toBatch(profiles.get(index));
            if (materials != next.materials()) {
                materials =
                        BatchConcurrencyCapabilities.MaterialOwnership.UNKNOWN;
            }
            if (output != next.outputOwnership()) {
                output =
                        BatchConcurrencyCapabilities.OutputOwnership.AMBIGUOUS;
            }
            if (cleanup != next.cleanup()) {
                cleanup =
                        BatchConcurrencyCapabilities.CleanupContract.UNKNOWN;
            }
            preparation = stricterPreparation(
                    preparation, next.preparation());
            sideEffects = riskierSideEffects(
                    sideEffects, next.sideEffects());
            support.addAll(next.supportOffsets());
        }
        return new BatchConcurrencyCapabilities(
                materials,
                output,
                cleanup,
                sideEffects,
                preparation,
                List.copyOf(support)
        );
    }

    private static BatchConcurrencyCapabilities toBatch(
            PlannedConcurrency profile
    ) {
        return new BatchConcurrencyCapabilities(
                BatchConcurrencyCapabilities.MaterialOwnership.valueOf(
                        profile.materials().name()),
                BatchConcurrencyCapabilities.OutputOwnership.valueOf(
                        profile.outputOwnership().name()),
                BatchConcurrencyCapabilities.CleanupContract.valueOf(
                        profile.cleanup().name()),
                BatchConcurrencyCapabilities.SideEffects.valueOf(
                        profile.sideEffects().name()),
                BatchConcurrencyCapabilities.PreparationContract.valueOf(
                        profile.preparation().name()),
                profile.supportOffsets()
        );
    }

    private static BatchConcurrencyCapabilities.PreparationContract
    stricterPreparation(
            BatchConcurrencyCapabilities.PreparationContract left,
            BatchConcurrencyCapabilities.PreparationContract right
    ) {
        if (left == BatchConcurrencyCapabilities.PreparationContract.UNKNOWN
                || right == BatchConcurrencyCapabilities.PreparationContract.UNKNOWN) {
            return BatchConcurrencyCapabilities.PreparationContract.UNKNOWN;
        }
        return BatchConcurrencyCapabilities.PreparationContract.RETRY_SAFE;
    }

    private static BatchConcurrencyCapabilities.SideEffects
    riskierSideEffects(
            BatchConcurrencyCapabilities.SideEffects left,
            BatchConcurrencyCapabilities.SideEffects right
    ) {
        return sideEffectRisk(left) >= sideEffectRisk(right)
                ? left : right;
    }

    private static int sideEffectRisk(
            BatchConcurrencyCapabilities.SideEffects sideEffects
    ) {
        return switch (sideEffects) {
            case NONE -> 0;
            case MACHINE_LOCAL -> 1;
            case LOCAL_WORLD_ITEMS -> 2;
            case INFER -> 3;
            case ADJACENT_MACHINE -> 4;
            case PLAYER_TRANSFORM -> 5;
            case WORLD_GLOBAL -> 6;
            case UNKNOWN -> 7;
        };
    }

    @Nullable
    public static ResourceLocation recipeProcessId(
            Recipe<?> recipe,
            RegistryAccess registries
    ) {
        var handler = ModRecipeHandlers.claimFor(recipe).winner();
        if (!(handler instanceof ExternalRecipeHandler external)) {
            return null;
        }
        var view = external.recipeView(recipe, registries);
        return view == null ? null : view.processId();
    }
}
