package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.api.RSIIntegrationServices;
import com.huanghuang.rsintegration.api.RegistrationOptions;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities;
import com.huanghuang.rsintegration.api.machine.MachineBinding;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.MachineDescriptorResult;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.machine.PlannedConcurrency;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExternalIntegrationRuntimeTest {
    private static final ResourceLocation PROCESS =
            new ResourceLocation("test", "process");

    @Test
    void plannedProfilesMergeSupportOffsetsConservatively() {
        PlannedConcurrency first = new PlannedConcurrency(
                ConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                ConcurrencyCapabilities.OutputOwnership.MACHINE_SLOT,
                ConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                ConcurrencyCapabilities.SideEffects.MACHINE_LOCAL,
                ConcurrencyCapabilities.PreparationContract.RETRY_SAFE,
                List.of(new BlockPos(1, 0, 0))
        );
        PlannedConcurrency second = new PlannedConcurrency(
                ConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                ConcurrencyCapabilities.OutputOwnership.MACHINE_SLOT,
                ConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                ConcurrencyCapabilities.SideEffects.ADJACENT_MACHINE,
                ConcurrencyCapabilities.PreparationContract.RELIABLE,
                List.of(new BlockPos(0, 0, 1))
        );

        BatchConcurrencyCapabilities merged =
                ExternalIntegrationRuntime.mergePlanningConcurrency(
                        List.of(first, second));

        assertEquals(
                BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                merged.materials()
        );
        assertEquals(
                BatchConcurrencyCapabilities.OutputOwnership.MACHINE_SLOT,
                merged.outputOwnership()
        );
        assertEquals(
                BatchConcurrencyCapabilities.SideEffects.ADJACENT_MACHINE,
                merged.sideEffects()
        );
        assertEquals(
                List.of(new BlockPos(1, 0, 0), new BlockPos(0, 0, 1)),
                merged.supportOffsets()
        );
    }

    @Test
    void conflictingOutputOwnershipDegradesToAmbiguous() {
        BatchConcurrencyCapabilities merged =
                ExternalIntegrationRuntime.mergePlanningConcurrency(List.of(
                        PlannedConcurrency.machineSlot(),
                        PlannedConcurrency.delegateResult()
                ));

        assertEquals(
                BatchConcurrencyCapabilities.OutputOwnership.AMBIGUOUS,
                merged.outputOwnership()
        );
    }

    @Test
    void processWithAnyUndeclaredMachineRemainsExclusive() {
        RSIIntegrationServices.MachineRegistration declared =
                registration("declared", PlannedConcurrency.machineSlot());
        RSIIntegrationServices.MachineRegistration undeclared =
                registration("undeclared", null);

        BatchConcurrencyCapabilities resolved =
                ExternalIntegrationRuntime.resolvePlanningConcurrency(
                        PROCESS,
                        List.of(declared, undeclared)
                );

        assertNull(resolved);
    }

    @Test
    void declarationByOneIntegrationCoversAllOfItsProcessBindings() {
        BatchConcurrencyCapabilities resolved =
                ExternalIntegrationRuntime.resolvePlanningConcurrency(
                        PROCESS,
                        List.of(registration(
                                "declared",
                                PlannedConcurrency.machineSlot()
                        ))
                );

        assertEquals(
                BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                resolved.materials()
        );
    }

    private static RSIIntegrationServices.MachineRegistration registration(
            String path,
            PlannedConcurrency profile
    ) {
        MachineIntegration integration = new MachineIntegration() {
            private final ResourceLocation id =
                    new ResourceLocation("test", path);

            @Override
            public ResourceLocation id() {
                return id;
            }

            @Override
            public List<MachineBinding> bindings() {
                return List.of(MachineBinding.blocks(
                        Set.of(PROCESS),
                        false,
                        new ResourceLocation("test", path + "_block")
                ));
            }

            @Override
            public Map<ResourceLocation, PlannedConcurrency>
            planningConcurrency() {
                return profile == null
                        ? Map.of()
                        : Map.of(PROCESS, profile);
            }

            @Override
            public boolean supports(BlockEntity blockEntity) {
                return false;
            }

            @Override
            public MachineDescriptorResult describe(
                    MachineContext context,
                    BlockEntity blockEntity
            ) {
                throw new AssertionError("describe is not expected");
            }
        };
        return new RSIIntegrationServices.MachineRegistration(
                integration,
                RegistrationOptions.defaults()
        );
    }
}
