package com.huanghuang.rsintegration.mods.avaritia;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
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
        ModType.register(ModIds.ID_AVARITIA_CRAFTING,
                new String[]{
                        "committee.nova.mods.avaritia.common.crafting.recipe.ShapedTableCraftingRecipe",
                        "committee.nova.mods.avaritia.common.crafting.recipe.ShapelessTableCraftingRecipe"
                },
                new String[]{"compressed_crafting_table", "double_compressed_crafting_table",
                        "end_crafting_table", "nether_crafting_table",
                        "sculk_crafting_table", "extreme_crafting_table"},
                new String[]{ModIds.ID_AVARITIA_CRAFTING},
                CraftingTableBatchDelegate::new);

        // Compressors — real IItemHandler insertion + tick polling
        ModType.register(ModIds.ID_AVARITIA_COMPRESSOR,
                new String[]{
                        "committee.nova.mods.avaritia.common.crafting.recipe.CompressorRecipe"
                },
                new String[]{"neutron_compressor", "dense_neutron_compressor",
                        "denser_neutron_compressor", "densest_neutron_compressor"},
                new String[]{ModIds.ID_AVARITIA_COMPRESSOR},
                CompressorBatchDelegate::new);

        // Smithing table — virtual (no BlockEntity)
        ModType.register(ModIds.ID_AVARITIA_SMITHING,
                new String[]{
                        "committee.nova.mods.avaritia.common.crafting.recipe.ExtremeSmithingRecipe"
                },
                new String[]{"extreme_smithing_table"},
                new String[]{ModIds.ID_AVARITIA_SMITHING},
                GenericBatchDelegate::new);

        // GUI-only machines — no recipes, no delegate
        ModType.register("avaritia_gui",
                new String[0], new String[0], new String[0], null);
        ModType.configureJei(ModIds.ID_AVARITIA_CRAFTING,
                null,
                new String[][]{{"committee.nova.mods.avaritia.common.crafting.recipe.", ModIds.ID_AVARITIA_CRAFTING}},
                null);
        ModType.configureJei(ModIds.ID_AVARITIA_COMPRESSOR,
                null,
                new String[][]{{"committee.nova.mods.avaritia.common.crafting.recipe.CompressorRecipe", ModIds.ID_AVARITIA_COMPRESSOR}},
                null);
        ModType.configureJei(ModIds.ID_AVARITIA_SMITHING,
                null,
                new String[][]{{"committee.nova.mods.avaritia.common.crafting.recipe.ExtremeSmithingRecipe", ModIds.ID_AVARITIA_SMITHING}},
                null);
    }

    @Override
    public void registerBindingTargets() {
        // ── Crafting tables ──
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "avaritia", ModType.byId(ModIds.ID_AVARITIA_CRAFTING),
                RSIntegrationConfig.ENABLE_AVARITIA,
                List.of(
                        "committee.nova.mods.avaritia.common.block.craft.CompressedCraftTableBlock",
                        "committee.nova.mods.avaritia.common.block.craft.DoubleCompressedCraftTableBlock",
                        "committee.nova.mods.avaritia.common.block.craft.TierCraftTableBlock"
                ),
                ModIds.ID_AVARITIA_CRAFTING));

        // ── Neutron Compressors ──
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "avaritia", ModType.byId(ModIds.ID_AVARITIA_COMPRESSOR),
                RSIntegrationConfig.ENABLE_AVARITIA,
                List.of("committee.nova.mods.avaritia.common.block.compressor.NeutronCompressorBlock"),
                ModIds.ID_AVARITIA_COMPRESSOR));

        // ── Extreme Smithing Table ──
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "avaritia", ModType.byId(ModIds.ID_AVARITIA_SMITHING),
                RSIntegrationConfig.ENABLE_AVARITIA,
                List.of("committee.nova.mods.avaritia.common.block.extreme.ExtremeSmithingTableBlock"),
                ModIds.ID_AVARITIA_SMITHING));

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
