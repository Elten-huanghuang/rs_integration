package com.huanghuang.rsintegration.compat.ftbquests;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeSubmissionHookTest {
    private static final String MESSAGE_CLASS =
            "dev/ftb/mods/ftbquests/net/SubmitTaskMessage.class";
    private static final String INVENTORY_LISTENER_CLASS =
            "dev/ftb/mods/ftbquests/util/FTBQuestsInventoryListener.class";
    private static final String ITEM_REWARD_CLASS =
            "dev/ftb/mods/ftbquests/quest/reward/ItemReward.class";
    private static final String CLAIM_ALL_REWARDS_CLASS =
            "dev/ftb/mods/ftbquests/net/ClaimAllRewardsMessage.class";
    private static final String SUBMIT_CALLBACK_DESCRIPTOR =
            "(Ldev/ftb/mods/ftbquests/quest/task/Task;"
                    + "Ldev/ftb/mods/ftbquests/quest/TeamData;"
                    + "Lnet/minecraft/server/level/ServerPlayer;)V";
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources",
            "rs_integration.mixins.json");
    private static final Path SERVICE = Path.of("src", "main", "java", "com", "huanghuang",
            "rsintegration", "compat", "ftbquests", "NativeItemTaskSubmissionService.java");

    @Test
    void rsFallbackIsScopedToExplicitSubmitPackets() throws IOException {
        String config = Files.readString(MIXIN_CONFIG, StandardCharsets.UTF_8);
        String service = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue(config.contains("ftbquests.SubmitTaskMessageMixin"));
        assertTrue(config.contains("ftbquests.InventoryTaskAutoSubmissionMixin"));
        assertFalse(config.contains("ftbquests.ItemTaskSubmissionMixin"),
                "ItemTask.submitTask is also called by automatic inventory detection");
        assertTrue(service.contains("reserveUpToFromMainInventoryThenNetwork"),
                "one transaction must own inventory-first and RS-fallback consumption");
        assertTrue(service.contains("if (display.isEmpty()) return false;"),
                "an unavailable filter preview must fall back to FTB's native submit path");
        assertFalse(service.contains("afterNativeSubmission"),
                "post-native settlement can reuse inventory-counted progress");
    }

    @Test
    void supportedFtbVersionsExposeTheExplicitSubmitCallback() throws IOException {
        List<Path> jars = List.of(
                Path.of("libs", "ftb-quests-forge-2001.4.13.jar"),
                Path.of("libs", "[FTB 任务] ftb-quests-forge-2001.4.22.jar"));
        for (Path jar : jars) {
            AtomicBoolean found = new AtomicBoolean();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                var entry = zip.getEntry(MESSAGE_CLASS);
                assertTrue(entry != null, () -> jar + " is missing " + MESSAGE_CLASS);
                try (var input = zip.getInputStream(entry)) {
                    new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                         String signature, String[] exceptions) {
                            if (name.equals("lambda$handle$0")
                                    && descriptor.equals(SUBMIT_CALLBACK_DESCRIPTOR)) {
                                found.set(true);
                            }
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
            assertTrue(found.get(), () -> jar + " changed its explicit-submit callback contract");
            assertInventoryListenerContract(jar);
            assertItemRewardContract(jar);
            assertClaimAllRewardsContract(jar);
        }
    }

    private static void assertInventoryListenerContract(Path jar) throws IOException {
        AtomicBoolean found = new AtomicBoolean();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(INVENTORY_LISTENER_CLASS);
            assertTrue(entry != null, () -> jar + " is missing " + INVENTORY_LISTENER_CLASS);
            try (var input = zip.getInputStream(entry)) {
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        if (!name.equals("lambda$detect$0")) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String methodName,
                                                        String methodDescriptor, boolean isInterface) {
                                if (owner.equals("dev/ftb/mods/ftbquests/quest/task/Task")
                                        && methodName.equals("submitTask")
                                        && methodDescriptor.equals(
                                        "(Ldev/ftb/mods/ftbquests/quest/TeamData;"
                                                + "Lnet/minecraft/server/level/ServerPlayer;"
                                                + "Lnet/minecraft/world/item/ItemStack;)V")) {
                                    found.set(true);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        assertTrue(found.get(), () -> jar + " changed its inventory-detection submit contract");
    }

    private static void assertItemRewardContract(Path jar) throws IOException {
        AtomicBoolean found = new AtomicBoolean();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(ITEM_REWARD_CLASS);
            assertTrue(entry != null, () -> jar + " is missing " + ITEM_REWARD_CLASS);
            try (var input = zip.getInputStream(entry)) {
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        if (!name.equals("claim") || !descriptor.equals(
                                "(Lnet/minecraft/server/level/ServerPlayer;Z)V")) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String methodName,
                                                        String methodDescriptor, boolean isInterface) {
                                if (opcode == Opcodes.INVOKESTATIC
                                        && owner.equals("dev/architectury/hooks/item/ItemStackHooks")
                                        && methodName.equals("giveItem")
                                        && methodDescriptor.equals(
                                        "(Lnet/minecraft/server/level/ServerPlayer;"
                                                + "Lnet/minecraft/world/item/ItemStack;)V")) {
                                    found.set(true);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        assertTrue(found.get(), () -> jar + " changed its item-reward delivery contract");
    }

    private static void assertClaimAllRewardsContract(Path jar) throws IOException {
        AtomicBoolean found = new AtomicBoolean();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(CLAIM_ALL_REWARDS_CLASS);
            assertTrue(entry != null, () -> jar + " is missing " + CLAIM_ALL_REWARDS_CLASS);
            try (var input = zip.getInputStream(entry)) {
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        if (!name.equals("lambda$handle$1")) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String methodName,
                                                        String methodDescriptor, boolean isInterface) {
                                if (opcode == Opcodes.INVOKEVIRTUAL
                                        && owner.equals("dev/ftb/mods/ftbquests/quest/TeamData")
                                        && methodName.equals("claimReward")
                                        && methodDescriptor.equals(
                                        "(Lnet/minecraft/server/level/ServerPlayer;"
                                                + "Ldev/ftb/mods/ftbquests/quest/reward/Reward;Z)V")) {
                                    found.set(true);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        assertTrue(found.get(), () -> jar + " changed its claim-all reward contract");
    }
}
