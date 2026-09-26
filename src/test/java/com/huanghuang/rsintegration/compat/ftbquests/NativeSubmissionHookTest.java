package com.huanghuang.rsintegration.compat.ftbquests;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
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
    private static final String QUEST_CLASS =
            "dev/ftb/mods/ftbquests/quest/Quest.class";
    private static final String CHAPTER_CLASS =
            "dev/ftb/mods/ftbquests/quest/Chapter.class";
    private static final String TEAM_DATA_CLASS =
            "dev/ftb/mods/ftbquests/quest/TeamData.class";
    private static final String SUBMIT_HANDLE_DESCRIPTOR =
            "(Ldev/architectury/networking/NetworkManager$PacketContext;)V";
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources",
            "rs_integration.mixins.json");
    private static final Path SERVICE = Path.of("src", "main", "java", "com", "huanghuang",
            "rsintegration", "compat", "ftbquests", "NativeItemTaskSubmissionService.java");
    private static final Path QUEST_SERVICE = Path.of("src", "main", "java", "com", "huanghuang",
            "rsintegration", "compat", "ftbquests", "FtbQuestSubmissionService.java");
    private static final Path QUEST_ESCROW = Path.of("src", "main", "java", "com", "huanghuang",
            "rsintegration", "compat", "ftbquests", "QuestSubmissionEscrow.java");
    private static final Path CHECKMARK_SERVICE = Path.of("src", "main", "java", "com", "huanghuang",
            "rsintegration", "compat", "ftbquests", "CheckmarkConfirmService.java");
    private static final Path REWARD_MIXIN = Path.of("src", "main", "java", "com", "huanghuang",
            "rsintegration", "mixin", "ftbquests", "ItemRewardMixin.java");
    private static final Path CURIOS_EVENTS = Path.of("src", "main", "java", "com", "huanghuang",
            "rsintegration", "compat", "ftbquests", "FtbQuestCuriosScanEvents.java");

    @Test
    void rsFallbackIsScopedToExplicitSubmitPackets() throws IOException {
        String config = Files.readString(MIXIN_CONFIG, StandardCharsets.UTF_8);
        String service = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue(config.contains("ftbquests.SubmitTaskMessageMixin"));
        assertTrue(config.contains("ftbquests.InventoryTaskAutoSubmissionMixin"));
        assertTrue(config.contains("ftbquests.TeamDataAutoCompletionMixin"),
                "automatic reward/reset must be deferred during item settlement");
        assertFalse(config.contains("ftbquests.ItemTaskSubmissionMixin"),
                "ItemTask.submitTask is also called by automatic inventory detection");
        assertTrue(service.contains("reserveUpToFromMainInventoryThenNetwork"),
                "one transaction must own inventory-first and RS-fallback consumption");
        assertTrue(service.contains("QuestInventorySubmissionContext.open()"),
                "only RSI's physical settlement may suppress inventory-listener re-entry");
        assertTrue(service.contains("reason=no-display-item"),
                "an unavailable filter preview must fall back to FTB's native submit path");
        assertTrue(service.contains("if (reserved <= 0)"));
        assertTrue(service.contains("return false;\n            }"),
                "a zero-reservation ledger attempt must not cancel native inventory submission");
        assertFalse(service.contains("afterNativeSubmission"),
                "post-native settlement can reuse inventory-counted progress");

        String submitMixin = Files.readString(Path.of("src", "main", "java", "com", "huanghuang",
                "rsintegration", "mixin", "ftbquests", "SubmitTaskMessageMixin.java"),
                StandardCharsets.UTF_8);
        assertTrue(submitMixin.contains("method = \"handle\""),
                "custom settlement must hook FTB's stable explicit-submit entry point");
        assertTrue(submitMixin.contains("file.withPlayerContext(player"),
                "custom item submission must retain FTB's player context");
        assertTrue(submitMixin.contains("if (handled[0]) ci.cancel()"),
                "the native packet may only be cancelled after RSI owns the submission");
        assertFalse(submitMixin.contains("ci.cancel();\n        file.withPlayerContext"),
                "the original packet must remain live while RSI decides whether to handle it");
        assertFalse(submitMixin.contains("task.submitTask(data, player)"),
                "fallback must continue the original version-specific packet handler");
        assertFalse(submitMixin.contains("getMethod(\"getPlayer\")"),
                "reflecting on Architectury's runtime context implementation is version-fragile");

        String inventoryMixin = Files.readString(Path.of("src", "main", "java", "com", "huanghuang",
                "rsintegration", "mixin", "ftbquests", "InventoryTaskAutoSubmissionMixin.java"),
                StandardCharsets.UTF_8);
        assertTrue(inventoryMixin.contains("QuestInventorySubmissionContext.isSuppressed()"),
                "native FTB inventory submission must remain enabled outside RSI settlement");
    }

    @Test
    void jeiQuestSubmissionSupportsInventoryWithoutAStorageNetwork() throws IOException {
        String service = Files.readString(QUEST_SERVICE, StandardCharsets.UTF_8);
        String escrow = Files.readString(QUEST_ESCROW, StandardCharsets.UTF_8);

        assertFalse(service.contains("if (endpoint == null)"),
                "JEI quest preview and execution must keep inventory-only planning available");
        assertTrue(escrow.contains("network != null\n                ? ledger.reserveFromNetwork"),
                "network reservation must only run when a network exists");
        assertTrue(escrow.contains("ledger.reserveFromInventory"),
                "inventory reservation must remain the no-network fallback");
    }

    @Test
    void bulkCheckmarksRequireTheContainingChapterToBeVisible() throws IOException {
        String service = Files.readString(CHECKMARK_SERVICE, StandardCharsets.UTF_8);
        assertTrue(service.contains("quest.getChapter().isVisible(data)"),
                "hidden chapters must not be completed by bulk checkmark confirmation");
    }

    @Test
    void bulkCheckmarksRequireCompletedQuestDependencies() throws IOException {
        String service = Files.readString(CHECKMARK_SERVICE, StandardCharsets.UTF_8);
        assertTrue(service.contains("data.areDependenciesComplete(quest)"),
                "flexible progression must not let bulk confirmation create premature checkmark progress");
    }

    @Test
    void rewardAndCuriosChangesSchedulePlayerItemRescan() throws IOException {
        String rewardMixin = Files.readString(REWARD_MIXIN, StandardCharsets.UTF_8);
        String curiosEvents = Files.readString(CURIOS_EVENTS, StandardCharsets.UTF_8);
        String claimAllMixin = Files.readString(Path.of("src", "main", "java", "com", "huanghuang",
                "rsintegration", "mixin", "ftbquests", "ClaimAllRewardsMessageMixin.java"),
                StandardCharsets.UTF_8);
        String scanService = Files.readString(Path.of("src", "main", "java", "com", "huanghuang",
                "rsintegration", "compat", "ftbquests", "StorageQuestScanService.java"),
                StandardCharsets.UTF_8);

        assertTrue(rewardMixin.contains("StorageQuestScanService.schedulePlayerItemScan(player)"));
        assertTrue(claimAllMixin.contains("StorageQuestScanService.schedulePlayerItemScan(player)"));
        assertTrue(curiosEvents.contains("getMethod(\"getTo\")"));
        assertTrue(curiosEvents.contains("StorageQuestScanService.schedulePlayerItemScan(player)"));
        assertTrue(scanService.contains("QuestScanItems.fromPlayer(player, curios)"));
        assertTrue(scanService.contains("PENDING_PLAYER_ITEM_SCANS"));
        assertTrue(scanService.contains("isAvailableAfterRewardClaim(data, itemTask)"));
        assertTrue(scanService.contains("private static boolean isAvailableAfterRewardClaim"));
    }

    @Test
    void supportedFtbVersionsExposeTheExplicitSubmitEntryPoint() throws IOException {
        List<Path> jars = List.of(
                Path.of("libs", "[FTB任务]ftb-quests-forge-2001.4.10.jar"),
                Path.of("libs", "ftb-quests-forge-2001.4.13.jar"),
                Path.of("libs", "[FTB任务-魔改] ftb-quests-forge-2001.4.20.jar"),
                Path.of("libs", "[FTB 任务] ftb-quests-forge-2001.4.22.jar"));
        for (Path jar : jars) {
            AtomicBoolean foundHandle = new AtomicBoolean();
            AtomicBoolean foundTaskId = new AtomicBoolean();
            AtomicBoolean foundLockCheck = new AtomicBoolean();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                var entry = zip.getEntry(MESSAGE_CLASS);
                assertTrue(entry != null, () -> jar + " is missing " + MESSAGE_CLASS);
                try (var input = zip.getInputStream(entry)) {
                    new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public FieldVisitor visitField(
                                int access, String name, String descriptor,
                                String signature, Object value) {
                            if (name.equals("taskId") && descriptor.equals("J")) {
                                foundTaskId.set(true);
                            }
                            return null;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                         String signature, String[] exceptions) {
                            if (name.equals("handle")
                                    && descriptor.equals(SUBMIT_HANDLE_DESCRIPTOR)) {
                                foundHandle.set(true);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode, String owner, String methodName,
                                            String methodDescriptor, boolean isInterface) {
                                        if (opcode == Opcodes.INVOKEVIRTUAL
                                                && owner.equals(
                                                "dev/ftb/mods/ftbquests/quest/TeamData")
                                                && methodName.equals("isLocked")
                                                && methodDescriptor.equals("()Z")) {
                                            foundLockCheck.set(true);
                                        }
                                    }
                                };
                            }
                            return null;
                        }
                    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
            assertTrue(foundHandle.get(), () -> jar + " changed its explicit-submit entry contract");
            assertTrue(foundTaskId.get(), () -> jar + " changed its submit-task id field contract");
            assertTrue(foundLockCheck.get(), () -> jar + " changed its submit lock-check contract");
            assertInventoryListenerContract(jar);
            assertItemRewardContract(jar);
            assertClaimAllRewardsContract(jar);
            assertCompletionTimestampContract(jar);
            assertRepeatableContract(jar);
            assertChapterVisibilityContract(jar);
        }
    }

    private static void assertChapterVisibilityContract(Path jar) throws IOException {
        AtomicBoolean foundGetChapter = new AtomicBoolean();
        AtomicBoolean foundChapterVisibility = new AtomicBoolean();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var questEntry = zip.getEntry(QUEST_CLASS);
            assertTrue(questEntry != null, () -> jar + " is missing " + QUEST_CLASS);
            try (var input = zip.getInputStream(questEntry)) {
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        if (name.equals("getChapter")
                                && descriptor.equals(
                                "()Ldev/ftb/mods/ftbquests/quest/Chapter;")) {
                            foundGetChapter.set(true);
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }

            var chapterEntry = zip.getEntry(CHAPTER_CLASS);
            assertTrue(chapterEntry != null, () -> jar + " is missing " + CHAPTER_CLASS);
            try (var input = zip.getInputStream(chapterEntry)) {
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        if (name.equals("isVisible") && descriptor.equals(
                                "(Ldev/ftb/mods/ftbquests/quest/TeamData;)Z")) {
                            foundChapterVisibility.set(true);
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        assertTrue(foundGetChapter.get(), () -> jar + " changed the quest chapter contract");
        assertTrue(foundChapterVisibility.get(),
                () -> jar + " changed the chapter visibility contract");
    }

    private static void assertCompletionTimestampContract(Path jar) throws IOException {
        AtomicBoolean foundGet = new AtomicBoolean();
        AtomicBoolean foundSet = new AtomicBoolean();
        AtomicBoolean foundOnlineMembers = new AtomicBoolean();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(TEAM_DATA_CLASS);
            assertTrue(entry != null, () -> jar + " is missing " + TEAM_DATA_CLASS);
            try (var input = zip.getInputStream(entry)) {
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        if (name.equals("getCompletedTime")
                                && descriptor.equals("(J)Ljava/util/Optional;")) foundGet.set(true);
                        if (name.equals("setCompleted")
                                && descriptor.equals("(JLjava/util/Date;)Z")) foundSet.set(true);
                        if (name.equals("getOnlineMembers")
                                && descriptor.equals("()Ljava/util/Collection;")) {
                            foundOnlineMembers.set(true);
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        assertTrue(foundGet.get(), () -> jar + " changed completion timestamp reads");
        assertTrue(foundSet.get(), () -> jar + " changed completion timestamp writes");
        assertTrue(foundOnlineMembers.get(),
                () -> jar + " changed the online-team-member contract");
    }

    private static void assertRepeatableContract(Path jar) throws IOException {
        AtomicBoolean found = new AtomicBoolean();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(QUEST_CLASS);
            assertTrue(entry != null, () -> jar + " is missing " + QUEST_CLASS);
            try (var input = zip.getInputStream(entry)) {
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        if (name.equals("checkRepeatable")
                                && (descriptor.equals("(Ldev/ftb/mods/ftbquests/quest/TeamData;Ljava/util/UUID;)V")
                                || descriptor.equals("(Ldev/ftb/mods/ftbquests/quest/TeamData;Ljava/util/UUID;)Z"))) {
                            found.set(true);
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        assertTrue(found.get(), () -> jar + " changed its repeatable reset contract");
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
