package com.huanghuang.rsintegration.mods.avaritia;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.AvaritiaRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;
import java.util.function.Supplier;

public final class AvaritiaRSModule implements IModIntegration {

    public static final AvaritiaRSModule INSTANCE = new AvaritiaRSModule();
    private AvaritiaRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() { return RSIntegrationConfig.ENABLE_AVARITIA; }

    @Override
    public String modId() { return "avaritia"; }

    @Override
    public void registerModType() {
        // Crafting tables — real IItemHandler insertion + assemble
        ModType.register("avaritia_crafting",
                new String[]{
                        "committee.nova.mods.avaritia.common.crafting.recipe.ShapedTableCraftingRecipe",
                        "committee.nova.mods.avaritia.common.crafting.recipe.ShapelessTableCraftingRecipe"
                },
                new String[]{"compressed_crafting_table", "double_compressed_crafting_table",
                        "end_crafting_table", "nether_crafting_table",
                        "sculk_crafting_table", "extreme_crafting_table"},
                new String[]{"avaritia_crafting"},
                CraftingTableBatchDelegate::new);

        // Compressors — real IItemHandler insertion + tick polling
        ModType.register("avaritia_compressor",
                new String[]{
                        "committee.nova.mods.avaritia.common.crafting.recipe.CompressorRecipe"
                },
                new String[]{"neutron_compressor", "dense_neutron_compressor",
                        "denser_neutron_compressor", "densest_neutron_compressor"},
                new String[]{"avaritia_compressor"},
                CompressorBatchDelegate::new);

        // Smithing table — virtual (no BlockEntity)
        ModType.register("avaritia_smithing",
                new String[]{
                        "committee.nova.mods.avaritia.common.crafting.recipe.ExtremeSmithingRecipe"
                },
                new String[]{"extreme_smithing_table"},
                new String[]{"avaritia_smithing"},
                com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate::new);

        // GUI-only machines — no recipes, no delegate
        ModType.register("avaritia_gui",
                new String[0], new String[0], new String[0], null);
    }

    @Override
    public void registerBindingTargets() {
        // ── Crafting tables ──
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "avaritia", ModType.byId("avaritia_crafting"),
                RSIntegrationConfig.ENABLE_AVARITIA,
                List.of(
                        "committee.nova.mods.avaritia.common.block.craft.CompressedCraftTableBlock",
                        "committee.nova.mods.avaritia.common.block.craft.DoubleCompressedCraftTableBlock",
                        "committee.nova.mods.avaritia.common.block.craft.TierCraftTableBlock"
                ),
                "avaritia_crafting"));

        // ── Neutron Compressors ──
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "avaritia", ModType.byId("avaritia_compressor"),
                RSIntegrationConfig.ENABLE_AVARITIA,
                List.of("committee.nova.mods.avaritia.common.block.compressor.NeutronCompressorBlock"),
                "avaritia_compressor"));

        // ── Extreme Smithing Table ──
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "avaritia", ModType.byId("avaritia_smithing"),
                RSIntegrationConfig.ENABLE_AVARITIA,
                List.of("committee.nova.mods.avaritia.common.block.extreme.ExtremeSmithingTableBlock"),
                "avaritia_smithing"));

        // ── Neutron Collector (no GUI, passive output only) ──
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "avaritia", ModType.byId("avaritia_gui"),
                RSIntegrationConfig.ENABLE_AVARITIA,
                List.of("committee.nova.mods.avaritia.common.block.collector.NeutronCollectorBlock"),
                "avaritia_gui", false));

        // ── GUI-only machines ──
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "avaritia", ModType.byId("avaritia_gui"),
                RSIntegrationConfig.ENABLE_AVARITIA,
                List.of(
                        "committee.nova.mods.avaritia.common.block.chest.CompressedChestBlock",
                        "committee.nova.mods.avaritia.common.block.chest.InfinityChestBlock",
                        "committee.nova.mods.avaritia.common.block.chest.TesseractBlock",
                        "committee.nova.mods.avaritia.common.block.extreme.ExtremeAnvilBlock"
                ),
                "avaritia_gui"));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new AvaritiaRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {}

    @Override
    public void initCommon() {}

    @Override
    public Supplier<DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> () -> {};
    }
}
