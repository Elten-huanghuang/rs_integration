package com.huanghuang.rsintegration.api.machine.gui;

import com.huanghuang.rsintegration.api.RSIIntegrationApi;
import com.huanghuang.rsintegration.api.RegistrationPhase;
import com.huanghuang.rsintegration.network.gui.BlockGuiRegistry;
import net.minecraft.world.level.block.entity.BlockEntityType;
import java.util.Objects;

/** Public registration API for menus on blocks whose native use mutates world state. */
public final class RSIMachineGuiApi {
    public static final int API_MAJOR = 1, API_MINOR = 0;
    private RSIMachineGuiApi() {}

    /**
     * Register during common setup (enqueueWork). The handler precedes Block.use;
     * native use is never called when this handler exists, even if opening fails.
     * Returns false for duplicate handlers or outside the registration phase.
     * Opening still goes through RSI's existing binding/permission validation.
     */
    public static boolean register(BlockEntityType<?> type, MachineGuiOpener opener) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(opener, "opener");
        if (RSIIntegrationApi.get().map(api -> api.phase() != RegistrationPhase.REGISTRATION).orElse(true)) {
            return false;
        }
        return BlockGuiRegistry.registerPreferred(type, opener);
    }
}
