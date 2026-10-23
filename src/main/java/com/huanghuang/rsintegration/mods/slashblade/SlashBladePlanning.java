package com.huanghuang.rsintegration.mods.slashblade;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** 拔刀剑条件的纯值表示；只投影已确认的刀名与数值下限，不保留第三方对象。 */
public final class SlashBladePlanning {
    private static final Set<String> SUPPORTED_FIELDS = Set.of(
            "name", "kill", "proud_soul", "refine", "enchantments", "sword_type");

    private SlashBladePlanning() {}

    @Nullable
    public static CompoundTag requirement(Ingredient ingredient) {
        if (!SlashBladeRecipeHandler.isSlashBladeIngredient(ingredient)) return null;
        try {
            JsonObject json = ingredient.toJson().getAsJsonObject();
            JsonObject request = json.getAsJsonObject("request");
            if (request == null || !SUPPORTED_FIELDS.containsAll(request.keySet())) return null;
            // 附魔合并受兼容性、等级上限影响，保留原生解析。
            if (request.has("enchantments") && (!request.get("enchantments").isJsonArray()
                    || !request.getAsJsonArray("enchantments").isEmpty())) return null;
            String name = request.has("name") ? request.get("name").getAsString() : "slashblade:none";
            ResourceLocation id = ResourceLocation.tryParse(name);
            if (id == null) return null;
            CompoundTag state = new CompoundTag();
            // Resharped 的 none 表示无名刀，并非接受任意刀名。
            state.putString("translationKey", "slashblade:none".equals(name)
                    ? "" : Util.makeDescriptionId("item", id));
            for (String field : List.of("kill", "proud_soul", "refine")) {
                int value = request.has(field) ? request.get(field).getAsInt() : 0;
                if (value < 0) return null;
                state.putInt(stateField(field), value);
            }
            if (request.has("sword_type")) {
                for (JsonElement value : request.getAsJsonArray("sword_type")) {
                    switch (value.getAsString()) {
                        case "broken" -> state.putBoolean("isBroken", true);
                        case "sealed" -> state.putBoolean("isSealed", true);
                        case "fierceredge" -> state.putInt("killCount", Math.max(1000, state.getInt("killCount")));
                        case "souleater" -> state.putInt("proudSoul", Math.max(10000, state.getInt("proudSoul")));
                        default -> { return null; }
                    }
                }
            }
            CompoundTag result = new CompoundTag();
            result.put("bladeState", state);
            return result;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static String stateField(String field) {
        return switch (field) {
            case "kill" -> "killCount";
            case "proud_soul" -> "proudSoul";
            case "refine" -> "RepairCounter";
            default -> throw new IllegalArgumentException(field);
        };
    }

    /** 只读 NBT；额外 UUID、模型、耐久等状态不参与条件相等。 */
    public static boolean matches(@Nullable CompoundTag requirement, @Nullable CompoundTag actual) {
        if (requirement == null || actual == null
                || !requirement.contains("bladeState", Tag.TAG_COMPOUND)
                || !actual.contains("bladeState", Tag.TAG_COMPOUND)) return false;
        CompoundTag expected = requirement.getCompound("bladeState");
        CompoundTag state = actual.getCompound("bladeState");
        return expected.getString("translationKey").equals(state.getString("translationKey"))
                && state.getInt("killCount") >= expected.getInt("killCount")
                && state.getInt("proudSoul") >= expected.getInt("proudSoul")
                && state.getInt("RepairCounter") >= expected.getInt("RepairCounter")
                && (!expected.getBoolean("isBroken")
                    || state.getBoolean("isBroken") && !actual.getBoolean("Unbreakable"))
                && (!expected.getBoolean("isSealed") || state.getBoolean("isSealed"));
    }

    /**
     * 合成会累加杀敌数和魂。精炼有累加/取最大两种配置，这里取两种配置都保证的下限。
     * 只有满足全部输入条件后这些下限才成立；不会给库存或实际物品补数值。
     */
    @Nullable
    public static ItemStack minimumOutput(ItemStack declared, List<IngredientSpec> specs) {
        if (declared.isEmpty() || declared.getTag() == null
                || !declared.getTag().contains("bladeState", Tag.TAG_COMPOUND)) return null;
        ItemStack result = declared.copy();
        CompoundTag state = result.getTag().getCompound("bladeState");
        long kills = state.getInt("killCount");
        long souls = state.getInt("proudSoul");
        int refine = state.getInt("RepairCounter");
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty() || spec.role() != DemandRole.CONSUMED
                    || !SlashBladeRecipeHandler.isSlashBladeIngredient(spec.ingredient())) continue;
            CompoundTag input = requirement(spec.ingredient());
            if (input == null) return null;
            CompoundTag required = input.getCompound("bladeState");
            kills += (long) required.getInt("killCount") * spec.count();
            souls += (long) required.getInt("proudSoul") * spec.count();
            refine = Math.max(refine, required.getInt("RepairCounter"));
            if (kills > Integer.MAX_VALUE || souls > Integer.MAX_VALUE) return null;
        }
        state.putInt("killCount", (int) kills);
        state.putInt("proudSoul", (int) souls);
        state.putInt("RepairCounter", refine);
        return result;
    }

