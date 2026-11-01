package com.huanghuang.rsintegration.feature.goety.integration;

import com.Polarice3.Goety.common.blocks.BrewCauldronBlock;
import com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity;
import com.Polarice3.Goety.utils.BrewUtils;
import com.huanghuang.rsintegration.feature.goety.brewing.BrewPlan;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 服务端当前状态快照；Jade 不依赖菜单是否仍然打开。 */
public final class CauldronDisplay {
    private CauldronDisplay() {}

    public static CompoundTag snapshot(BlockEntity entity) {
        var data = new CompoundTag();
        if (!(entity instanceof BrewCauldronBlockEntity cauldron) || cauldron.getLevel() == null) return data;
        String mode = cauldron.mode.name();
        data.putString("mode", mode);
        data.putBoolean("running", GoetyCauldronCompat.isRunning(cauldron));
        data.putInt("progress", Math.max(0, cauldron.soulTime));
        data.putInt("total", Math.max(0, cauldron.getBrewCost()));
        data.putInt("water", Math.min(3, cauldron.getBlockState().getValue(BrewCauldronBlock.LEVEL)));
        data.putBoolean("heated", cauldron.isHeated());
        if (!mode.equals("BREWING") && !mode.equals("COMPLETED")
                && !mode.equals("CRAFTING") && !mode.equals("CRAFTED")) return data;
        var output = BrewPlan.previewCurrentOutput(cauldron);
        if (output.isEmpty()) return data;
        data.put("output", output.save(new CompoundTag()));
        var effects = new ListTag();
        for (var effect : PotionUtils.getMobEffects(output)) {
            effects.add(StringTag.valueOf(Component.Serializer.toJson(effectName(
                    effect.getDescriptionId(), effect.getAmplifier()))));
        }
        for (var effect : BrewUtils.getBrewEffects(output)) {
            effects.add(StringTag.valueOf(Component.Serializer.toJson(effectName(
                    effect.getDescriptionId(), effect.getAmplifier()))));
        }
        data.put("effects", effects);
        return data;
    }

    private static Component effectName(String key, int amplifier) {
        Component name = Component.translatable(key);
        return amplifier <= 0 ? name : Component.translatable(
                "potion.withAmplifier", name, Component.translatable("potion.potency." + amplifier));
    }
}
