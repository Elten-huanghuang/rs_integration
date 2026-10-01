package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.disk.UnifiedDiskVisibility;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

/** 登录时更新展示，切换服务器不会沿用上一台服务器的开关。 */
@OnlyIn(Dist.CLIENT)
public final class UnifiedDiskClientVisibility {
    private UnifiedDiskClientVisibility() {}

    public static void fromServer(boolean enabled) {
        UnifiedDiskVisibility.fromServer(enabled);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null && minecraft.getConnection() != null) {
            var parameters = new CreativeModeTab.ItemDisplayParameters(minecraft.getConnection().enabledFeatures(),
                    minecraft.player.canUseGameMasterBlocks(), minecraft.level.registryAccess());
            ModItems.RSI_TAB.get().buildContents(parameters);
            CreativeModeTabs.searchTab().buildContents(parameters);
        }
        if (ModList.get().isLoaded("jei")) RSJeiPlugin.refreshUnifiedDiskVisibility();
        if (ModList.get().isLoaded("emi")) {
            // EMI 重载接口不属于稳定 API，反射隔离版本差异及可选依赖。
            try { Class.forName("dev.emi.emi.runtime.EmiReloadManager").getMethod("reload").invoke(null); }
            catch (ReflectiveOperationException exception) {
                RSIntegrationMod.LOGGER.warn("[RSI] EMI 统一盘显示刷新失败", exception);
            }
        }
    }
}
