package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conservative policy gate for cross-node delegate concurrency. */
public final class GraphConcurrencyPolicy {
    public enum DelegatePolicy {
        AUTO,
        OFF,
        FORCE_WITH_GUARDS
    }

    public record Decision(boolean exclusive, String reason,
                           BatchConcurrencyCapabilities capabilities) {
        static Decision allow(BatchConcurrencyCapabilities capabilities) {
            return new Decision(false, "capability guards passed", capabilities);
        }

        static Decision deny(String reason, BatchConcurrencyCapabilities capabilities) {
            return new Decision(true, reason, capabilities);
        }
    }

    private GraphConcurrencyPolicy() {}

    public static boolean isExclusive(String modTypeId, IBatchDelegate delegate) {
        return decide(modTypeId, delegate).exclusive();
    }

    public static Decision decide(String modTypeId, IBatchDelegate delegate) {
        return decide(modTypeId, delegate, null, disabledMods(), delegatePolicies());
    }

    public static Decision decide(String modTypeId, IBatchDelegate delegate,
                                  BatchConcurrencyCapabilities recipeCapabilities) {
        return decide(modTypeId, delegate, recipeCapabilities, disabledMods(), delegatePolicies());
    }

    static Decision decide(String modTypeId, IBatchDelegate delegate,
                           List<? extends String> disabled,
                           List<? extends String> policies) {
        return decide(modTypeId, delegate, null, disabled, policies);
    }

    static Decision decide(String modTypeId, IBatchDelegate delegate,
                           BatchConcurrencyCapabilities recipeCapabilities,
                           List<? extends String> disabled,
                           List<? extends String> policies) {
        if (delegate == null) return Decision.deny("delegate unavailable", null);
        if (isModDisabled(modTypeId, disabled)) {
            return Decision.deny("mod disabled by craftingParallelDisabledMods", null);
        }
        DelegatePolicy policy = policyFor(modTypeId, delegate, policies);
        if (policy == DelegatePolicy.OFF) {
            return Decision.deny("delegate policy is OFF", delegate.concurrencyCapabilities());
        }

        BatchConcurrencyCapabilities capabilities = recipeCapabilities != null
                ? recipeCapabilities : delegate.concurrencyCapabilities();
        if (capabilities == null) {
            return Decision.deny(delegate.supportsConcurrentNodeExecution()
                    ? "legacy boolean lacks capability contract"
                    : "delegate has no concurrency capability", null);
        }
        if (capabilities.materials()
                != BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED) {
            return Decision.deny("materials are not fully chain-reserved", capabilities);
        }
        if (capabilities.cleanup()
                != BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE) {
            return Decision.deny("cleanup is not separable/offline-safe", capabilities);
        }
        if (capabilities.preparation()
                == BatchConcurrencyCapabilities.PreparationContract.UNKNOWN) {
            return Decision.deny("preparation contract is unknown", capabilities);
        }
        if (capabilities.outputOwnership()
                == BatchConcurrencyCapabilities.OutputOwnership.AMBIGUOUS
                || capabilities.outputOwnership()
                == BatchConcurrencyCapabilities.OutputOwnership.ENTITY) {
            return Decision.deny("output ownership is ambiguous or entity-owned", capabilities);
        }
        if (capabilities.sideEffects() != BatchConcurrencyCapabilities.SideEffects.NONE
                && capabilities.sideEffects() != BatchConcurrencyCapabilities.SideEffects.MACHINE_LOCAL
                && capabilities.sideEffects() != BatchConcurrencyCapabilities.SideEffects.LOCAL_WORLD_ITEMS
                && capabilities.sideEffects() != BatchConcurrencyCapabilities.SideEffects.ADJACENT_MACHINE
                && capabilities.sideEffects() != BatchConcurrencyCapabilities.SideEffects.INFER) {
            return Decision.deny("delegate has non-local side effects: "
                    + capabilities.sideEffects().name().toLowerCase(Locale.ROOT), capabilities);
        }
        return Decision.allow(capabilities);
    }

    static boolean isModDisabled(String modTypeId, List<? extends String> disabled) {
        if (modTypeId == null || disabled == null || disabled.isEmpty()) return false;
        Set<String> normalized = new HashSet<>();
        for (String entry : disabled) {
            if (entry != null && !entry.isBlank()) normalized.add(entry.trim().toLowerCase(Locale.ROOT));
        }
        return normalized.contains(modTypeId.toLowerCase(Locale.ROOT));
    }

    static DelegatePolicy policyFor(String modTypeId, IBatchDelegate delegate,
                                    List<? extends String> policies) {
        if (policies == null || policies.isEmpty()) return DelegatePolicy.AUTO;
        String mod = normalize(modTypeId);
        String className = normalize(delegate.getClass().getName());
        String simpleName = normalize(delegate.getClass().getSimpleName());
        DelegatePolicy selected = DelegatePolicy.AUTO;
        int selectedSpecificity = -1;
        for (String entry : policies) {
            if (entry == null) continue;
            int separator = entry.lastIndexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) continue;
            String key = normalize(entry.substring(0, separator));
            DelegatePolicy value;
            try {
                value = DelegatePolicy.valueOf(entry.substring(separator + 1)
                        .trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            int specificity = key.equals(className) ? 3
                    : key.equals(simpleName) ? 2
                    : key.equals(mod) ? 1 : -1;
            if (specificity > selectedSpecificity) {
                selected = value;
                selectedSpecificity = specificity;
            }
        }
        return selected;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<? extends String> disabledMods() {
        try {
            return RSIntegrationConfig.CRAFTING_PARALLEL_DISABLED_MODS.get();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<? extends String> delegatePolicies() {
        try {
            return RSIntegrationConfig.CRAFTING_PARALLEL_DELEGATE_POLICIES.get();
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
