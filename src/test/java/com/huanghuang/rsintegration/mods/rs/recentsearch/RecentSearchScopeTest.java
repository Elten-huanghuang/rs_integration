package com.huanghuang.rsintegration.mods.rs.recentsearch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RecentSearchScopeTest {
    @Test
    void scopeHashIsStableAndDoesNotExposeTheServerAddress() {
        String address = "multiplayer:example.org:25565";
        String hash = RecentSearchScope.hashScope(address);

        assertEquals(hash, RecentSearchScope.hashScope(address));
        assertEquals(24, hash.length());
        assertNotEquals(address, hash);
        assertNotEquals(hash, RecentSearchScope.hashScope("multiplayer:other.example.org"));
    }
}
