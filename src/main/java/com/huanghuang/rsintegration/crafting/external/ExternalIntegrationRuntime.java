package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.RSIIntegrationApi;
import com.huanghuang.rsintegration.api.RSIIntegrationServices;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 将冻结后的公共注册安装到 RSI 内部处理器与绑定索引。
 *
 * <p>所有公共配方共用一个内部逻辑 ModType；具体 process、机器和
 * execution 仍由公共 claim 与 descriptor 决定。</p>
 */
public final class ExternalIntegrationRuntime {
    private static volatile boolean initialized;

    private ExternalIntegrationRuntime() {}

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        RSIIntegrationApi api = RSIIntegrationApi.get().orElse(null);
        if (api == null) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-API] integration runtime initialized without API entry");
            return;
        }
        int recipeHandlers = 0;
        for (RSIIntegrationServices.RecipeRegistration registration
                : RSIIntegrationServices.recipeRegistrations()) {
            ModRecipeHandlers.register(new ExternalRecipeHandler(
                    registration.integration(),
                    ModType.EXTERNAL_INTEGRATION,
                    registration.options().priority(),
                    recipe -> api.recipes().claim(recipe).winner()
                            == registration.integration()
            ));
            recipeHandlers++;
        }

        LinkedHashSet<String> machineNamespaces = new LinkedHashSet<>();
        for (RSIIntegrationServices.MachineRegistration registration
                : RSIIntegrationServices.machineRegistrations()) {
            ResourceLocation id = registration.integration().id();
            if (!machineNamespaces.add(id.getNamespace())) continue;
            BindingEventHandler.registerTarget(
                    new BindingEventHandler.MachineBindingTarget(
                            id.getNamespace(),
                            ModType.EXTERNAL_INTEGRATION,
                            RSIntegrationConfig.ENABLE_BINDING,
                            List.of(),
                            List.of(id.getNamespace() + ":*"),
                            ModType.EXTERNAL_INTEGRATION.id(),
                            false
                    )
            );
        }
        RSIntegrationMod.LOGGER.info(
                "[RSI-API] installed {} recipe claims and {} machine namespaces",
                recipeHandlers, machineNamespaces.size());
    }

    public static boolean isExternalType(ModType type) {
        return type == ModType.EXTERNAL_INTEGRATION;
    }
}
