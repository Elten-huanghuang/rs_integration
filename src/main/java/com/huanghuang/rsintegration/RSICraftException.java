package com.huanghuang.rsintegration;

public final class RSICraftException extends RuntimeException {

    private final String code;

    public RSICraftException(String code, String message) {
        super(message);
        this.code = code;
    }

    public RSICraftException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }

    public String translationKey() { return "rsi.craft.error." + code; }

    // ── Common error codes ────────────────────────────────────────

    public static RSICraftException recipeUnresolvable(String recipeId) {
        return new RSICraftException("recipe_unresolvable",
                "Cannot resolve ingredients for recipe: " + recipeId);
    }

    public static RSICraftException ledgerStateViolation(String expected, String actual) {
        return new RSICraftException("ledger_state",
                "Ledger state violation: expected " + expected + ", but was " + actual);
    }

    public static RSICraftException chainAborted(String reason) {
        return new RSICraftException("chain_aborted", "Craft chain aborted: " + reason);
    }

    public static RSICraftException materialUnavailable(String item, int needed, int available) {
        return new RSICraftException("material_unavailable",
                "Material unavailable: " + item + " (need " + needed + ", have " + available + ")");
    }

    public static RSICraftException commitFailed(String detail) {
        return new RSICraftException("commit_failed", "Ledger commit failed: " + detail);
    }
}
