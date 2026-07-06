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
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;

import com.huanghuang.rsintegration.RSIntegrationMod;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

@OnlyIn(Dist.CLIENT)
final class AetherworksHelper {

    private static final int FORGE_SEARCH_RADIUS = 5;

    private static final boolean LOADED;

    @Nullable private static Class<?> anvilClass;
    @Nullable private static Class<?> forgeClass;
    @Nullable private static Class<?> toolStationClass;

    @Nullable private static Field f_anvil_progress;
    @Nullable private static Field f_anvil_hitTimeout;
    @Nullable private static Field f_anvil_mistakes;
    @Nullable private static Field f_anvil_inventory;

    @Nullable private static Field f_ts_inventory;

    @Nullable private static Field f_forge_heatCap;
    @Nullable private static Field f_forge_emberCap;

    // IHeatCapability is from Aetherworks: net.sirplop.aetherworks.api.capabilities.IHeatCapability
    @Nullable private static Method m_heat_getHeat;
    // IEmberCapability is from Embers: com.rekindled.embers.api.power.IEmberCapability
    @Nullable private static Method m_ember_getEmber;
    @Nullable private static Method m_ember_getCapacity;

    @Nullable private static Object recipeType;
    @Nullable private static Method m_recipe_getDisplayInput;
    @Nullable private static Method m_recipe_getNumberOfHits;
    @Nullable private static Method m_recipe_getTemperatureMin;
    @Nullable private static Method m_recipe_getTemperatureMax;
    @Nullable private static Method m_recipe_getEmberPerHit;

    @Nullable private static Object tsRecipeType;
    @Nullable private static Method m_ts_recipe_getDisplayInputs;
    @Nullable private static Method m_ts_recipe_getTemperature;

    @Nullable private static Item hammerItem;

    static {
        boolean ok = false;
        if (ModList.get().isLoaded("aetherworks")) {
            try {
                // Essential: anvil and forge class objects
                anvilClass = Class.forName("net.sirplop.aetherworks.blockentity.AetheriumAnvilBlockEntity");
                forgeClass = Class.forName("net.sirplop.aetherworks.blockentity.AetherForgeBlockEntity");

                // Essential: anvil fields for HUD + auto-hammer
                f_anvil_progress = anvilClass.getField("progress");
                f_anvil_hitTimeout = anvilClass.getField("hitTimeout");
                f_anvil_mistakes = anvilClass.getField("mistakes");
                f_anvil_inventory = anvilClass.getField("inventory");

                ok = true; // Core HUD and auto-hammer can work

                // Tool station (non-essential for HUD)
                try {
                    toolStationClass = Class.forName(
                            "net.sirplop.aetherworks.blockentity.ToolStationBlockEntity");
                    f_ts_inventory = toolStationClass.getField("inventory");
                } catch (Exception ignored) {}
            } catch (Exception e) {
                // Aetherworks not properly installed — core classes missing
            }

            if (ok) {
                // Non-essential: forge capability fields
                try { f_forge_heatCap = forgeClass.getField("heatCapability"); } catch (Exception ignored) {}
                try { f_forge_emberCap = forgeClass.getField("emberCapability"); } catch (Exception ignored) {}

                // Non-essential: heat capability (Aetherworks own API)
                try {
                    Class<?> hc = Class.forName("net.sirplop.aetherworks.api.capabilities.IHeatCapability");
                    m_heat_getHeat = hc.getMethod("getHeat");
                } catch (Exception ignored) {}

                // Non-essential: ember capability (Embers API)
                try {
                    Class<?> ec = Class.forName("com.rekindled.embers.api.power.IEmberCapability");
                    m_ember_getEmber = ec.getMethod("getEmber");
                    m_ember_getCapacity = ec.getMethod("getEmberCapacity");
                } catch (Exception ignored) {}

                // Non-essential: recipe access
                try {
                    Class<?> recipeClass = Class.forName("net.sirplop.aetherworks.recipe.IAetheriumAnvilRecipe");
                    m_recipe_getDisplayInput = recipeClass.getMethod("getDisplayInput");
                    m_recipe_getNumberOfHits = recipeClass.getMethod("getNumberOfHits");
                    m_recipe_getTemperatureMin = recipeClass.getMethod("getTemperatureMin");
                    m_recipe_getTemperatureMax = recipeClass.getMethod("getTemperatureMax");
                    m_recipe_getEmberPerHit = recipeClass.getMethod("getEmberPerHit");

                    Class<?> awReg = Class.forName("net.sirplop.aetherworks.AWRegistry");
                    Field f = awReg.getField("AETHERIUM_ANVIL");
                    Object ro = f.get(null);
                    recipeType = ro.getClass().getMethod("get").invoke(ro);
                } catch (Exception ignored) {}

                // Non-essential: tool station recipe access
                try {
                    Class<?> tsRClass = Class.forName(
                            "net.sirplop.aetherworks.recipe.IToolStationRecipe");
                    m_ts_recipe_getDisplayInputs = tsRClass.getMethod("getDisplayInputs");
                    m_ts_recipe_getTemperature = tsRClass.getMethod("getTemperature");

                    Class<?> awReg = Class.forName("net.sirplop.aetherworks.AWRegistry");
                    Field f2 = awReg.getField("TOOL_STATION");
                    Object ro2 = f2.get(null);
                    tsRecipeType = ro2.getClass().getMethod("get").invoke(ro2);
                } catch (Exception ignored) {}

                // Non-essential: hammer
                try {
                    Class<?> regMan = Class.forName("com.rekindled.embers.RegistryManager");
                    Field f = regMan.getField("TINKER_HAMMER");
                    Object ro = f.get(null);
                    hammerItem = (Item) ro.getClass().getMethod("get").invoke(ro);
                } catch (Exception ignored) {}
            }
        }
        LOADED = ok;
    }

