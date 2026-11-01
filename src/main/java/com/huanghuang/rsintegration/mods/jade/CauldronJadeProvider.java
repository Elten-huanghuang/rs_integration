package com.huanghuang.rsintegration.mods.jade;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.feature.goety.integration.CauldronDisplay;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.BoxStyle;

public enum CauldronJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    static final String DATA_KEY = "rs_integration:witch_cauldron";
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath("rs_integration", "witch_cauldron");

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        data.remove(DATA_KEY);
        if (!RSIntegrationConfig.isGoetyCauldronEnabled()) return;
        var snapshot = CauldronDisplay.snapshot(accessor.getBlockEntity());
        if (!snapshot.isEmpty()) data.put(DATA_KEY, snapshot);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (!RSIntegrationConfig.isGoetyCauldronEnabled()
                || !accessor.getServerData().contains(DATA_KEY, Tag.TAG_COMPOUND)) return;
        var data = accessor.getServerData().getCompound(DATA_KEY);
        String mode = data.getString("mode");
        boolean inProgress = mode.equals("BREWING") || mode.equals("CRAFTING");
        boolean hasRecipe = inProgress || mode.equals("COMPLETED") || mode.equals("CRAFTED");
        var output = hasRecipe ? ItemStack.of(data.getCompound("output")) : ItemStack.EMPTY;
        if (!output.isEmpty()) {
            var helper = tooltip.getElementHelper();
            tooltip.add(helper.smallItem(output));
            tooltip.append(Component.translatable("jade.rs_integration.cauldron.output", output.getHoverName()));
            for (var effect : data.getList("effects", Tag.TAG_STRING)) {
                var name = Component.Serializer.fromJson(effect.getAsString());
                if (name != null) tooltip.add(name);
            }
        }
        tooltip.add(Component.translatable("gui.rs_integration.mode." + mode));
        int total = Math.max(0, data.getInt("total"));
        int progress = Math.max(0, Math.min(total, data.getInt("progress")));
        if (inProgress && total > 0 && !output.isEmpty()) {
            var helper = tooltip.getElementHelper();
            tooltip.add(helper.progress((float) progress / total,
                    Component.translatable("jade.rs_integration.cauldron.souls", progress, total),
                    helper.progressStyle(), BoxStyle.DEFAULT, true));
            tooltip.add(Component.translatable(data.getBoolean("running")
                    ? "jade.rs_integration.cauldron.running" : "jade.rs_integration.cauldron.paused"));
        }
        tooltip.add(Component.translatable("jade.rs_integration.cauldron.water",
                Math.max(0, Math.min(3, data.getInt("water")))));
        tooltip.add(Component.translatable(data.getBoolean("heated")
                ? "gui.rs_integration.hot" : "gui.rs_integration.cold"));
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}
