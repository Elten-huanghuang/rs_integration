package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.mods.ModCraftNetworkHandlers;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ArsNouveauRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * Ars Nouveau integration module for RS.
 *
 * <p>Registers two independent {@link ModType}s:</p>
 * <ul>
 *   <li>{@code ars_nouveau_imbuement} — Imbuement Chamber (single block + optional pedestals)</li>
 *   <li>{@code ars_nouveau_apparatus} — Enchanting Apparatus (single block + radius-3 pedestals)</li>
 * </ul>
 *
 * <p>Both machines consume Source (magical energy) during crafting. Source is
 * a per-tile integer resource, NOT an item ingredient, and is handled by the
 * delegates as a soft throughput limiter.</p>
 */
public final class ArsNouveauRSModule implements IModIntegration {

    public static final ArsNouveauRSModule INSTANCE = new ArsNouveauRSModule();

    private ArsNouveauRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_ARS_NOUVEAU;
    }

    @Override
    public String modId() {
        return ModIds.ARS_NOUVEAU;
    }

    @Override
    public void registerModType() {
        // Imbuement Chamber
        ModType.register(
                ModIds.ID_ARS_IMBUEMENT,
                new String[]{"com.hollingsworth.arsnouveau.common.crafting.recipes.ImbuementRecipe"},
                new String[]{"imbuement", "chamber"},
                new String[]{"imbuement"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.arsnouveau.ArsImbuementBatchDelegate")
        );

        ModType.configureJei(
                ModIds.ID_ARS_IMBUEMENT,
                new String[][]{{"ars_nouveau:imbuement", "imbuement"}},
                new String[][]{{"com.hollingsworth.arsnouveau.common.crafting.recipes.ImbuementRecipe", "imbuement"}},
                "gui.rs_integration.jei.ars_nouveau_imbuement_craft"
        );

        // Enchanting Apparatus
        ModType.register(
                ModIds.ID_ARS_APPARATUS,
                new String[]{"com.hollingsworth.arsnouveau.api.enchanting_apparatus.EnchantingApparatusRecipe"},
                new String[]{"apparatus", "enchanting"},
                new String[]{"apparatus", "enchanting"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.arsnouveau.ArsApparatusBatchDelegate")
        );

        ModType.configureJei(
                ModIds.ID_ARS_APPARATUS,
                new String[][]{{"ars_nouveau:enchanting_apparatus", "apparatus"}},
                new String[][]{{"com.hollingsworth.arsnouveau.api.enchanting_apparatus.EnchantingApparatusRecipe", "apparatus"}},
                "gui.rs_integration.jei.ars_nouveau_apparatus_craft"
        );
    }

    @Override
    public void registerBindingTargets() {
        // Imbuement Chamber
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.ARS_NOUVEAU,
                ModType.byId(ModIds.ID_ARS_IMBUEMENT),
                RSIntegrationConfig.ENABLE_ARS_NOUVEAU,
                List.of("com.hollingsworth.arsnouveau.common.block.tile.ImbuementTile"),
                "ars_nouveau_imbuement"
        ));

        // Enchanting Apparatus
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.ARS_NOUVEAU,
                ModType.byId(ModIds.ID_ARS_APPARATUS),
                RSIntegrationConfig.ENABLE_ARS_NOUVEAU,
                List.of("com.hollingsworth.arsnouveau.common.block.tile.EnchantingApparatusTile"),
                "ars_nouveau_apparatus"
        ));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new ArsNouveauRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {
        ModCraftNetworkHandlers.registerArsNouveau();
    }

    @Override
    public void initCommon() {
        RSIntegrationMod.LOGGER.debug("Ars Nouveau RS module common init done.");
    }
}
