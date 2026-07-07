package com.huanghuang.rsintegration.util;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Lightweight correlation token carried through a single craft operation
 * (resolve → commit or rollback).  Prefixes log messages with a short
 * operation ID so entries from the same craft can be grouped across
 * different classes.
 */
public final class CraftLogContext {

    private final UUID opId;
    private final UUID playerId;
    private final ResourceLocation recipeId;
    @Nullable private final String step;
    private final String prefix;

    private CraftLogContext(UUID opId, UUID playerId, ResourceLocation recipeId,
                           @Nullable String step) {
        this.opId = opId;
        this.playerId = playerId;
        this.recipeId = recipeId;
        this.step = step;
        this.prefix = "[" + opId.toString().replace("-", "").substring(0, 8) + "]";
    }

    public static CraftLogContext create(UUID playerId, ResourceLocation recipeId) {
        return new CraftLogContext(UUID.randomUUID(), playerId, recipeId, null);
    }

    public CraftLogContext withStep(String newStep) {
        return new CraftLogContext(opId, playerId, recipeId, newStep);
    }

    public String prefix() { return prefix; }

    public String format(String message) {
        return prefix + " " + message;
    }

    public UUID opId() { return opId; }
    public UUID playerId() { return playerId; }
    public ResourceLocation recipeId() { return recipeId; }
    @Nullable public String step() { return step; }

    @Override
    public String toString() {
        return prefix + " recipe=" + recipeId + " player="
                + playerId.toString().replace("-", "").substring(0, 8);
    }
}
