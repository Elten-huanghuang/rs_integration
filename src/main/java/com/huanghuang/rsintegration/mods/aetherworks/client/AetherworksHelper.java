package com.huanghuang.rsintegration.mods.aetherworks.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.items.IItemHandler;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.reflection.probes.AetherworksReflection;
import com.huanghuang.rsintegration.reflection.probes.EmbersReflection;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

@OnlyIn(Dist.CLIENT)
final class AetherworksHelper {

    private static final int FORGE_SEARCH_RADIUS = 5;

    private static final boolean LOADED;

    private static Field f_anvil_progress;
    private static Field f_anvil_hitTimeout;
    private static Field f_anvil_mistakes;
    private static Field f_anvil_inventory;

    private static Field f_ts_inventory;

    private static Field f_forge_heatCap;
    private static Field f_forge_emberCap;

    // IHeatCapability is from Aetherworks: net.sirplop.aetherworks.api.capabilities.IHeatCapability
    private static Method m_heat_getHeat;
    // IEmberCapability is from Embers: com.rekindled.embers.api.power.IEmberCapability
    private static Method m_ember_getEmber;
    private static Method m_ember_getCapacity;

    private static Object recipeType;
    private static Method m_recipe_getDisplayInput;
    private static Method m_recipe_getNumberOfHits;
    private static Method m_recipe_getTemperatureMin;
    private static Method m_recipe_getTemperatureMax;
    private static Method m_recipe_getEmberPerHit;

    private static Object tsRecipeType;
    private static Method m_ts_recipe_getDisplayInputs;
    private static Method m_ts_recipe_getTemperature;
    private static Method m_ts_recipe_getTemperatureRate;

    private static Item hammerItem;

    static {
        boolean ok = false;
        if (AetherworksReflection.ready) {
            try {
                // Essential: anvil fields for HUD + auto-hammer
                f_anvil_progress = AetherworksReflection.anvilBEClass.getDeclaredField("progress");
                f_anvil_hitTimeout = AetherworksReflection.anvilBEClass.getDeclaredField("hitTimeout");
                f_anvil_mistakes = AetherworksReflection.anvilBEClass.getDeclaredField("mistakes");
                f_anvil_inventory = AetherworksReflection.anvilBEClass.getDeclaredField("inventory");
                f_anvil_progress.setAccessible(true);
                f_anvil_hitTimeout.setAccessible(true);
                f_anvil_mistakes.setAccessible(true);
                f_anvil_inventory.setAccessible(true);

                ok = true; // Core HUD and auto-hammer can work

                // Tool station (non-essential for HUD)
                try {
                    f_ts_inventory = AetherworksReflection.toolStationBEClass.getField("inventory");
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] reflection probe failed", e); }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Anvil block entity field probe failed — HUD disabled", e);
            }

            if (ok) {
                // Non-essential: forge capability fields
                try { f_forge_heatCap = AetherworksReflection.forgeBEClass.getField("heatCapability"); } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] reflection probe failed", e); }
                try { f_forge_emberCap = AetherworksReflection.forgeBEClass.getField("emberCapability"); } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] reflection probe failed", e); }

                // Non-essential: heat capability (Aetherworks own API)
                try {
                    m_heat_getHeat = AetherworksReflection.iheatCapabilityClass.getMethod("getHeat");
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] reflection probe failed", e); }

                // Non-essential: ember capability (Embers API)
                try {
                    m_ember_getEmber = EmbersReflection.iemberCapabilityClass.getMethod("getEmber");
                    m_ember_getCapacity = EmbersReflection.iemberCapabilityClass.getMethod("getEmberCapacity");
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] reflection probe failed", e); }

                // Non-essential: recipe access
                try {
                    m_recipe_getDisplayInput = AetherworksReflection.anvilRecipeClass.getMethod("getDisplayInput");
                    m_recipe_getNumberOfHits = AetherworksReflection.anvilRecipeClass.getMethod("getNumberOfHits");
                    m_recipe_getTemperatureMin = AetherworksReflection.anvilRecipeClass.getMethod("getTemperatureMin");
                    m_recipe_getTemperatureMax = AetherworksReflection.anvilRecipeClass.getMethod("getTemperatureMax");
                    m_recipe_getEmberPerHit = AetherworksReflection.anvilRecipeClass.getMethod("getEmberPerHit");

                    Class<?> awReg = AetherworksReflection.awRegistryClass;
                    Field f = awReg.getField("AETHERIUM_ANVIL");
                    Object ro = f.get(null);
                    recipeType = ro.getClass().getMethod("get").invoke(ro);
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] reflection probe failed", e); }

