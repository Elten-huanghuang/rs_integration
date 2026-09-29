package com.huanghuang.rsintegration.craftingstation;

/** RS 合成终端中可切换的工作站模式。 */
public enum CraftingStationMode {
    CRAFTING,
    STONECUTTER,
    SMITHING,
    ANVIL;

    /** RSI machine type used when this virtual mode requires a real binding. */
    public String requiredBindingTypeId() {
        return switch (this) {
            case STONECUTTER -> "vanilla_stonecutter";
            case SMITHING -> "smithing";
            case ANVIL -> "vanilla_anvil";
            case CRAFTING -> null;
        };
    }

    /** Translation key used in the missing-binding message. */
    public String displayNameKey() {
        return switch (this) {
            case STONECUTTER -> "rsi.batch.mod.vanilla_stonecutter";
            case SMITHING -> "rsi.batch.mod.smithing";
            case ANVIL -> "rsi.batch.mod.vanilla_anvil";
            case CRAFTING -> "rsi.batch.mod.crafting";
        };
    }
}
