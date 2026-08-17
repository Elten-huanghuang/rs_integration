package com.huanghuang.rsintegration.crafting.planning;

import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

/** Fixed-cardinality reasons why preview planning continues synchronously. */
public enum SynchronousFallbackReason {
    MAIN_THREAD_ONLY,
    INCOMPLETE_DEMAND_TREE,
    CATALYST_ROUTE,
    RECIPE_OVERRIDES,
    PURE_UNRESOLVABLE,
    STEP_LIMIT,
    SEARCH_LIMIT,
    ASYNC_FAILURE;

    public static Optional<SynchronousFallbackReason> whenPureRouteUnavailable(
            boolean mainThreadOnly, boolean hasOverrides, boolean demandTreePureCompatible) {
        return whenPureRouteUnavailable(mainThreadOnly, hasOverrides,
                demandTreePureCompatible, false);
    }

    public static Optional<SynchronousFallbackReason> whenPureRouteUnavailable(
            boolean mainThreadOnly, boolean hasOverrides, boolean demandTreePureCompatible,
            boolean catalystRouteAvailable) {
        if (mainThreadOnly) return Optional.of(MAIN_THREAD_ONLY);
        if (hasOverrides) return Optional.of(RECIPE_OVERRIDES);
        if (!demandTreePureCompatible) return Optional.of(INCOMPLETE_DEMAND_TREE);
        if (catalystRouteAvailable) return Optional.of(CATALYST_ROUTE);
        return Optional.empty();
    }

    public static Optional<SynchronousFallbackReason> fromAsyncFailure(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof CancellationException
                || cause instanceof RejectedExecutionException
                || cause instanceof AsyncPlanningCoordinator.StalePlanningResultException) {
            return Optional.empty();
        }
        if (cause instanceof PlanningThreadContext.MainThreadPlanningFallbackException) {
            return Optional.of(MAIN_THREAD_ONLY);
        }
        return Optional.of(ASYNC_FAILURE);
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }
}
