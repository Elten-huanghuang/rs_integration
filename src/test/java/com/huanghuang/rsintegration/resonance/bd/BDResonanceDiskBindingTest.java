package com.huanghuang.rsintegration.resonance.bd;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BDResonanceDiskBindingTest extends BootstrapTest {
    private static final String ACCESS =
            "com/huanghuang/rsintegration/resonance/bd/BDResonanceDiskAccess";

    @Test
    void uuidWithoutNetworkIdIsNotACompleteBinding() {
        ItemStack stack = new ItemStack(Items.PAPER);
        CompoundTag disk = new CompoundTag();
        disk.putUUID(BDResonanceDiskAccess.UUID_TAG, UUID.randomUUID());
        stack.getOrCreateTag().put(BDResonanceDiskAccess.DISK_TAG, disk);

        assertEquals(-1, BDResonanceDiskAccess.getTaggedNetworkId(stack));
        assertFalse(BDResonanceDiskAccess.hasCompleteBindingTags(stack));
    }

    @Test
    void bindingWritesUuidAndNonNegativeNetworkIdTogether() {
        ItemStack stack = new ItemStack(Items.PAPER);
        UUID diskId = UUID.randomUUID();

        BDResonanceDiskAccess.bind(stack, diskId, 0);

        assertEquals(diskId, BDResonanceDiskAccess.getTaggedDiskId(stack));
        assertEquals(0, BDResonanceDiskAccess.getTaggedNetworkId(stack));
        assertTrue(BDResonanceDiskAccess.hasCompleteBindingTags(stack));
    }

    @Test
    void provisionalNegativeNetworkIdRemainsUnbound() {
        ItemStack stack = new ItemStack(Items.PAPER);

        BDResonanceDiskAccess.bind(stack, UUID.randomUUID(), -1);

        assertFalse(BDResonanceDiskAccess.hasCompleteBindingTags(stack));
    }

    @Test
    void networkBindingWritesItemBeforeTryingToCoalesce() throws IOException {
        int[] instruction = {0};
        int[] bindCall = {-1};
        int[] coalesceCall = {-1};
        Path classFile = Path.of("build", "classes", "java", "main",
                "com", "huanghuang", "rsintegration", "resonance", "bd",
                "BDResonanceDiskAccess.class");

        new ClassReader(Files.readAllBytes(classFile)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!"bindToNetwork".equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        int position = instruction[0]++;
                        if (!ACCESS.equals(owner)) return;
                        if ("bind".equals(name)) bindCall[0] = position;
                        if ("coalesceWithNetwork".equals(name)) coalesceCall[0] = position;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertTrue(bindCall[0] >= 0, "network binding must persist the current item stack");
        assertTrue(coalesceCall[0] >= 0, "network binding must retain coalescing support");
        assertTrue(bindCall[0] < coalesceCall[0],
                "the first generator must be persisted even when no existing disk is found");
    }
}