    /** 兼容规划使用与纯图相同的反传门槛，无法证明的条件仍交给原生匹配拒绝。 */
    @Nullable
    public static ItemStack outputForDemand(ItemStack declared, List<IngredientSpec> specs, Ingredient demand) {
        CompoundTag required = requirement(demand);
        if (required == null) return minimumOutput(declared, specs);
        boolean sameItem = Arrays.stream(demand.getItems())
                .anyMatch(stack -> !stack.isEmpty() && stack.getItem() == declared.getItem());
        if (!sameItem) return minimumOutput(declared, specs);
        ItemStack wanted = declared.copy();
        wanted.setTag(required);
        return minimumOutput(declared, requireOutput(specs, declared, wanted));
    }

    /** 保留合成网格空位，让预览显示的条件与实际取料一致。 */
    public static List<Ingredient> requireOutputIngredients(List<Ingredient> ingredients,
                                                           ItemStack declared, ItemStack wanted) {
        List<IngredientSpec> specs = ingredients.stream().map(ingredient -> new IngredientSpec(ingredient, 1)).toList();
        List<IngredientSpec> required = requireOutput(specs, declared, wanted);
        return required == specs ? ingredients : required.stream().map(IngredientSpec::ingredient).toList();
    }

    @Nullable
    public static CompoundTag raiseInput(CompoundTag input, CompoundTag minimum, CompoundTag wanted) {
        CompoundTag outputState = minimum.getCompound("bladeState");
        CompoundTag wantedState = wanted.getCompound("bladeState");
        if (!outputState.getString("translationKey").equals(wantedState.getString("translationKey"))) return null;
        CompoundTag raised = input.copy();
        CompoundTag inputState = raised.getCompound("bladeState");
        for (String field : List.of("killCount", "proudSoul", "RepairCounter")) {
            int needed = wantedState.getInt(field);
            int guaranteed = outputState.getInt(field);
            if (needed <= guaranteed) continue;
            long value = "RepairCounter".equals(field) ? needed
                    : (long) inputState.getInt(field) + needed - guaranteed;
            if (value > Integer.MAX_VALUE) return null;
            inputState.putInt(field, (int) value);
        }
        return raised;
    }

    /** 执行预留沿用规划反传的门槛，不能在同名库存里误拿低数值刀。 */
    public static List<IngredientSpec> requireOutput(List<IngredientSpec> specs,
                                                     ItemStack declared, ItemStack wanted) {
        if (specs == null || wanted == null || wanted.isEmpty() || wanted.getTag() == null
                || declared.isEmpty() || declared.getItem() != wanted.getItem()) return specs;
        ItemStack minimum = minimumOutput(declared, specs);
        if (minimum == null || matches(wanted.getTag(), minimum.getTag())) return specs;
        int bladeInput = -1;
        for (int i = 0; i < specs.size(); i++) {
            IngredientSpec spec = specs.get(i);
            if (!SlashBladeRecipeHandler.isSlashBladeIngredient(spec.ingredient())) continue;
            if (bladeInput >= 0 || spec.count() != 1 || spec.role() != DemandRole.CONSUMED) return specs;
            bladeInput = i;
        }
        if (bladeInput < 0) return specs;
        IngredientSpec original = specs.get(bladeInput);
        CompoundTag input = requirement(original.ingredient());
        CompoundTag raised = input == null ? null : raiseInput(input, minimum.getTag(), wanted.getTag());
        if (raised == null) return specs;
        List<IngredientSpec> result = new ArrayList<>(specs);
        result.set(bladeInput, new IngredientSpec(new RequiredStateIngredient(original.ingredient(), raised),
                original.count(), original.role()));
        return result;
    }

    private static final class RequiredStateIngredient extends Ingredient {
        private final Ingredient original;
        private final CompoundTag requirement;

        private RequiredStateIngredient(Ingredient original, CompoundTag requirement) {
            super(Arrays.stream(original.getItems()).map(stack -> {
                ItemStack copy = stack.copy();
                CompoundTag state = copy.getOrCreateTag().getCompound("bladeState");
                state.merge(requirement.getCompound("bladeState"));
                copy.getOrCreateTag().put("bladeState", state);
                return new ItemValue(copy);
            }));
            this.original = original;
            this.requirement = requirement;
        }

        @Override
        public boolean test(@Nullable ItemStack stack) {
            return stack != null && IngredientMatcher.test(original, stack)
                    && matches(requirement, stack.getTag());
        }

        @Override
        public boolean isSimple() { return false; }

        @Override
        public JsonElement toJson() {
            JsonObject json = original.toJson().getAsJsonObject().deepCopy();
            JsonObject request = json.getAsJsonObject("request");
            CompoundTag state = requirement.getCompound("bladeState");
            request.addProperty("kill", state.getInt("killCount"));
            request.addProperty("proud_soul", state.getInt("proudSoul"));
            request.addProperty("refine", state.getInt("RepairCounter"));
            return json;
        }
    }
}
