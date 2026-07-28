package com.huanghuang.rsintegration.crafting.planning;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

/** Fixed-cardinality reasons why preview planning continues synchronously. */
public enum SynchronousFallbackReason {
    MAIN_THREAD_ONLY,
    TARGET_NOT_PROJECTED,
    PURE_UNRESOLVABLE,
    STEP_LIMIT,
    SEARCH_LIMIT,
    ASYNC_FAILURE;

    public static Optional<SynchronousFallbackReason> whenAsyncUnavailable(
            boolean mainThreadOnly, boolean targetProjected) {
        if (mainThreadOnly) return Optional.of(MAIN_THREAD_ONLY);
        if (!targetProjected) return Optional.of(TARGET_NOT_PROJECTED);
        return Optional.empty();
    }

    public static Optional<SynchronousFallbackReason> fromPureResult(
            @Nullable PureRecipePlanner.Result result) {
        if (result == null || result.status() == PureRecipePlanner.Status.SUCCESS) {
            return Optional.empty();
        }
        return Optional.of(switch (result.status()) {
            case UNRESOLVABLE -> PURE_UNRESOLVABLE;
            case STEP_LIMIT -> STEP_LIMIT;
            case SEARCH_LIMIT -> SEARCH_LIMIT;
            case SUCCESS -> throw new IllegalStateException("successful result handled above");
        });
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
