package com.huanghuang.rsintegration.client.recipe;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/** 新旧 JEI 共用的递归合成和机器界面按钮点击入口。 */
@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, value = Dist.CLIENT)
public final class JeiCraftButtonInputHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RECIPES_GUI = "mezz.jei.gui.recipes.RecipesGui";

    private JeiCraftButtonInputHandler() {}

    // 新版 JEI 在 Forge 的 Pre 事件中分发并拦截输入，不再覆写 mouseClicked。
    // 必须先处理自己的按钮；命中后取消事件，避免旧版或新版 JEI 重复响应。
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.isCanceled() || event.getButton() != 0) return;
        Screen screen = event.getScreen();
        // 按类名判断，避免未安装 JEI 时加载其可选实现类。
        if (screen == null || !RECIPES_GUI.equals(screen.getClass().getName())) return;

        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();
        int machineIndex = AltarCraftButtons.hitTestMachineGui(mouseX, mouseY);
        int craftIndex = machineIndex < 0 ? AltarCraftButtons.hitTest(mouseX, mouseY) : -1;
        if (machineIndex < 0 && craftIndex < 0) return;

        event.setCanceled(true);
        try {
            if (machineIndex >= 0) {
                LOGGER.debug("[RSI-RecipesGui] MachineGUI hit: index={} at ({},{})",
                        machineIndex, mouseX, mouseY);
                AltarCraftButtons.triggerMachineGui(machineIndex);
            } else {
                var data = AltarCraftButtons.getButtonData(craftIndex);
                LOGGER.debug("[RSI-RecipesGui] Plus hit: index={} at ({},{}) modType={} recipeId={}",
                        craftIndex, mouseX, mouseY, data.modType(), data.recipeId());
                AltarCraftButtons.triggerClick(craftIndex);
            }
        } catch (RuntimeException failure) {
            LOGGER.error("[RSI-RecipesGui] 按钮点击处理失败", failure);
        }
    }
}
