package com.huanghuang.rsintegration.feature.goety.menu;

import com.huanghuang.rsintegration.feature.goety.GoetyCauldronFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class GoetyRsiMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, GoetyCauldronFeature.MOD_ID);
    public static final RegistryObject<MenuType<WitchCauldronMenu>> WITCH_CAULDRON = MENUS.register(
            "witch_cauldron", () -> IForgeMenuType.create((windowId, inventory, data) ->
                    WitchCauldronMenu.client(windowId, inventory, data.readBlockPos())));

    private GoetyRsiMenus() {}
}
