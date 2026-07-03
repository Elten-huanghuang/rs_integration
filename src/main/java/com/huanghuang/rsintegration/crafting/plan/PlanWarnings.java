package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.mods.aether.AetherFurnaceBatchDelegate;
import com.huanghuang.rsintegration.mods.aetherworks.AetherworksBatchDelegate;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotBatchDelegate;
import com.huanghuang.rsintegration.mods.eidolon.EidolonBatchDelegate;
import com.huanghuang.rsintegration.mods.immortalers_delight.EnchantalCoolerBatchDelegate;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyBatchDelegate;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketBatchDelegate;
import com.huanghuang.rsintegration.mods.forbidden.FaBatchDelegate;
import com.huanghuang.rsintegration.mods.goety.GoetyBatchDelegate;
import com.huanghuang.rsintegration.mods.malum.MalumBatchDelegate;
import com.huanghuang.rsintegration.mods.touhoulittlemaid.TlmAltarBatchDelegate;
import com.huanghuang.rsintegration.mods.wizards_reborn.WRBatchDelegate;
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
            case "aether":
                warnings.addAll(AetherFurnaceBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case "goety":
                warnings.addAll(GoetyBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case "forbidden_arcanus":
                warnings.addAll(FaBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case "wizards_reborn":
                warnings.addAll(WRBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case "malum":
                warnings.addAll(MalumBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case "eidolon":
                warnings.addAll(EidolonBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case "embers_alchemy":
                warnings.addAll(EreAlchemyBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case "aetherworks_anvil":
                warnings.addAll(AetherworksBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case "crockpot":
                warnings.addAll(CrockPotBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case "farmingforblockheads":
                warnings.addAll(MarketBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case "touhou_little_maid":
                warnings.addAll(TlmAltarBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case "immortalers_delight":
                warnings.addAll(EnchantalCoolerBatchDelegate.getPlanWarnings(player, recipe, dim, pos));
                break;
            case "avaritia_crafting":
            case "avaritia_compressor":
            case "avaritia_smithing":
                break;
            default:
                break;
        }
        return warnings;
    }
}
