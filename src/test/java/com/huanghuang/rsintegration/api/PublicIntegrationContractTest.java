package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicIntegrationContractTest {
    @Test
    void apiVersionUsesSameMajorAndBackwardCompatibleMinorPatchRules() {
        ApiVersion version = new ApiVersion(1, 3, 4);

        assertTrue(version.supports(1, 0, 9));
        assertTrue(version.supports(1, 3, 4));
        assertFalse(version.supports(1, 3, 5));
        assertFalse(version.supports(1, 4, 0));
        assertFalse(version.supports(2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ApiVersion(-1, 0, 0));
    }

    @Test
    void registrationOptionsDefensivelyCopyAttributes() {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("x-test-source", "unit");
        RegistrationOptions options = new RegistrationOptions(10, attributes);
        attributes.put("x-test-mutated", "true");

        assertEquals(10, options.priority());
        assertEquals(Map.of("x-test-source", "unit"), options.attributes());
        assertThrows(UnsupportedOperationException.class,
                () -> options.attributes().put("x-test-other", "value"));
    }

    @Test
    void acceptedRegistrationRequiresAStableId() {
        IntegrationDiagnostic diagnostic = new IntegrationDiagnostic(
                IntegrationDiagnosticCode.REGISTRATION_INVALID_DECLARATION,
                IntegrationSeverity.ERROR,
                new ResourceLocation("test", "integration"),
                Map.of("reason", "missing-id"));
        ResourceLocation id = new ResourceLocation("test", "recipe");

        RegistrationResult result = new RegistrationResult(
                RegistrationStatus.ACCEPTED, id, RegistrationPhase.REGISTRATION,
                List.of(diagnostic));

        assertTrue(result.accepted());
        assertEquals(id, result.registeredId());
        assertEquals(List.of(diagnostic), result.diagnostics());
        assertThrows(IllegalArgumentException.class, () -> new RegistrationResult(
                RegistrationStatus.ACCEPTED, null, RegistrationPhase.REGISTRATION,
                List.of()));
    }

    @Test
    void resourceKeyCopiesIdentityAndComparesCompoundContentIndependentlyOfKeyOrder() {
        CompoundTag first = new CompoundTag();
        first.putString("name", "alpha");
        first.putInt("amount", 3);
        CompoundTag second = new CompoundTag();
        second.putInt("amount", 3);
        second.putString("name", "alpha");
        ResourceLocation id = new ResourceLocation("minecraft", "stone");

        IntegrationResourceKey firstKey = new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM, id, first);
        IntegrationResourceKey secondKey = new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM, id, second);
        first.putString("mutated", "true");

        assertEquals(firstKey, secondKey);
        assertEquals(firstKey.hashCode(), secondKey.hashCode());
        assertFalse(firstKey.identity().contains("mutated"));
        CompoundTag returned = firstKey.identity();
        returned.putString("caller", "mutation");
        assertFalse(firstKey.identity().contains("caller"));
        assertNotEquals(firstKey, new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.FLUID, id, second));
    }

    @Test
    void resourceAmountRejectsNonPositiveValues() {
        IntegrationResourceKey key = new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM,
                new ResourceLocation("minecraft", "stone"),
                new CompoundTag());

        assertEquals(5L, new ResourceAmount(key, 5L).amount());
        assertThrows(IllegalArgumentException.class,
                () -> new ResourceAmount(key, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new ResourceAmount(null, 1L));
    }
}
