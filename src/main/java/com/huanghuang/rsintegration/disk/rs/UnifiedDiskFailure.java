package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import net.minecraft.network.chat.Component;

import java.nio.file.NoSuchFileException;

/** 故障原因和恢复建议共用于悬浮提示与聊天，翻译留给玩家客户端。 */
public record UnifiedDiskFailure(Component reason, Component action) {
    private static final String PREFIX = "item.rs_integration.unified_storage_disk.failure.";

    public static UnifiedDiskFailure from(Exception error) {
        if (error instanceof FrozenKey.MissingResourceException missing) {
            return new UnifiedDiskFailure(Component.translatable(PREFIX + (missing.kind() == FrozenKey.Kind.ITEM
                    ? "missing_item" : "missing_fluid"), missing.resource().toString()),
                    Component.translatable(PREFIX + "restore_mod", missing.resource().getNamespace()));
        }
        return new UnifiedDiskFailure(Component.translatable(PREFIX + (error instanceof NoSuchFileException
                ? "missing_file" : "read_failed")), Component.translatable(PREFIX + "contact_admin"));
    }

    public static UnifiedDiskFailure wrongWorld() {
        return new UnifiedDiskFailure(Component.translatable(PREFIX + "wrong_world"),
                Component.translatable(PREFIX + "return_world"));
    }

    public static UnifiedDiskFailure invalidIdentity() {
        return new UnifiedDiskFailure(Component.translatable(PREFIX + "invalid_identity"),
                Component.translatable(PREFIX + "contact_admin"));
    }

    public static UnifiedDiskFailure disabled() {
        return new UnifiedDiskFailure(Component.translatable("item.rs_integration.unified_storage_disk.disabled"),
                Component.translatable(PREFIX + "enable_disk"));
    }
}
