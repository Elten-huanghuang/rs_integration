package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.crafting.loadbalancer.OperationQueue;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManaPoolParallelOwnershipTest extends BootstrapTest {
    private static final BlockPos POOL = new BlockPos(0, 64, 0);

    @Test
    void oldWideScanWouldConsumeLaterAdjacentReplicationInputs() {
        ItemStack expected = new ItemStack(Items.DIAMOND, 2);
        ItemStack otherInput = new ItemStack(Items.DIAMOND, 5);
        Vec3 otherPoolInput = new Vec3(1.5, 64.75, 0.5);
        assertTrue(new AABB(POOL).inflate(1.5).contains(otherPoolInput));
        assertTrue(ItemStack.isSameItemSameTags(otherInput, expected));
        assertTrue(otherInput.getCount() >= expected.getCount());
        assertFalse(ManaPoolBatchDelegate.acceptsPhysicalOutput(
                POOL, otherPoolInput, otherInput, expected, false));
    }

    @Test
    void evenSamePoolInputRequiresBotaniaInfusionFlag() {
        ItemStack stack = new ItemStack(Items.DIAMOND, 5);
        assertFalse(ManaPoolBatchDelegate.acceptsPhysicalOutput(
                POOL, new Vec3(0.5, 64.75, 0.5), stack, stack, false));
    }

    @Test
    void neighborOutputsCannotBeCollectedEvenWhenTheyHaveNativeFlag() {
        ItemStack stack = new ItemStack(Items.DIAMOND, 2);
        assertFalse(ManaPoolBatchDelegate.acceptsPhysicalOutput(
                POOL, new Vec3(1.5, 65.5, 0.5), stack, stack, true));
    }

    @Test
    void splitOutputBelowRecipeStackCountStillBelongsToThisPool() {
        ItemStack expected = new ItemStack(Items.DIAMOND, 2);
        assertTrue(ManaPoolBatchDelegate.acceptsPhysicalOutput(
                POOL, new Vec3(0.5, 65.5, 0.5), expected.copyWithCount(1), expected, true));
        ItemStack differentState = expected.copy();
        differentState.getOrCreateTag().putInt("state", 1);
        assertFalse(ManaPoolBatchDelegate.acceptsPhysicalOutput(
                POOL, new Vec3(0.5, 65.5, 0.5), differentState, expected, true));
    }

    @Test
    void sixAdjacentWorkersFinishUnevenWindowsWithoutStealingInputs() {
        int completed = 0;
        int produced = 0;
        for (int window = 0; window < 32; window++) {
            OperationQueue queue = new OperationQueue(32);
            List<Integer> batches = new ArrayList<>();
            for (int worker = 0; worker < 6; worker++) {
                batches.add(queue.claimBatch(worker,
                        ManaPoolBatchDelegate.parallelWorkerBatchSize(32, 6)).size());
            }
            assertEquals(List.of(6, 6, 6, 6, 6, 2), batches);
            for (int worker = 0; worker < 6; worker++) {
                BlockPos pool = POOL.offset(worker, 0, 0);
                ItemStack expected = new ItemStack(Items.DIAMOND, 2);
                for (int other = worker + 1; other < 6; other++) {
                    assertFalse(ManaPoolBatchDelegate.acceptsPhysicalOutput(pool,
                            new Vec3(other + 0.5, 64.75, 0.5),
                            expected.copyWithCount(batches.get(other)), expected, false));
                }
                for (int operation = 0; operation < batches.get(worker); operation++) {
                    assertTrue(ManaPoolBatchDelegate.acceptsPhysicalOutput(pool,
                            new Vec3(worker + 0.5, 65.5, 0.5), expected, expected, true));
                    produced += expected.getCount();
                }
                completed += queue.completeBatch(worker).size();
            }
            assertTrue(queue.isComplete());
            assertEquals(0, queue.runningOperations());
        }
        assertEquals(1024, completed);
        assertEquals(2048, produced);
    }

    @Test
    void parallelShareRespectsManaWithoutIncreasingOrZeroingClaim() {
        int share = ManaPoolBatchDelegate.parallelWorkerBatchSize(32, 6);
        assertEquals(6, ManaPoolBatchDelegate.physicalBatchSize(share, 12000, 2000));
        assertEquals(2, ManaPoolBatchDelegate.physicalBatchSize(share, 4000, 2000));
        assertEquals(1, ManaPoolBatchDelegate.physicalBatchSize(share, 0, 2000));
    }

    @Test
    void productionCodeUsesNativeFlagAndLocalCollectionRegion() throws IOException {
        List<String> calls = new ArrayList<>();
        try (var stream = ManaPoolBatchDelegate.class.getResourceAsStream("ManaPoolBatchDelegate.class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            calls.add(method + ":" + name);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            if (opcode == Opcodes.GETFIELD) calls.add(method + ":field:" + name);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertTrue(calls.contains("isCraftOutput:field:manaInfusionSpawned"));
        assertTrue(calls.contains("isCraftOutput:acceptsPhysicalOutput"));
        assertTrue(calls.contains("collectResult:getOutputCaptureRegion"));
        assertTrue(calls.contains("isMachineCraftFinished:getOutputCaptureRegion"));
        assertFalse(calls.contains("collectResult:inflate"));
        assertTrue(calls.contains("preferredParallelBatchSize:physicalBatchSize"));
    }
}