    private AetherworksHelper() {}

    static boolean isLoaded() { return LOADED; }

    static boolean isAnvil(@Nullable BlockEntity be) {
        return LOADED && anvilClass != null && anvilClass.isInstance(be);
    }

    static boolean isToolStation(@Nullable BlockEntity be) {
        return LOADED && toolStationClass != null && toolStationClass.isInstance(be);
    }

    static boolean isForge(@Nullable BlockEntity be) {
        return LOADED && forgeClass != null && forgeClass.isInstance(be);
    }

    static boolean isHoldingHammer(Player player) {
        return hammerItem != null && player.getMainHandItem().is(hammerItem);
    }

    static int getAnvilProgress(BlockEntity anvil) {
        if (f_anvil_progress == null) return 0;
        try { return f_anvil_progress.getInt(anvil); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed: {}", e.toString());
            return 0;
        }
    }

    static int getAnvilHitTimeout(BlockEntity anvil) {
        if (f_anvil_hitTimeout == null) return 0;
        try { return f_anvil_hitTimeout.getInt(anvil); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed: {}", e.toString());
            return 0;
        }
    }

    static int getAnvilMistakes(BlockEntity anvil) {
        if (f_anvil_mistakes == null) return 0;
        try { return f_anvil_mistakes.getInt(anvil); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed: {}", e.toString());
            return 0;
        }
    }

    static ItemStack getAnvilSlot(BlockEntity anvil, int slot) {
        if (f_anvil_inventory == null) return ItemStack.EMPTY;
        try {
            IItemHandler handler = (IItemHandler) f_anvil_inventory.get(anvil);
            return handler.getStackInSlot(slot);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed: {}", e.toString());
            return ItemStack.EMPTY;
        }
    }

    static ItemStack getTsSlot(BlockEntity ts, int slot) {
        if (f_ts_inventory == null) return ItemStack.EMPTY;
        try {
            IItemHandler handler = (IItemHandler) f_ts_inventory.get(ts);
            return handler.getStackInSlot(slot);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed: {}", e.toString());
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
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed: {}", e.toString());
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
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed: {}", e.toString());
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
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed: {}", e.toString());
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
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed: {}", e.toString());
        }
        return null;
    }

    static int getRecipeHits(Object recipe) {
        if (m_recipe_getNumberOfHits == null) return 0;
        try { return (int) m_recipe_getNumberOfHits.invoke(recipe); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed: {}", e.toString());
            return 0;
        }
    }

    static int getRecipeTempMin(Object recipe) {
        if (m_recipe_getTemperatureMin == null) return 0;
        try { return (int) m_recipe_getTemperatureMin.invoke(recipe); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed: {}", e.toString());
            return 0;
        }
    }

    static int getRecipeTempMax(Object recipe) {
        if (m_recipe_getTemperatureMax == null) return 0;
        try { return (int) m_recipe_getTemperatureMax.invoke(recipe); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed: {}", e.toString());
            return 0;
        }
    }

    static int getRecipeEmberPerHit(Object recipe) {
        if (m_recipe_getEmberPerHit == null) return 0;
        try { return (int) m_recipe_getEmberPerHit.invoke(recipe); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed: {}", e.toString());
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
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed: {}", e.toString());
        }
        return null;
    }

    static int getTsRecipeTemp(Object recipe) {
        if (m_ts_recipe_getTemperature == null) return 0;
        try { return (int) m_ts_recipe_getTemperature.invoke(recipe); } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Aetherworks] Reflection read failed: {}", e.toString());
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
