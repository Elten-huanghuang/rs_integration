package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.mods.aether.AetherFurnaceBatchDelegate;
import com.huanghuang.rsintegration.mods.aetherworks.AetherworksBatchDelegate;
import com.huanghuang.rsintegration.mods.aetherworks.AetherworksToolStationBatchDelegate;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotBatchDelegate;
import com.huanghuang.rsintegration.mods.eidolon.EidolonBatchDelegate;
import com.huanghuang.rsintegration.mods.farmersdelight.CookingPotBatchDelegate;
import com.huanghuang.rsintegration.mods.farmersdelight.SkilletBatchDelegate;
import com.huanghuang.rsintegration.mods.immortalersdelight.EnchantalCoolerBatchDelegate;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyBatchDelegate;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketBatchDelegate;
import com.huanghuang.rsintegration.mods.forbidden.FaBatchDelegate;
import com.huanghuang.rsintegration.mods.goety.GoetyBatchDelegate;
import com.huanghuang.rsintegration.mods.malum.MalumBatchDelegate;
import com.huanghuang.rsintegration.mods.touhoulittlemaid.TlmAltarBatchDelegate;
import com.huanghuang.rsintegration.mods.wizardsreborn.WRBatchDelegate;
import com.huanghuang.rsintegration.mods.youkaishomecoming.ferment.FermentationTankBatchDelegate;
import com.huanghuang.rsintegration.mods.youkaishomecoming.moka.MokaPotBatchDelegate;
import com.huanghuang.rsintegration.mods.youkaishomecoming.steamer.SteamerBatchDelegate;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class PlanWarnings {
    private PlanWarnings() {}

    public static List<String> collect(String typeId, ServerPlayer player, Recipe<?> recipe,
                                        @Nullable ResourceLocation dim, @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        switch (typeId) {
            case ModIds.AETHER:
            case "aether_freezer":
            case "aether_incubator":
            case "aether_altar":
                warnings.addAll(AetherFurnaceBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.GOETY:
                warnings.addAll(GoetyBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.FORBIDDEN_ARCANUS:
                warnings.addAll(FaBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.WIZARDS_REBORN:
                warnings.addAll(WRBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.MALUM:
                warnings.addAll(MalumBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.EIDOLON:
                warnings.addAll(EidolonBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_EMBERS_ALCHEMY:
                warnings.addAll(EreAlchemyBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_AETHERWORKS_ANVIL:
                warnings.addAll(AetherworksBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_AETHERWORKS_TOOL_STATION:
                warnings.addAll(AetherworksToolStationBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.CROCKPOT:
                warnings.addAll(CrockPotBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_FR_KETTLE:
                warnings.addAll(com.huanghuang.rsintegration.mods.farmersrespite.kettle.FRKettleBatchDelegate
                        .getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.FARMINGFORBLOCKHEADS:
                warnings.addAll(MarketBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.TOUHOU_LITTLE_MAID:
                warnings.addAll(TlmAltarBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.IMMORTERS_DELIGHT:
                warnings.addAll(EnchantalCoolerBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_FD_COOKING_POT:
                warnings.addAll(CookingPotBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_FD_SKILLET:
                warnings.addAll(SkilletBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_YHK_MOKA:
                warnings.addAll(MokaPotBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_YHK_STEAMER:
                warnings.addAll(SteamerBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_YHK_FERMENT:
                warnings.addAll(FermentationTankBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case ModIds.ID_AVARITIA_CRAFTING:
            case ModIds.ID_AVARITIA_COMPRESSOR:
            case ModIds.ID_AVARITIA_SMITHING:
                break;
            default:
                break;
        }
        return warnings;
    }
}
