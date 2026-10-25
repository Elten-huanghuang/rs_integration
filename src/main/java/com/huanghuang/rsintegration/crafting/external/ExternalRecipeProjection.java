package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.ResourceMatchMode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.recipe.ItemAlternative;
import com.huanghuang.rsintegration.api.recipe.ItemIngredientView;
import com.huanghuang.rsintegration.api.recipe.FluidRequirement;
import com.huanghuang.rsintegration.api.recipe.MaterialRequirement;
import com.huanghuang.rsintegration.api.recipe.OutputAmount;
import com.huanghuang.rsintegration.api.recipe.OutputDeclaration;
import com.huanghuang.rsintegration.api.recipe.OutputKind;
import com.huanghuang.rsintegration.api.recipe.OutputProbabilityMode;
import com.huanghuang.rsintegration.api.recipe.RecipeContext;
import com.huanghuang.rsintegration.api.recipe.RecipeInspectionResult;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption.Kind;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 将公共 RecipeView 无损投影为内部单次操作输入/输出。
 *
 * <p>当前适配器只接受能完整落入内部物品模型的声明。超出物品槽位可表达
 * 范围的配方会返回带稳定诊断的拒绝结果，不能静默截断数量。</p>
 */
final class ExternalRecipeProjection {
    private ExternalRecipeProjection() {}

