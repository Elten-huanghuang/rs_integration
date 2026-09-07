package com.huanghuang.rsintegration.crafting.availability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Bounded client cache. Tickets prevent delayed responses from repopulating invalidated entries. */
public final class RecipeAvailabilityCache {
    static final long REFRESH_MS = 2_000;
    static final int MAX_ENTRIES = 256;
    private final Map<RecipeAvailabilityKey, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long nextTicket;
    private long windowStart = Long.MIN_VALUE;
    private int requests;

    public MaterialAvailability get(RecipeAvailabilityKey key, long now,
                                      BiConsumer<RecipeAvailabilityKey, Long> send) {
        Entry entry = entries.get(key);
        if (entry != null && now >= entry.time && now - entry.time < REFRESH_MS) return entry.state;
        if (windowStart == Long.MIN_VALUE || now < windowStart || now - windowStart >= 1_000) {
            windowStart = now;
            requests = 0;
        }
        if (requests >= 32) return MaterialAvailability.UNKNOWN;
        requests++;
        long ticket = ++nextTicket;
        entries.put(key, new Entry(ticket, now, MaterialAvailability.UNKNOWN));
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
        send.accept(key, ticket);
        return MaterialAvailability.UNKNOWN;
    }

    public void accept(RecipeAvailabilityKey key, long ticket, MaterialAvailability state, long now) {
        Entry pending = entries.get(key);
        if (pending != null && pending.ticket == ticket && now >= pending.time
                && now - pending.time < REFRESH_MS) {
            entries.put(key, new Entry(ticket, pending.time, state));
        }
    }

    public void clear() {
        entries.clear();
        // Retain ticket and rate window across screen changes and logout.
    }

    private record Entry(long ticket, long time, MaterialAvailability state) {}
}
