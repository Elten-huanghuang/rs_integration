package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 为选定配方附加客户端持久偏好，不改写目标配方。 */
public final class ClientPlanningPreferences {
    private static boolean migrationAttempted;

    private ClientPlanningPreferences() {}

    public static GenericCraftPacket apply(GenericCraftPacket packet) {
        return apply(packet, preferencesPath());
    }

    static GenericCraftPacket apply(GenericCraftPacket packet, Path path) {
        CraftingPlanPreferences.Snapshot snapshot =
                CraftingPlanPreferences.load(path);
        return packet.withPlanningPreferences(snapshot.toPlanningPreferences());
    }

    static synchronized Path preferencesPath() {
        Path current = currentPath();
        Path legacy = legacyPath();
        if (!migrationAttempted) {
            migrationAttempted = Files.isRegularFile(current)
                    || !Files.isRegularFile(legacy)
                    || migrateLegacy(legacy, current);
        }
        return current;
    }

    static boolean migrateLegacy(Path legacy, Path current) {
        if (legacy == null || current == null
                || !Files.isRegularFile(legacy) || Files.exists(current)) {
            return false;
        }
        try {
            Path parent = current.getParent();
            if (parent != null) Files.createDirectories(parent);
            try {
                Files.move(legacy, current, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(legacy, current);
            }
            return Files.isRegularFile(current);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Path currentPath() {
        return FMLPaths.GAMEDIR.get()
                .resolve("rs_integration").resolve("crafting_plan.json");
    }

    private static Path legacyPath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve("rs_integration").resolve("crafting_plan.json");
    }
}
