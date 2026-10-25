package com.huanghuang.rsintegration.api.consumer;

import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record ConsumerInspectionTicket(
        ConsumerInspectionTicketId ticketId,
        UUID playerId,
        ResourceLocation consumerId,
        int totalRecipes,
        int pageSize,
        long snapshotRevision
) {
    public ConsumerInspectionTicket {
        if (ticketId == null || playerId == null || consumerId == null
                || totalRecipes <= 0 || pageSize <= 0
                || snapshotRevision <= 0L) {
            throw new IllegalArgumentException(
                    "inspection ticket is incomplete");
        }
    }
}
