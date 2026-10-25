package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.api.consumer.DefaultRSICraftConsumerApi;
import com.huanghuang.rsintegration.api.consumer.RSIConsumerServices;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@ApiStatus.Internal
public final class RSIIntegrationServices {
    private static final DefaultRSIIntegrationApi API = new DefaultRSIIntegrationApi();
    private static final DefaultRSICraftConsumerApi CONSUMER =
            new DefaultRSICraftConsumerApi();
    private static volatile boolean initialized;

    private RSIIntegrationServices() {}

    public static synchronized void initialize() {
        initialized = true;
        RSIConsumerServices.install(CONSUMER);
        CONSUMER.markIndexing();
    }

    public static void beginRegistration() {
        API.beginRegistration();
    }

    public static void freezeRegistration() {
        API.freezeRegistration();
    }

    public static void stop() {
        API.stop();
        CONSUMER.stop();
    }

    public static void resumeAfterStop() {
        API.resumeAfterStop();
        CONSUMER.markIndexing();
    }

    public static void tick(ServerLevel level) {
        if (initialized) {
            CONSUMER.tick(level);
        }
    }

    public static void clearPlayer(UUID playerId) {
        CONSUMER.clearPlayer(playerId);
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