    record Result(
            @Nullable RecipeView view,
            List<IntegrationDiagnostic> diagnostics,
            long revision
    ) {
        Result {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        boolean available() {
            return view != null;
        }

        static Result rejected(
                long revision,
                IntegrationDiagnosticCode code,
                ResourceLocation integrationId,
                String reason
        ) {
            return new Result(
                    null,
                    List.of(new IntegrationDiagnostic(
                            code,
                            IntegrationSeverity.ERROR,
                            integrationId,
                            Map.of("reason", reason)
                    )),
                    revision
            );
        }
    }

    static Result inspect(
            RecipeIntegration<?> integration,
            Recipe<?> recipe,
            RegistryAccess registries
    ) {
        Objects.requireNonNull(integration, "integration");
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(registries, "registries");
        long revision = CraftPlanningRevision.current();
        try {
            RecipeInspectionResult result = inspectTyped(
                    integration, recipe, registries, revision);
            if (result.view() == null) {
                return new Result(null, result.diagnostics(), revision);
            }
            List<IntegrationDiagnostic> validation =
                    validateForItemProjection(result.view(), integration.id());
            return validation.isEmpty()
                    ? new Result(result.view(), result.diagnostics(), revision)
                    : new Result(null, validation, revision);
        } catch (RuntimeException | LinkageError exception) {
            return Result.rejected(
                    revision,
                    IntegrationDiagnosticCode.RECIPE_INSPECTION_FAILED,
                    integration.id(),
                    exception.getClass().getName()
            );
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RecipeInspectionResult inspectTyped(
            RecipeIntegration<?> integration,
            Recipe<?> recipe,
            RegistryAccess registries,
            long revision
    ) {
        RecipeIntegration typed = integration;
        return typed.inspect(
                new RecipeContext(integration.id(), registries, revision),
                recipe
        );
    }

    static List<IngredientSpec> ingredients(RecipeView view) {
        List<IngredientSpec> result = new ArrayList<>(view.materials().size());
        for (MaterialRequirement requirement : view.materials()) {
            int amount = toIntAmount(
                    requirement.amount(), "material " + requirement.entryId());
            result.add(new IngredientSpec(
                    ingredient(requirement.ingredient()),
                    amount,
                    consumption(requirement.consumption())
            ));
        }
        return List.copyOf(result);
    }

    static List<ItemStack> exactSecondaryOutputs(RecipeView view) {
        OutputDeclaration primary = primaryOutput(view);
        List<ItemStack> outputs = new ArrayList<>();
        for (OutputDeclaration output : view.outputs()) {
            if (output == primary || !isExactDeterministicItem(output)) continue;
            outputs.add(toStack(output.resource(), output.amount().max()));
        }
        return List.copyOf(outputs);
    }

    @Nullable
    static OutputDeclaration primaryOutput(RecipeView view) {
        return view.outputs().stream()
                .filter(output -> output.outputKind()
                        == OutputKind.PRIMARY)
                .findFirst()
                .orElse(null);
    }

    static ItemStack primaryStack(RecipeView view) {
        OutputDeclaration primary = primaryOutput(view);
        return primary == null || !isItem(primary.resource())
                ? ItemStack.EMPTY
                : toStack(primary.resource(), primary.amount().min());
    }

    static boolean deterministicPrimary(RecipeView view) {
        OutputDeclaration primary = primaryOutput(view);
        return primary != null
                && primary.probability().mode() == OutputProbabilityMode.GUARANTEED
                && !primary.dynamicIdentity()
                && primary.amount().min() == primary.amount().max();
    }

    static boolean runtimeDependentPrimary(RecipeView view) {
        OutputDeclaration primary = primaryOutput(view);
        return primary != null && primary.dynamicIdentity();
    }

    static boolean targetProducing(RecipeView view) {
        return view.outputs().stream().anyMatch(OutputDeclaration::targetProducing);
    }

    static Ingredient ingredient(ItemIngredientView view) {
        List<ItemStack> templates = view.alternatives().stream()
                .map(ItemAlternative::resource)
                .map(resource -> toStack(
                        resource,
                        view.matchMode() == ResourceMatchMode.ANY_IDENTITY
                                ? 0L : 1L))
                .toList();
        if (view.matchMode() == ResourceMatchMode.ANY_IDENTITY) {
            return Ingredient.of(templates.stream());
        }
        Map<String, ItemStack> unique = new LinkedHashMap<>();
        for (ItemAlternative alternative : view.alternatives()) {
            ItemStack template = toStack(alternative.resource(), 1L);
            unique.putIfAbsent(template.getItem().toString()
                    + "|" + template.getTag(), template);
        }
        if (view.matchMode() == ResourceMatchMode.EXACT_IDENTITY) {
            return exactIngredient(unique.values().stream());
        }
        return partialIngredient(unique.values().stream());
    }

    static ItemStack toStack(IntegrationResourceKey resource, long count) {
        if (!isItem(resource)) {
            throw new IllegalArgumentException("resource is not an item");
        }
        Item item = BuiltInRegistries.ITEM.get(resource.id());
        if (item == Items.AIR) {
            throw new IllegalArgumentException(
                    "unknown item: " + resource.id());
        }
        int stackCount = count <= 0L
                ? 1
                : (int) Math.min(Integer.MAX_VALUE, count);
        ItemStack stack = new ItemStack(item, stackCount);
        CompoundTag identity = resource.identity();
        if (!identity.isEmpty()) stack.setTag(identity);
        return stack;
    }

    private static Ingredient partialIngredient(Stream<ItemStack> templates) {
        List<ItemStack> copies = templates
                .map(stack -> stack.copyWithCount(1))
                .toList();
        return new Ingredient(copies.stream().map(Ingredient.ItemValue::new)) {
            @Override
            public boolean test(@Nullable ItemStack candidate) {
                if (candidate == null || candidate.isEmpty()) return false;
                for (ItemStack template : copies) {
                    if (candidate.is(template.getItem())
                            && contains(candidate.getTag(), template.getTag())) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean isSimple() {
                return false;
            }
        };
    }

    private static Ingredient exactIngredient(Stream<ItemStack> templates) {
        List<ItemStack> copies = templates
                .map(stack -> stack.copyWithCount(1))
                .toList();
        return new Ingredient(copies.stream().map(Ingredient.ItemValue::new)) {
            @Override
            public boolean test(@Nullable ItemStack candidate) {
                if (candidate == null || candidate.isEmpty()) return false;
                return copies.stream().anyMatch(
                        template -> ItemStack.isSameItemSameTags(candidate, template));
            }

            @Override
            public boolean isSimple() {
                return false;
            }
        };
    }

    private static boolean contains(
            @Nullable CompoundTag actual,
            @Nullable CompoundTag required
    ) {
        if (required == null || required.isEmpty()) return true;
        if (actual == null) return false;
        for (String key : required.getAllKeys()) {
            Tag expected = required.get(key);
            Tag found = actual.get(key);
            if (expected == null || found == null) return false;
            if (expected instanceof CompoundTag expectedCompound) {
                if (!(found instanceof CompoundTag foundCompound)
                        || !contains(foundCompound, expectedCompound)) {
                    return false;
                }
            } else if (expected instanceof ListTag expectedList) {
                if (!(found instanceof ListTag foundList)
                        || !listContains(foundList, expectedList)) {
                    return false;
                }
            } else if (!expected.equals(found)) {
                return false;
            }
        }
        return true;
    }

    static boolean compoundContains(
            @Nullable CompoundTag actual,
            @Nullable CompoundTag required
    ) {
        return contains(actual, required);
    }

    private static boolean listContains(ListTag actual, ListTag required) {
        if (actual.size() != required.size()) return false;
        for (int index = 0; index < required.size(); index++) {
            Tag expected = required.get(index);
            Tag found = actual.get(index);
            if (expected instanceof CompoundTag expectedCompound) {
                if (!(found instanceof CompoundTag foundCompound)
                        || !contains(foundCompound, expectedCompound)) {
                    return false;
                }
            } else if (!expected.equals(found)) {
                return false;
            }
        }
        return true;
    }

    private static InputConsumption consumption(
            com.huanghuang.rsintegration.api.recipe.InputConsumption consumption
    ) {
        ResourceLocation linked = consumption.linkedOutputPortId();
        Kind kind = switch (consumption.kind()) {
            case CONSUMED -> Kind.CONSUMED;
            case FUEL -> Kind.FUEL;
            case CATALYST -> Kind.CATALYST;
            case TRANSFORMED -> Kind.TRANSFORMED;
            case CONTAINER_RETURNED -> Kind.CONTAINER_RETURNED;
            case DURABILITY -> Kind.DURABILITY;
        };
        long units = switch (consumption.kind()) {
            case DURABILITY -> consumption.damagePerUse();
            case FUEL -> consumption.fuelBurnTicksPerOperation();
            default -> 0L;
        };
        return new InputConsumption(kind, units, linked);
    }

    private static List<IntegrationDiagnostic> validateForItemProjection(
            RecipeView view,
            ResourceLocation integrationId
    ) {
        List<IntegrationDiagnostic> diagnostics = new ArrayList<>();
        for (MaterialRequirement material : view.materials()) {
            try {
                toIntAmount(material.amount(), "material " + material.entryId());
                ingredient(material.ingredient());
            } catch (IllegalArgumentException exception) {
                diagnostics.add(diagnostic(
                        IntegrationDiagnosticCode.MATERIAL_DECLARATION_INVALID,
                        integrationId,
                        exception.getMessage()
                ));
            }
        }
        for (FluidRequirement fluid : view.fluids()) {
            try {
                if (fluid.amount() <= 0L) {
                    throw new IllegalArgumentException(
                            "fluid amount must be positive");
                }
                for (IntegrationResourceKey alternative
                        : fluid.fluid().alternatives()) {
                    if (alternative.kind()
                            != IntegrationResourceKey.ResourceKind.FLUID) {
                        throw new IllegalArgumentException(
                                "fluid alternative has non-fluid kind");
                    }
                }
            } catch (IllegalArgumentException exception) {
                diagnostics.add(diagnostic(
                        IntegrationDiagnosticCode.MATERIAL_DECLARATION_INVALID,
                        integrationId,
                        exception.getMessage()
                ));
            }
        }
        for (OutputDeclaration output : view.outputs()) {
            if (!isItem(output.resource())) {
                diagnostics.add(diagnostic(
                        IntegrationDiagnosticCode.OUTPUT_DECLARATION_INVALID,
                        integrationId,
                        "non_item_output:" + output.portId()
                ));
                continue;
            }
            if (output.amount().max() > Integer.MAX_VALUE) {
                diagnostics.add(diagnostic(
                        IntegrationDiagnosticCode.QUANTITY_OVERFLOW,
                        integrationId,
                        "output_quantity:" + output.portId()
                ));
            }
        }
        return diagnostics;
    }

    private static boolean isExactDeterministicItem(OutputDeclaration output) {
        OutputAmount amount = output.amount();
        return isItem(output.resource())
                && amount.min() == amount.max()
                && amount.max() <= Integer.MAX_VALUE
                && output.probability().mode() == OutputProbabilityMode.GUARANTEED
                && !output.dynamicIdentity();
    }

    private static boolean isItem(IntegrationResourceKey resource) {
        return resource.kind() == IntegrationResourceKey.ResourceKind.ITEM;
    }

    private static int toIntAmount(long amount, String label) {
        if (amount < 1L || amount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    label + " amount is outside int slot range: " + amount);
        }
        return (int) amount;
    }

    private static IntegrationDiagnostic diagnostic(
            IntegrationDiagnosticCode code,
            ResourceLocation integrationId,
            String reason
    ) {
        return new IntegrationDiagnostic(
                code,
                IntegrationSeverity.ERROR,
                integrationId,
                Map.of("reason", reason == null ? "invalid" : reason)
        );
    }
}
