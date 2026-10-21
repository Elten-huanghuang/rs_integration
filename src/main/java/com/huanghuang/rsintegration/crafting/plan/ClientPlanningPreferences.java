package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;

/** 客户端发起初始计划请求前应用持久偏好。 */
public final class ClientPlanningPreferences {
    private static boolean migrationAttempted;

    private ClientPlanningPreferences() {}

    public static GenericCraftPacket apply(GenericCraftPacket packet, @Nullable Level level) {
        CraftingPlanPreferences.Snapshot snapshot =
                CraftingPlanPreferences.load(preferencesPath());
        ResourceLocation preferred = preferredRootRecipe(snapshot.recipeChoices(),
                snapshot.excludedRecipes(), packet.recipeId(), level);
        if (preferred != null) packet.withRecipeId(preferred);
        return packet.withPlanningPreferences(snapshot.toPlanningPreferences());
    }

    static synchronized Path preferencesPath() {
        Path current = currentPath();
        Path legacy = legacyPath();
        if (!migrationAttempted) {
            migrationAttempted = Files.isRegularFile(current)
                    || !Files.isRegularFile(legacy)
                    || migrateLegacy(legacy, current);
        }
        return current;
    }

    static boolean migrateLegacy(Path legacy, Path current) {
        if (legacy == null || current == null
                || !Files.isRegularFile(legacy) || Files.exists(current)) {
            return false;
        }
        try {
            Path parent = current.getParent();
            if (parent != null) Files.createDirectories(parent);
            try {
                Files.move(legacy, current, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(legacy, current);
            }
            return Files.isRegularFile(current);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Path currentPath() {
        return FMLPaths.GAMEDIR.get()
                .resolve("rs_integration").resolve("crafting_plan.json");
    }

    private static Path legacyPath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve("rs_integration").resolve("crafting_plan.json");
    }

    @Nullable
    private static ResourceLocation preferredRootRecipe(
            Map<String, RecipeChoice> choices,
            Set<String> excludedRecipes,
            ResourceLocation clickedRecipeId, @Nullable Level level) {
        if (level == null) return null;
        Recipe<?> clicked = level.getRecipeManager().byKey(clickedRecipeId).orElse(null);
        if (clicked == null) return null;
        ItemStack clickedOutput = ModRecipeHandlers.tryGetResultItem(
                clicked, level.registryAccess());
        if (clickedOutput.isEmpty()) return null;
        ResourceLocation targetKey = CraftingResolver.preferenceKey(clickedOutput);
        if (targetKey == null) return null;
        RecipeChoice choice = choices.get(targetKey.toString());
        if (!(choice instanceof RecipeChoice.Normal normal)
                || normal.recipeId().equals(clickedRecipeId)) return null;
        if (excludedRecipes.contains(normal.recipeId().toString())) return null;
        Recipe<?> preferred = level.getRecipeManager().byKey(normal.recipeId()).orElse(null);
        if (preferred == null) return null;
        ItemStack preferredOutput = ModRecipeHandlers.tryGetResultItem(
                preferred, level.registryAccess());
        return targetKey.equals(CraftingResolver.preferenceKey(preferredOutput))
                ? normal.recipeId() : null;
    }
}