                // Non-essential: tool station recipe access
                try {
                    m_ts_recipe_getDisplayInputs = AetherworksReflection.toolStationRecipeClass.getMethod("getDisplayInputs");
                    m_ts_recipe_getTemperature = AetherworksReflection.toolStationRecipeClass.getMethod("getTemperature");
                    m_ts_recipe_getTemperatureRate = AetherworksReflection.toolStationRecipeClass.getMethod("getTemperatureRate");

                    Class<?> awReg = AetherworksReflection.awRegistryClass;
                    Field f2 = awReg.getField("TOOL_STATION");
                    Object ro2 = f2.get(null);
                    tsRecipeType = ro2.getClass().getMethod("get").invoke(ro2);
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] reflection probe failed", e); }

                // Non-essential: hammer
                try {
                    Class<?> regMan = EmbersReflection.registryManagerClass;
                    Field f = regMan.getField("TINKER_HAMMER");
                    Object ro = f.get(null);
                    hammerItem = (Item) ro.getClass().getMethod("get").invoke(ro);
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] reflection probe failed", e); }
            }
        }
        LOADED = ok;
    }

    private AetherworksHelper() {}

    static boolean isLoaded() { return LOADED; }

    static boolean isAnvil(@Nullable BlockEntity be) {
        return LOADED && AetherworksReflection.anvilBEClass.isInstance(be);
    }

    static boolean isToolStation(@Nullable BlockEntity be) {
        return LOADED && AetherworksReflection.toolStationBEClass.isInstance(be);
    }

    static boolean isForge(@Nullable BlockEntity be) {
        return LOADED && AetherworksReflection.forgeBEClass.isInstance(be);
    }

    static boolean isHoldingHammer(Player player) {
        return hammerItem != null && player.getMainHandItem().is(hammerItem);
    }

    static int getAnvilProgress(BlockEntity anvil) {
        if (f_anvil_progress == null) return 0;
        try { return f_anvil_progress.getInt(anvil); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed", e);
            return 0;
        }
    }

    static int getAnvilHitTimeout(BlockEntity anvil) {
        if (f_anvil_hitTimeout == null) return 0;
        try { return f_anvil_hitTimeout.getInt(anvil); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed", e);
            return 0;
        }
    }

    static int getAnvilMistakes(BlockEntity anvil) {
        if (f_anvil_mistakes == null) return 0;
        try { return f_anvil_mistakes.getInt(anvil); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed", e);
            return 0;
        }
    }

    static ItemStack getAnvilSlot(BlockEntity anvil, int slot) {
        if (f_anvil_inventory == null) return ItemStack.EMPTY;
        try {
            IItemHandler handler = (IItemHandler) f_anvil_inventory.get(anvil);
            return handler.getStackInSlot(slot);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed", e);
            return ItemStack.EMPTY;
        }
    }

    static ItemStack getTsSlot(BlockEntity ts, int slot) {
        if (f_ts_inventory == null) return ItemStack.EMPTY;
        try {
            IItemHandler handler = (IItemHandler) f_ts_inventory.get(ts);
            return handler.getStackInSlot(slot);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed", e);
            return ItemStack.EMPTY;
        }
    }

    static double getForgeHeat(BlockEntity forge) {
        if (f_forge_heatCap == null || m_heat_getHeat == null) return 0;
        try {
            Object cap = f_forge_heatCap.get(forge);
            if (cap == null) return 0;
            return (double) m_heat_getHeat.invoke(cap);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed", e);
            return 0;
        }
    }

    static double getForgeEmber(BlockEntity forge) {
        if (f_forge_emberCap == null || m_ember_getEmber == null) return 0;
        try {
            Object cap = f_forge_emberCap.get(forge);
            if (cap == null) return 0;
            return (double) m_ember_getEmber.invoke(cap);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed", e);
            return 0;
        }
    }

    static double getForgeEmberCapacity(BlockEntity forge) {
        if (f_forge_emberCap == null || m_ember_getCapacity == null) return 0;
        try {
            Object cap = f_forge_emberCap.get(forge);
            if (cap == null) return 0;
            return (double) m_ember_getCapacity.invoke(cap);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed", e);
            return 0;
        }
    }

    @Nullable
    static Object findRecipe(Level level, ItemStack item) {
        if (recipeType == null || item.isEmpty()) return null;
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            List<Object> recipes = (List<Object>)
                    level.getRecipeManager().getAllRecipesFor((RecipeType) recipeType);
            for (Object r : recipes) {
                Ingredient ing = (Ingredient) m_recipe_getDisplayInput.invoke(r);
                if (ing.test(item)) return r;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed", e);
        }
        return null;
    }

    static int getRecipeHits(Object recipe) {
        if (m_recipe_getNumberOfHits == null) return 0;
        try { return (int) m_recipe_getNumberOfHits.invoke(recipe); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed", e);
            return 0;
        }
    }

    static int getRecipeTempMin(Object recipe) {
        if (m_recipe_getTemperatureMin == null) return 0;
        try { return (int) m_recipe_getTemperatureMin.invoke(recipe); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed", e);
            return 0;
        }
    }

    static int getRecipeTempMax(Object recipe) {
        if (m_recipe_getTemperatureMax == null) return 0;
        try { return (int) m_recipe_getTemperatureMax.invoke(recipe); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed", e);
            return 0;
        }
    }

    static int getRecipeEmberPerHit(Object recipe) {
        if (m_recipe_getEmberPerHit == null) return 0;
        try { return (int) m_recipe_getEmberPerHit.invoke(recipe); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed", e);
            return 0;
        }
    }

    @Nullable
    static BlockEntity findForge(Level level, BlockPos anvilPos) {
        BlockPos.MutableBlockPos mp = anvilPos.mutable();
        for (int dx = -FORGE_SEARCH_RADIUS; dx <= FORGE_SEARCH_RADIUS; dx++)
            for (int dy = -FORGE_SEARCH_RADIUS; dy <= FORGE_SEARCH_RADIUS; dy++)
                for (int dz = -FORGE_SEARCH_RADIUS; dz <= FORGE_SEARCH_RADIUS; dz++) {
                    mp.set(anvilPos.getX() + dx, anvilPos.getY() + dy, anvilPos.getZ() + dz);
                    BlockEntity be = level.getBlockEntity(mp);
                    if (isForge(be)) return be;
                }
        return null;
    }

    @Nullable
    static Object findTsRecipe(Level level, ItemStack item) {
        if (tsRecipeType == null || item.isEmpty()) return null;
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            List<Object> recipes = (List<Object>)
                    level.getRecipeManager().getAllRecipesFor((RecipeType) tsRecipeType);
            for (Object r : recipes) {
                @SuppressWarnings("unchecked")
                List<Ingredient> inputs = (List<Ingredient>) m_ts_recipe_getDisplayInputs.invoke(r);
                if (inputs != null) {
                    for (Ingredient ing : inputs) {
                        if (ing.test(item)) return r;
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed", e);
        }
        return null;
    }

    static int getTsRecipeTemp(Object recipe) {
        if (m_ts_recipe_getTemperature == null) return 0;
        try { return (int) m_ts_recipe_getTemperature.invoke(recipe); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed", e);
            return 0;
        }
    }

    static double getTsRecipeTempRate(Object recipe) {
        if (m_ts_recipe_getTemperatureRate == null) return 0;
        try { return (double) m_ts_recipe_getTemperatureRate.invoke(recipe); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed", e);
            return 0;
        }
    }

    @Nullable
    static BlockEntity getTargetAnvil() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (mc.hitResult instanceof BlockHitResult hit
                && hit.getType() == BlockHitResult.Type.BLOCK) {
            BlockEntity be = mc.level.getBlockEntity(hit.getBlockPos());
            if (isAnvil(be) || isToolStation(be)) return be;
        }
        return null;
    }
}
