package com.huanghuang.rsintegration.crafting.plan;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;

/**
 * 结构化诊断。服务端只发送代码和参数，最终语言文本由客户端生成。
 */
public record IntegrationDiagnostic(
        IntegrationDiagnosticCode code,
        Severity severity,
        List<String> arguments
) {
    public IntegrationDiagnostic {
        Objects.requireNonNull(code, "code");
        severity = severity == null ? Severity.ERROR : severity;
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    public static IntegrationDiagnostic error(IntegrationDiagnosticCode code,
                                              String... arguments) {
        return new IntegrationDiagnostic(code, Severity.ERROR, List.of(arguments));
    }

    public static IntegrationDiagnostic warning(IntegrationDiagnosticCode code,
                                                String... arguments) {
        return new IntegrationDiagnostic(code, Severity.WARNING, List.of(arguments));
    }

    /** 只能在客户端调用，避免专用服务器提前解析语言文本。 */
    public Component clientMessage() {
        return Component.translatable(code.translationKey(), arguments.toArray());
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}
