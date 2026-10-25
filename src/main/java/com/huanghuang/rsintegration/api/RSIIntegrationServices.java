package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@ApiStatus.Internal
public final class RSIIntegrationServices {
    private static final DefaultRSIIntegrationApi API = new DefaultRSIIntegrationApi();
    private static volatile boolean initialized;

    private RSIIntegrationServices() {}

    public static synchronized void initialize() {
        initialized = true;
    }

    public static void beginRegistration() {
        API.beginRegistration();
    }

    public static void freezeRegistration() {
        API.freezeRegistration();
    }

    public static void stop() {
        API.stop();
    }

    public static void resumeAfterStop() {
        API.resumeAfterStop();
    }

    /**
     * 仅供 RSI 内部适配器读取已冻结的配方注册，不属于第三方扩展面。
     */
    public static List<RecipeRegistration>
    recipeRegistrations() {
        return API.recipeRegistrations();
    }

    /**
     * 仅供 RSI 内部适配器读取已冻结的机器注册。
     */
    public static List<MachineRegistration> machineRegistrations() {
        return API.machineRegistrations();
    }

    public record RecipeRegistration(
            RecipeIntegration<?> integration,
            RegistrationOptions options
    ) {
        public RecipeRegistration {
            Objects.requireNonNull(integration, "integration");
            Objects.requireNonNull(options, "options");
        }
    }

    public record MachineRegistration(
            MachineIntegration integration,
            RegistrationOptions options
    ) {
        public MachineRegistration {
            Objects.requireNonNull(integration, "integration");
            Objects.requireNonNull(options, "options");
        }
    }

    static Optional<RSIIntegrationApi> current() {
        return initialized ? Optional.of(API) : Optional.empty();
    }
}
