package com.huanghuang.rsintegration.compat.emi;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.disk.UnifiedDiskVisibility;
import net.minecraft.resources.ResourceLocation;
import com.huanghuang.rsintegration.machine.BeyondDimensionsMachineHubClient;
import com.huanghuang.rsintegration.util.ModIds;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.widget.Bounds;
import net.minecraftforge.fml.ModList;

@EmiEntrypoint
public final class RSEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        ResourceLocation disk = new ResourceLocation("rs_integration", "unified_storage_disk");
        if (!UnifiedDiskVisibility.visible()) registry.removeEmiStacks(stack -> disk.equals(stack.getId()));
        // Recipe decorators are hidden by EMI's default show-recipe-decorators=false
        // setting. RecipeDisplayMixin attaches the player-facing buttons instead.
        if (ModList.get().isLoaded("beyonddimensions")) {
            registry.addGenericExclusionArea((screen, consumer) -> {
                for (var area : BeyondDimensionsMachineHubClient.getFavoriteExtraAreas(screen)) {
                    consumer.accept(new Bounds(
                            area.getX(), area.getY(), area.getWidth(), area.getHeight()));
                }
            });
        }
        if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
            try {
                Class<?> hooks = Class.forName(
                        "com.huanghuang.rsintegration.compat.emi.RSEmiOptionalHooks");
                hooks.getMethod("registerGridExclusion", EmiRegistry.class)
                        .invoke(null, registry);
            } catch (ReflectiveOperationException exception) {
                RSIntegrationMod.LOGGER.debug(
                        "[RSI-EMI] Refined Storage exclusion handler unavailable", exception);
            }
        }
    }
}
