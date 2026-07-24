package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Server-thread material arbiter for graph node assets.
 * Reservations are atomic across every request in a node.
 *
 * <p><b>THREAD SAFETY:</b> This class is <b>NOT</b> thread-safe.
 * All methods must be called from the server tick thread only.
 * The internal HashMaps are not synchronized; calling from worker threads
 * or async callbacks will cause data races leading to item duplication or loss.
 *
 * <p>Do not convert to ConcurrentHashMap without also synchronizing
 * multi-step operations (reserve + commit is not atomic).
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

    public record Fragment(MaterialSource source, MaterialKey material, ItemStack stack) {
        public Fragment {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(stack, "stack");
            if (stack.isEmpty()) throw new IllegalArgumentException("fragment must be non-empty");
            stack = stack.copy();
        }
    }

    public record Checkout(List<Fragment> fragments) {
        public Checkout {
            fragments = List.copyOf(fragments);
        }

        public List<ItemStack> initialStacks() {
            List<ItemStack> stacks = new ArrayList<>();
            for (Fragment fragment : fragments) {
                if (fragment.source() instanceof MaterialSource.InitialPool) {
                    stacks.add(fragment.stack().copy());
                }
            }
            return List.copyOf(stacks);
        }

        public List<ItemStack> producerStacks() {
            List<ItemStack> stacks = new ArrayList<>();
            for (Fragment fragment : fragments) {
                if (fragment.source() instanceof MaterialSource.ProducerOutput) {
                    stacks.add(fragment.stack().copy());
                }
            }
            return List.copyOf(stacks);
        }
    }

    private final Map<Long, AssetLot> lots = new LinkedHashMap<>();
    private final Map<ReservationToken, Reservation> reservations = new HashMap<>();
    private long nextLotId;
    private long nextToken;

    public long publish(MaterialSource source, MaterialKey material, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("published quantity must be positive");
        return publishActual(source, material, material.toStack(quantity));
    }

    /** Publish the exact runtime fragment owned by this source. */
    public long publishActual(MaterialSource source, MaterialKey material, ItemStack stack) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty() || stack.getCount() <= 0) {
            throw new IllegalArgumentException("published stack must be non-empty");
        }
        if (!MaterialMatcher.matchesExact(material, stack)) {
            throw new IllegalArgumentException("published stack does not match material key");
        }
        long lotId = nextLotId++;
        lots.put(lotId, new AssetLot(lotId, source, material, stack));
        return lotId;
    }

    public boolean canReserve(List<Request> requests) {
        Objects.requireNonNull(requests, "requests");
        return plan(List.copyOf(requests)) != null;
    }

    public ReservationToken reserve(NodeId owner, List<Request> requests) {
        Objects.requireNonNull(owner, "owner");
        List<Request> immutableRequests = List.copyOf(requests);
        if (immutableRequests.isEmpty()) {
            ReservationToken token = new ReservationToken(nextToken++);
            reservations.put(token, new Reservation(owner, List.of(), ReservationState.RESERVED));
            return token;
        }

        Map<Long, Integer> planned = plan(immutableRequests);
        if (planned == null) return null;

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

    /**
     * Return exact producer fragments for operations that never reached a machine.
     * Remaining committed claims stay owned by in-flight/consumed operations.
     */
    public void refundCommittedProducerFragments(ReservationToken token, List<ItemStack> fragments) {
        Reservation reservation = require(token, ReservationState.COMMITTED);
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack stack : fragments) {
            if (stack != null && !stack.isEmpty()) remaining.add(stack.copy());
        }
        for (Claim claim : reservation.claims) {
            AssetLot lot = lots.get(claim.lotId);
            if (!(lot.source instanceof MaterialSource.ProducerOutput) || lot.committed <= 0) continue;
            int refundable = Math.min(claim.quantity, lot.committed);
            for (ItemStack stack : remaining) {
                if (refundable <= 0) break;
                if (stack.isEmpty() || !MaterialMatcher.matchesExact(lot.material, stack)) continue;
                int giveBack = Math.min(refundable, stack.getCount());
                stack.shrink(giveBack);
                lot.committed -= giveBack;
                lot.available += giveBack;
                claim.quantity -= giveBack;
                refundable -= giveBack;
            }
        }
        remaining.removeIf(ItemStack::isEmpty);
        if (!remaining.isEmpty()) {
            throw new IllegalArgumentException("recovered fragments exceed committed producer claims");
        }
    }

    private Map<Long, Integer> plan(List<Request> requests) {
        Map<Long, Integer> planned = new LinkedHashMap<>();
        for (Request request : requests) {
            int remaining = request.quantity();
            for (AssetLot lot : lots.values()) {
                if (remaining <= 0) break;
                if (!lot.source.equals(request.source()) || !lot.material.equals(request.material())) continue;
                int available = lot.available - planned.getOrDefault(lot.id, 0);
                if (available <= 0) continue;
                int take = Math.min(available, remaining);
                planned.merge(lot.id, take, Integer::sum);
                remaining -= take;
            }
            if (remaining > 0) return null;
        }
        return planned;
    }

    public Checkout checkout(ReservationToken token) {
        Objects.requireNonNull(token, "token");
        Reservation reservation = reservations.get(token);
        if (reservation == null) throw new IllegalArgumentException("unknown reservation token " + token.value());
        if (reservation.state != ReservationState.RESERVED
                && reservation.state != ReservationState.COMMITTED) {
            throw new IllegalStateException("reservation " + token.value() + " is " + reservation.state);
        }
        List<Fragment> fragments = new ArrayList<>();
        for (Claim claim : reservation.claims) {
            AssetLot lot = lots.get(claim.lotId);
            fragments.add(new Fragment(lot.source, lot.material,
                    lot.stack.copyWithCount(claim.quantity)));
        }
        return new Checkout(fragments);
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
        return count(drainAvailable(source, material, quantity));
    }

    /**
     * Return exact available fragments for delivery. Reserved, committed and
     * settled units are excluded by construction.
     */
    public List<ItemStack> drainAvailable(MaterialSource source, MaterialKey material, int quantity) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(material, "material");
        if (quantity <= 0) throw new IllegalArgumentException("take quantity must be positive");
        int remaining = quantity;
        List<ItemStack> drained = new ArrayList<>();
        for (AssetLot lot : lots.values()) {
            if (remaining <= 0) break;
            if (!lot.source.equals(source) || !lot.material.equals(material)) continue;
            int take = Math.min(lot.available, remaining);
            if (take <= 0) continue;
            drained.add(lot.stack.copyWithCount(take));
            lot.available -= take;
            lot.delivered += take;
            remaining -= take;
        }
        return List.copyOf(drained);
    }

    /** Exact producer fragments owned by a reservation. */
    public List<ItemStack> producerFragments(ReservationToken token) {
        Objects.requireNonNull(token, "token");
        Reservation reservation = reservations.get(token);
        if (reservation == null) throw new IllegalArgumentException("unknown reservation token " + token.value());
        if (reservation.state != ReservationState.RESERVED
                && reservation.state != ReservationState.COMMITTED) {
            throw new IllegalStateException("reservation " + token.value() + " is " + reservation.state);
        }
        List<ItemStack> fragments = new ArrayList<>();
        for (Claim claim : reservation.claims) {
            AssetLot lot = lots.get(claim.lotId);
            if (lot.source instanceof MaterialSource.ProducerOutput) {
                fragments.add(lot.stack.copyWithCount(claim.quantity));
            }
        }
        return List.copyOf(fragments);
    }

    /** Drain every currently available producer fragment exactly once. */
    public List<ItemStack> drainAvailableProducerAssets() {
        List<ItemStack> drained = new ArrayList<>();
        for (AssetLot lot : lots.values()) {
            if (!(lot.source instanceof MaterialSource.ProducerOutput) || lot.available <= 0) continue;
            drained.add(lot.stack.copyWithCount(lot.available));
            lot.delivered += lot.available;
            lot.available = 0;
        }
        return List.copyOf(drained);
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
        final ItemStack stack;
        int available;
        int reserved;
        int committed;
        int settled;
        int delivered;

        AssetLot(long id, MaterialSource source, MaterialKey material, ItemStack stack) {
            this.id = id;
            this.source = source;
            this.material = material;
            this.stack = stack.copy();
            this.available = stack.getCount();
        }
    }

    private static int count(List<ItemStack> stacks) {
        int total = 0;
        for (ItemStack stack : stacks) total += stack.getCount();
        return total;
    }

    private static final class Claim {
        final long lotId;
        int quantity;

        Claim(long lotId, int quantity) {
            this.lotId = lotId;
            this.quantity = quantity;
        }
    }

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
