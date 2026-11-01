package com.huanghuang.rsintegration.mods.jade;

import com.Polarice3.Goety.common.blocks.BrewCauldronBlock;
import com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/** Jade 仅在安装 Goety 时发现并加载此插件，未安装 Jade 时不触及其 API。 */
@WailaPlugin("goety")
public final class GoetyCauldronJadePlugin implements IWailaPlugin {
    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(CauldronJadeProvider.INSTANCE, BrewCauldronBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(CauldronJadeProvider.INSTANCE, BrewCauldronBlock.class);
    }
}
