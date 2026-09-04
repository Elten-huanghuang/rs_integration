package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackpackOperationOwnerTest {
    @Test
    void roundTripsBindingPlayerIdentityForOfflineUse() {
        UUID id = UUID.randomUUID();
        CompoundTag tag = new CompoundTag();

        BackpackOperationOwner.write(tag, new GameProfile(id, "OfflineOwner"));

        GameProfile restored = BackpackOperationOwner.read(tag).orElseThrow();
        assertEquals(id, restored.getId());
        assertEquals("OfflineOwner", restored.getName());
    }

    @Test
    void oldUpgradeWithoutOwnerRemainsDetectableForLegacyRecovery() {
        assertTrue(BackpackOperationOwner.read(new CompoundTag()).isEmpty());
    }

    @Test
    void malformedStoredNameCannotCreateAnInvalidFakePlayerProfile() {
        UUID id = UUID.randomUUID();
        CompoundTag tag = new CompoundTag();
        tag.putUUID(BackpackOperationOwner.OWNER_UUID_TAG, id);
        tag.putString(BackpackOperationOwner.OWNER_NAME_TAG, "invalid player name that is too long");

        GameProfile restored = BackpackOperationOwner.read(tag).orElseThrow();
        assertEquals(id, restored.getId());
        assertEquals("RSI_Backpack", restored.getName());
    }
}
