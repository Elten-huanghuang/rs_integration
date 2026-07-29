package com.huanghuang.rsintegration.mods.pmmo;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Executes salvage with PMMO's levels, random source, output odds, and XP awards. */
public final class PmmoSalvageRuntime {
    private static final String CORE_CLASS = "harmonised.pmmo.core.Core";
    private static final String API_UTILS_CLASS = "harmonised.pmmo.api.APIUtils";
    private static final String PARTY_UTILS_CLASS = "harmonised.pmmo.features.party.PartyUtils";

    private PmmoSalvageRuntime() {}

    public record Execution(List<ItemStack> outputs, int attempts,
                            int failedTargetAttempts) {
        public Execution {
            outputs = outputs.stream().map(ItemStack::copy).toList();
        }
    }

    public enum EligibilityState { ELIGIBLE, LEVEL_TOO_LOW, LOOKUP_FAILED }

    public record MissingLevel(String skill, int required, int actual) {}

    public record Eligibility(EligibilityState state, List<MissingLevel> missing) {
        public Eligibility {
            missing = List.copyOf(missing);
        }

        public boolean eligible() {
            return state == EligibilityState.ELIGIBLE;
        }
    }

    public static boolean isTargetEligible(ServerPlayer player,
                                           PmmoSalvageRecipeWrapper recipe) {
        return eligibility(player, recipe).eligible();
    }

    public static Eligibility eligibility(ServerPlayer player,
                                          PmmoSalvageRecipeWrapper recipe) {
        if (player == null || recipe == null) {
            return new Eligibility(EligibilityState.LOOKUP_FAILED, List.of());
        }
        try {
            return evaluate(recipe.target(), levels(player));
        } catch (ReflectiveOperationException | LinkageError exception) {
            RSIntegrationMod.LOGGER.debug("[RSI-PMMO] Player level lookup failed", exception);
            return new Eligibility(EligibilityState.LOOKUP_FAILED, List.of());
        }
    }

    static Eligibility evaluate(PmmoSalvageDefinition.Output target,
                                Map<String, Integer> levels) {
        List<MissingLevel> missing = new ArrayList<>();
        for (Map.Entry<String, Integer> requirement : target.levelRequirements().entrySet()) {
            int actual = levels.getOrDefault(requirement.getKey(), 0);
            if (actual < requirement.getValue()) {
                missing.add(new MissingLevel(requirement.getKey(), requirement.getValue(), actual));
            }
        }
        return new Eligibility(missing.isEmpty()
                ? EligibilityState.ELIGIBLE : EligibilityState.LEVEL_TOO_LOW, missing);
    }

    public static Component requirementSummary(Eligibility eligibility) {
        MutableComponent summary = Component.empty();
        for (int i = 0; i < eligibility.missing().size(); i++) {
            MissingLevel missing = eligibility.missing().get(i);
            if (i > 0) summary.append(", ");
            summary.append(Component.translatable("rsi.pmmo.level_requirement_entry",
                    Component.translatable("pmmo." + missing.skill()),
                    missing.required(), missing.actual()));
        }
        return summary;
    }

    public static Execution execute(ServerPlayer player, PmmoSalvageRecipeWrapper recipe,
                                    int attempts) throws ReflectiveOperationException {
        if (attempts <= 0) return new Execution(List.of(), 0, 0);
        Map<String, Integer> levels = levels(player);
        PmmoSalvageRoller.Result rolled = PmmoSalvageRoller.roll(
                recipe.definition(), recipe.target().outputId(), attempts,
                skill -> levels.getOrDefault(skill, 0),
                () -> player.getRandom().nextDouble());

        List<ItemStack> outputs = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Integer> entry : rolled.outputs().entrySet()) {
            var item = ForgeRegistries.ITEMS.getValue(entry.getKey());
            if (item != null && entry.getValue() > 0) {
                outputs.add(new ItemStack(item, entry.getValue()));
            }
        }
        awardXp(player, rolled.xpAwards());
        return new Execution(outputs, attempts, rolled.failedTargetAttempts());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> levels(ServerPlayer player)
            throws ReflectiveOperationException {
        Class<?> apiUtils = Class.forName(API_UTILS_CLASS);
        Object value = apiUtils.getMethod("getAllLevels", Player.class).invoke(null, player);
        return value instanceof Map<?, ?> map ? (Map<String, Integer>) map : Map.of();
    }

    private static void awardXp(ServerPlayer player, Map<String, Long> awards) {
        if (awards.isEmpty()) return;
        try {
            Class<?> coreClass = Class.forName(CORE_CLASS);
            Object core = coreClass.getMethod("get", LogicalSide.class)
                    .invoke(null, LogicalSide.SERVER);
            Class<?> partyUtils = Class.forName(PARTY_UTILS_CLASS);
            Method membersMethod = partyUtils.getMethod("getPartyMembersInRange", ServerPlayer.class);
            Object members = membersMethod.invoke(null, player);
            // PMMO normalises the award map in place. The roller returns an
            // immutable snapshot, so hand PMMO a mutable copy.
            coreClass.getMethod("awardXP", List.class, Map.class)
                    .invoke(core, members, new HashMap<>(awards));
        } catch (ReflectiveOperationException | LinkageError exception) {
            RSIntegrationMod.LOGGER.warn("[RSI-PMMO] Salvage succeeded but XP award failed", exception);
        }
    }
}
