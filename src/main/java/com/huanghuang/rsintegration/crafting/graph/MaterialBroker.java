package com.huanghuang.rsintegration.crafting.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Server-thread material arbiter for graph node assets.
 * Reservations are atomic across every request in a node.
 */
public final class MaterialBroker {

    public enum ReservationState {
        RESERVED,
        COMMITTED,
        SETTLED,
        RELEASED,
        REFUNDED
    }

    public record Request(MaterialSource source, MaterialKey material, int quantity) {
        public Request {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(material, "material");
            if (quantity <= 0) throw new IllegalArgumentException("request quantity must be positive");
        }
    }

    public record ReservationToken(long value) {
        public ReservationToken {
            if (value < 0) throw new IllegalArgumentException("reservation token must be non-negative");
        }
    }

    private final Map<Long, AssetLot> lots = new LinkedHashMap<>();
    private final Map<ReservationToken, Reservation> reservations = new HashMap<>();
    private long nextLotId;
    private long nextToken;

    public long publish(MaterialSource source, MaterialKey material, int quantity) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(material, "material");
        if (quantity <= 0) throw new IllegalArgumentException("published quantity must be positive");
        long lotId = nextLotId++;
        lots.put(lotId, new AssetLot(lotId, source, material, quantity));
        return lotId;
    }

    public ReservationToken reserve(NodeId owner, List<Request> requests) {
        Objects.requireNonNull(owner, "owner");
        List<Request> immutableRequests = List.copyOf(requests);
        if (immutableRequests.isEmpty()) {
            ReservationToken token = new ReservationToken(nextToken++);
            reservations.put(token, new Reservation(owner, List.of(), ReservationState.RESERVED));
            return token;
        }

        Map<Long, Integer> planned = new LinkedHashMap<>();
        for (Request request : immutableRequests) {
            int remaining = request.quantity();
            for (AssetLot lot : lots.values()) {
                if (remaining <= 0) break;
                if (!lot.source.equals(request.source()) || !lot.material.equals(request.material())) continue;
                int alreadyPlanned = planned.getOrDefault(lot.id, 0);
                int available = lot.available - alreadyPlanned;
                if (available <= 0) continue;
                int take = Math.min(available, remaining);
                planned.merge(lot.id, take, Integer::sum);
                remaining -= take;
            }
            if (remaining > 0) return null;
        }

        List<Claim> claims = new ArrayList<>(planned.size());
        for (Map.Entry<Long, Integer> entry : planned.entrySet()) {
            AssetLot lot = lots.get(entry.getKey());
            lot.available -= entry.getValue();
            lot.reserved += entry.getValue();
            claims.add(new Claim(lot.id, entry.getValue()));
        }
        ReservationToken token = new ReservationToken(nextToken++);
        reservations.put(token, new Reservation(owner, List.copyOf(claims), ReservationState.RESERVED));
        return token;
    }

    public void commit(ReservationToken token) {
        Reservation reservation = require(token, ReservationState.RESERVED);
        for (Claim claim : reservation.claims) {
            AssetLot lot = lots.get(claim.lotId);
            lot.reserved -= claim.quantity;
            lot.committed += claim.quantity;
        }
        reservation.state = ReservationState.COMMITTED;
    }

    public void release(ReservationToken token) {
        Reservation reservation = require(token, ReservationState.RESERVED);
        for (Claim claim : reservation.claims) {
            AssetLot lot = lots.get(claim.lotId);
            lot.reserved -= claim.quantity;
            lot.available += claim.quantity;
        }
        reservation.state = ReservationState.RELEASED;
    }

    public void settle(ReservationToken token) {
        Reservation reservation = require(token, ReservationState.COMMITTED);
        for (Claim claim : reservation.claims) {
            AssetLot lot = lots.get(claim.lotId);
            lot.committed -= claim.quantity;
            lot.settled += claim.quantity;
        }
        reservation.state = ReservationState.SETTLED;
    }

    public void refund(ReservationToken token) {
        Reservation reservation = require(token, ReservationState.COMMITTED);
        for (Claim claim : reservation.claims) {
            AssetLot lot = lots.get(claim.lotId);
            lot.committed -= claim.quantity;
            lot.available += claim.quantity;
        }
        reservation.state = ReservationState.REFUNDED;
    }

    public ReservationState state(ReservationToken token) {
        Reservation reservation = reservations.get(token);
        return reservation == null ? null : reservation.state;
    }

    public int available(MaterialSource source, MaterialKey material) {
        int total = 0;
        for (AssetLot lot : lots.values()) {
            if (lot.source.equals(source) && lot.material.equals(material)) total += lot.available;
        }
        return total;
    }

    public int heldBy(NodeId owner) {
        int total = 0;
        for (Reservation reservation : reservations.values()) {
            if (!reservation.owner.equals(owner)) continue;
            if (reservation.state == ReservationState.RESERVED
                    || reservation.state == ReservationState.COMMITTED) {
                for (Claim claim : reservation.claims) total += claim.quantity;
            }
        }
        return total;
    }

    /**
     * Remove currently unclaimed units from a source. Reserved, committed, and
     * settled units are never returned, so downstream-owned producer output
     * cannot also be delivered as surplus.
     */
    public int takeAvailable(MaterialSource source, MaterialKey material, int quantity) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(material, "material");
        if (quantity <= 0) throw new IllegalArgumentException("take quantity must be positive");
        int remaining = quantity;
        for (AssetLot lot : lots.values()) {
            if (remaining <= 0) break;
            if (!lot.source.equals(source) || !lot.material.equals(material)) continue;
            int take = Math.min(lot.available, remaining);
            lot.available -= take;
            lot.delivered += take;
            remaining -= take;
        }
        return quantity - remaining;
    }

    public int totalAvailable() {
        return lots.values().stream().mapToInt(lot -> lot.available).sum();
    }

    private Reservation require(ReservationToken token, ReservationState expected) {
        Objects.requireNonNull(token, "token");
        Reservation reservation = reservations.get(token);
        if (reservation == null) throw new IllegalArgumentException("unknown reservation token " + token.value());
        if (reservation.state != expected) {
            throw new IllegalStateException("reservation " + token.value() + " is "
                    + reservation.state + ", expected " + expected);
        }
        return reservation;
    }

    private static final class AssetLot {
        final long id;
        final MaterialSource source;
        final MaterialKey material;
        int available;
        int reserved;
        int committed;
        int settled;
        int delivered;

        AssetLot(long id, MaterialSource source, MaterialKey material, int available) {
            this.id = id;
            this.source = source;
            this.material = material;
            this.available = available;
        }
    }

    private record Claim(long lotId, int quantity) {}

    private static final class Reservation {
        final NodeId owner;
        final List<Claim> claims;
        ReservationState state;

        Reservation(NodeId owner, List<Claim> claims, ReservationState state) {
            this.owner = owner;
            this.claims = claims;
            this.state = state;
        }
    }
}
