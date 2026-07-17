package com.huanghuang.rsintegration.mods.ironfurnaces;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class IronFurnacesRSModule implements IModIntegration {

    public static final IronFurnacesRSModule INSTANCE = new IronFurnacesRSModule();
    public static final String TYPE_ID = "ironfurnaces_furnace";
    public static final String BLAST_TYPE_ID = "ironfurnaces_blast_furnace";
    public static final String SMOKER_TYPE_ID = "ironfurnaces_smoker";

    private IronFurnacesRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_IRON_FURNACES;
    }

    @Override
    public String modId() {
        return ModIds.IRON_FURNACES;
    }

    @Override
    public void registerModType() {
        // Binding-only type. Cooking recipes retain their vanilla machine types
        // and resolve this binding through AltarBindingRegistry's compatibility rule.
        ModType.register(TYPE_ID,
                new String[0],
                new String[]{"ironfurnaces"},
                new String[]{TYPE_ID},
                IronFurnacesBatchDelegate::new);
        ModType.register(BLAST_TYPE_ID, new String[0], new String[0],
                new String[]{BLAST_TYPE_ID}, IronFurnacesBatchDelegate::new);
        ModType.register(SMOKER_TYPE_ID, new String[0], new String[0],
                new String[]{SMOKER_TYPE_ID}, IronFurnacesBatchDelegate::new);
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.IRON_FURNACES, ModType.byId(TYPE_ID),
                RSIntegrationConfig.ENABLE_IRON_FURNACES,
                List.of("ironfurnaces.blocks.furnaces.BlockIronFurnaceBase"),
                TYPE_ID, true));
    }

    @Override
    public void registerRecipeHandler() {}

    @Override
    public void registerNetworkPackets() {}

    @Override
    public void initCommon() {}
}
