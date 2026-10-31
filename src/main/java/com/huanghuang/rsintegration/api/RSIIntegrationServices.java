package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.api.consumer.DefaultRSICraftConsumerApi;
import com.huanghuang.rsintegration.api.consumer.RSIConsumerServices;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.machine.gui.MachineGuiOpener;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.resonance.RSIResonanceServices;
import com.huanghuang.rsintegration.api.storage.DefaultRSIStorageApi;
import com.huanghuang.rsintegration.api.storage.RSIStorageServices;
import com.huanghuang.rsintegration.feature.resonance.api.DefaultRSIResonanceApi;
import com.huanghuang.rsintegration.network.gui.BlockGuiRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
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
        API.beginRegistration();
        RSIResonanceServices.install(new DefaultRSIResonanceApi());
        RSIStorageServices.install(new DefaultRSIStorageApi());
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
        RSIResonanceServices.clear();
        RSIStorageServices.clear();
    }

    public static void resumeAfterStop() {
        API.resumeAfterStop();
        RSIResonanceServices.install(new DefaultRSIResonanceApi());
        RSIStorageServices.install(new DefaultRSIStorageApi());
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
     * 供公共 GUI API 调用的内部注册桥接，避免公共类直接依赖网络实现。
     */
    public static boolean registerMachineGui(
            BlockEntityType<?> type,
            MachineGuiOpener opener
    ) {
        return BlockGuiRegistry.registerPreferred(
                Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(opener, "opener"));
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
