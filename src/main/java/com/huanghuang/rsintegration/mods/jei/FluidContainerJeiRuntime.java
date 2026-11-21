package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.client.recipe.FluidContainerClientCatalog;
import com.huanghuang.rsintegration.client.recipe.FluidContainerRecipeCategory;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerRecipe;
import mezz.jei.api.recipe.IRecipeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FluidContainerJeiRuntime {
    private static final int RECIPES_PER_TICK = 32;
    private static long completedRevision;

    private final IRecipeManager manager;
    private final Map<ResourceLocation, FluidContainerRecipe> registered = new HashMap<>();
    private final List<FluidContainerRecipe> visible = new ArrayList<>();
    private List<FluidContainerRecipe> catalog;
    private int cursor;

    FluidContainerJeiRuntime(IRecipeManager manager) {
        this.manager = manager;
    }

    public static long completedRevision() {
        return completedRevision;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        update(FluidContainerClientCatalog.requestRecipes(Minecraft.getInstance().level));
    }

    void update(@Nullable List<FluidContainerRecipe> ready) {
        var type = FluidContainerRecipeCategory.TYPE;
        if (catalog != ready) {
            if (!visible.isEmpty()) {
                manager.hideRecipes(type, List.copyOf(visible));
                visible.clear();
            }
            catalog = ready;
            cursor = 0;
        }
        if (ready == null || cursor == ready.size()) return;

        int end = Math.min(cursor + RECIPES_PER_TICK, ready.size());
        List<FluidContainerRecipe> additions = new ArrayList<>();
        List<FluidContainerRecipe> restored = new ArrayList<>();
        for (int i = cursor; i < end; i++) {
            FluidContainerRecipe recipe = ready.get(i);
            FluidContainerRecipe existing = registered.get(recipe.getId());
            if (existing == null) additions.add(recipe);
            else restored.add(existing);
        }
        if (!additions.isEmpty()) {
            manager.addRecipes(type, additions);
            additions.forEach(recipe -> registered.put(recipe.getId(), recipe));
            visible.addAll(additions);
        }
        if (!restored.isEmpty()) {
            // 同一个 ID 表示相同转换。恢复原对象，避免向 JEI 的索引重复添加。
            manager.unhideRecipes(type, restored);
            visible.addAll(restored);
        }
        cursor = end;
        // 只在整批注册结束后刷新打开的配方页，避免每 tick 重建布局。
        if (cursor == ready.size()) completedRevision++;
    }
}
