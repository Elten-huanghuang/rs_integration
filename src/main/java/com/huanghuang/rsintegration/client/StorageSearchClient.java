package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import com.huanghuang.rsintegration.sidepanel.client.RSIKeyBindings;
import com.huanghuang.rsintegration.util.ModIds;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IBookmarkOverlay;
import mezz.jei.api.runtime.IIngredientListOverlay;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/** Fills the search field of either supported storage-terminal GUI. */
public final class StorageSearchClient {
    private static final String RS_GRID_SCREEN =
            "com.refinedmods.refinedstorage.screen.grid.GridScreen";
    private static final String BD_GUI_PACKAGE =
            "com.wintercogs.beyonddimensions.client.gui.";

    private static boolean registered;

    private StorageSearchClient() {}

    public static void register() {
        if (registered) return;
        registered = true;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                EventPriority.HIGH, StorageSearchClient::onKeyPressed);
    }

    private static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!matchesFillBinding(event)) return;

        Screen screen = event.getScreen();
        if (!isStorageScreen(screen)) return;

        SearchCandidate candidate = findHoveredItem();
        if (candidate == null || candidate.text().isBlank()) return;

        if (setSearchText(screen, candidate.text())) {
            event.setCanceled(true);
        }
    }

    private static boolean matchesFillBinding(ScreenEvent.KeyPressed.Pre event) {
        var binding = RSIKeyBindings.KEY_FILL_STORAGE_SEARCH;
        return binding != null && !binding.isUnbound()
                && binding.isActiveAndMatches(
                        InputConstants.getKey(event.getKeyCode(), event.getScanCode()));
    }

    private static boolean isStorageScreen(@Nullable Screen screen) {
        if (screen == null) return false;
        String name = screen.getClass().getName();
        if (RS_GRID_SCREEN.equals(name)) return true;
        if (!ModList.get().isLoaded("beyonddimensions")) return false;
        // DimensionsCraftGUI and DimensionsTerminalCraftGUI inherit the
        // search field and responder from DimensionsNetGUI.
        return name.startsWith(BD_GUI_PACKAGE)
                && (name.endsWith("DimensionsNetGUI")
                || name.endsWith("DimensionsCraftGUI")
                || name.endsWith("DimensionsTerminalCraftGUI"));
    }

    @Nullable
    private static SearchCandidate findHoveredItem() {
        Minecraft minecraft = Minecraft.getInstance();

        // EMI exposes the exact hovered stack by screen coordinates. It is
        // tried first because EMI may be installed alongside JEI and own the
        // visible overlay in that configuration.
        if (ModList.get().isLoaded("emi")) {
            try {
                Optional<ItemStack> hovered = RecipeBrowserBridgeCoordinates.emiItem(minecraft);
                if (hovered.isPresent()) {
                    ItemStack stack = hovered.get();
                    return new SearchCandidate(stack.getHoverName().getString());
                }
            } catch (LinkageError | RuntimeException failure) {
                RSIntegrationMod.LOGGER.debug(
                        "[RSI-StorageSearch] EMI ingredient lookup failed", failure);
            }
        }

        if (!ModList.get().isLoaded(ModIds.JEI)) return null;
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) return null;

        try {
            ITypedIngredient<?> typed = hoveredJeiIngredient(runtime);
            if (typed == null) return null;
            Optional<ItemStack> stack = typed.getItemStack();
            if (stack.isEmpty() || stack.get().isEmpty()) return null;
            ItemStack item = stack.get();
            return new SearchCandidate(jeiDisplayName(runtime, typed, item));
        } catch (LinkageError | RuntimeException failure) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-StorageSearch] JEI ingredient lookup failed", failure);
            return null;
        }
    }

    @Nullable
    private static ITypedIngredient<?> hoveredJeiIngredient(IJeiRuntime runtime) {
        IIngredientListOverlay list = runtime.getIngredientListOverlay();
        if (list != null && list.isListDisplayed()) {
            Optional<ITypedIngredient<?>> ingredient = list.getIngredientUnderMouse();
            if (ingredient.isPresent()) return ingredient.get();
        }

        IBookmarkOverlay bookmarks = runtime.getBookmarkOverlay();
        if (bookmarks != null) {
            Optional<ITypedIngredient<?>> ingredient = bookmarks.getIngredientUnderMouse();
            if (ingredient.isPresent()) return ingredient.get();
        }

        // The public JEI runtime also exposes the ingredient under the mouse
        // in an open recipe view, which is not part of either overlay above.
        if (runtime.getRecipesGui() == null) return null;
        Optional<ItemStack> recipeItem = runtime.getRecipesGui()
                .getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
        return recipeItem
                .filter(item -> !item.isEmpty())
                .flatMap(item -> runtime.getIngredientManager()
                        .createTypedIngredient(VanillaTypes.ITEM_STACK, item))
                .orElse(null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String jeiDisplayName(IJeiRuntime runtime,
                                         ITypedIngredient<?> typed,
                                         ItemStack fallback) {
        try {
            Object ingredient = typed.getIngredient();
            IIngredientHelper helper = runtime.getIngredientManager().getIngredientHelper(ingredient);
            String displayName = helper.getDisplayName(ingredient);
            if (displayName != null && !displayName.isBlank()) return displayName;
        } catch (LinkageError | RuntimeException failure) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-StorageSearch] JEI display-name lookup failed", failure);
        }
        return fallback.getHoverName().getString();
    }

    /**
     * Applies a value through the terminal's EditBox responder. Both RS and
     * BD refresh their filtered view from that responder, so no backend API or
     * synthetic key event is needed here.
     */
    public static boolean setSearchText(@Nullable Screen screen, String text) {
        if (screen == null || text == null || !isStorageScreen(screen)) {
            return false;
        }

        try {
            EditBox field = findSearchField(screen);
            if (field == null) return false;
            field.setValue(text);
            field.moveCursorToEnd();
            return true;
        } catch (LinkageError | ReflectiveOperationException | RuntimeException failure) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-StorageSearch] Failed to write search field for {}",
                    screen.getClass().getName(), failure);
            return false;
        }
    }

    /**
     * Applies a server-originated value only while the menu that requested it
     * is still open. Container ids are scoped to the player connection and
     * change when the player switches terminals.
     */
    public static void applyServerSearchText(int containerId, String text) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = minecraft.screen;
        if (!(screen instanceof AbstractContainerScreen<?> container)
                || container.getMenu().containerId != containerId) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-StorageSearch] Ignoring stale server search update: expected container {}, current {}",
                    containerId,
                    screen instanceof AbstractContainerScreen<?> current
                            ? current.getMenu().containerId : -1);
            return;
        }
        setSearchText(screen, text);
    }

    @Nullable
    private static EditBox findSearchField(Screen screen)
            throws ReflectiveOperationException {
        if (RS_GRID_SCREEN.equals(screen.getClass().getName())) {
            Method getter = findMethod(screen.getClass(), "getSearchField");
            if (getter != null) {
                getter.setAccessible(true);
                Object value = getter.invoke(screen);
                if (value instanceof EditBox field) return field;
            }
        }

        Field named = findField(screen.getClass(), "searchField");
        if (named != null) {
            named.setAccessible(true);
            Object value = named.get(screen);
            if (value instanceof EditBox field) return field;
        }

        // Keep BD compatible with minor releases that rename the backing
        // field while retaining the same EditBox type.
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!EditBox.class.isAssignableFrom(field.getType())
                        || !field.getName().toLowerCase().contains("search")) continue;
                field.setAccessible(true);
                Object value = field.get(screen);
                if (value instanceof EditBox editBox) return editBox;
            }
        }
        return null;
    }

    @Nullable
    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Continue through the GUI superclass hierarchy.
            }
        }
        return null;
    }

    @Nullable
    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                // Continue through the GUI superclass hierarchy.
            }
        }
        return null;
    }

    private record SearchCandidate(String text) {}

    /** Keeps optional EMI references isolated behind a client-only call site. */
    private static final class RecipeBrowserBridgeCoordinates {
        private RecipeBrowserBridgeCoordinates() {}

        private static Optional<ItemStack> emiItem(Minecraft minecraft) {
            var window = minecraft.getWindow();
            int screenWidth = window.getScreenWidth();
            int screenHeight = window.getScreenHeight();
            if (screenWidth <= 0 || screenHeight <= 0) return Optional.empty();
            int mouseX = (int) (minecraft.mouseHandler.xpos()
                    * window.getGuiScaledWidth() / screenWidth);
            int mouseY = (int) (minecraft.mouseHandler.ypos()
                    * window.getGuiScaledHeight() / screenHeight);
            return RecipeBrowserBridge.hoveredEmiItem(mouseX, mouseY);
        }
    }
}
