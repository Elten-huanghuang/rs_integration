package com.huanghuang.rsintegration.crafting;

public final class RSICraftException extends RuntimeException {

    public enum Reason {
        RECIPE_UNRESOLVABLE("recipe_unresolvable", false),
        LEDGER_STATE("ledger_state", false),
        CHAIN_ABORTED("chain_aborted", false),
        MATERIAL_UNAVAILABLE("material_unavailable", true),
        COMMIT_FAILED("commit_failed", false),
        NETWORK_UNAVAILABLE("network_unavailable", true);

        private final String code;
        private final boolean retryable;

        Reason(String code, boolean retryable) {
            this.code = code;
            this.retryable = retryable;
        }

        public String code() { return code; }
        public boolean retryable() { return retryable; }
    }

    private final String code;
    private final Reason reason;

    public RSICraftException(String code, String message) {
        super(message);
        this.code = code;
        this.reason = null;
    }

    public RSICraftException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.reason = null;
    }

    public RSICraftException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.code = reason.code();
    }

    public String code() { return code; }
    public Reason reason() { return reason; }
    public boolean retryable() { return reason != null && reason.retryable(); }

    public String translationKey() { return "rsi.craft.error." + code; }

    public static RSICraftException recipeUnresolvable(String recipeId) {
        return new RSICraftException(Reason.RECIPE_UNRESOLVABLE,
                "Cannot resolve ingredients for recipe: " + recipeId, null);
    }

    public static RSICraftException ledgerStateViolation(String expected, String actual) {
        return new RSICraftException(Reason.LEDGER_STATE,
                "Ledger state violation: expected " + expected + ", but was " + actual, null);
    }

    public static RSICraftException chainAborted(String reason) {
        return new RSICraftException(Reason.CHAIN_ABORTED,
                "Craft chain aborted: " + reason, null);
    }

    public static RSICraftException materialUnavailable(String item, int needed, int available) {
        return new RSICraftException(Reason.MATERIAL_UNAVAILABLE,
                "Material unavailable: " + item + " (need " + needed + ", have " + available + ")", null);
    }

    public static RSICraftException commitFailed(String detail) {
        return new RSICraftException(Reason.COMMIT_FAILED,
                "Ledger commit failed: " + detail, null);
    }

    public static RSICraftException networkUnavailable(Throwable cause) {
        return new RSICraftException(Reason.NETWORK_UNAVAILABLE,
                "Storage network is unavailable", cause);
    }
}
