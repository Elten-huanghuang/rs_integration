package com.huanghuang.rsintegration.client.recipe;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerCatalog;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 在客户端主线程分批探测容器能力。 */
@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, value = Dist.CLIENT)
public final class FluidContainerClientCatalog {
    private static final long TICK_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    private static ClientLevel requestedLevel;
    private static ClientLevel failedLevel;
    private static FluidContainerCatalog.IncrementalIndex build;

    private FluidContainerClientCatalog() {}

    @Nullable
    public static List<FluidContainerRecipe> requestRecipes(@Nullable ClientLevel level) {
        var ready = FluidContainerCatalog.publishedRecipes(level);
        if (ready == null && level != null && level != failedLevel && requestedLevel != level) {
            cancelBuild();
            requestedLevel = level;
        }
        return ready;
    }

    @Nullable
    public static FluidContainerRecipe resolve(ClientLevel level, ResourceLocation id) {
        var recipe = FluidContainerCatalog.resolve(level, id);
        if (recipe == null) requestRecipes(level);
        return recipe;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level.getRecipeManager() != event.getRecipeManager()) return;
        FluidContainerCatalog.invalidateClientRecipes(level);
        cancelBuild();
        failedLevel = null;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) tick(Minecraft.getInstance().level, TICK_BUDGET_NANOS);
    }

    static void tick(@Nullable ClientLevel level, long budgetNanos) {
        if (failedLevel != level) failedLevel = null;
        if (requestedLevel == null) return;
        if (requestedLevel != level || FluidContainerCatalog.publishedRecipes(level) != null) {
            cancelBuild();
            return;
        }
        long deadline = System.nanoTime() + budgetNanos;
        try {
            if (build != null && !build.isCurrent()) {
                build.cancel();
                build = null;
            }
            if (build == null) build = FluidContainerCatalog.incrementalClientIndex(level);
            if (build.advance(() -> System.nanoTime() >= deadline) && build.publish()) cancelBuild();
        } catch (RuntimeException | LinkageError failure) {
            cancelBuild();
            failedLevel = level;
            RSIntegrationMod.LOGGER.warn("[RSI] 客户端流体容器目录构建失败", failure);
        }
    }

    private static void cancelBuild() {
        if (build != null) build.cancel();
        build = null;
        requestedLevel = null;
    }
}
