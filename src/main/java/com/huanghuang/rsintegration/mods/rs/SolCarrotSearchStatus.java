package com.huanghuang.rsintegration.mods.rs;

import com.huanghuang.rsintegration.autoeat.client.PinyinUtil;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Keeps SolCarrot's player-owned food state out of stable tooltip caches. */
public final class SolCarrotSearchStatus {
    private static final Logger LOGGER = LogUtils.getLogger();
    public enum Query { NONE, EATEN, NOT_EATEN }

    private static final String NOT_EATEN_KEY = "tooltip.solcarrot.hearty.not_eaten";
    private static final Set<String> EATEN_KEYS = Set.of(
            "tooltip.solcarrot.hearty.eaten",
            "tooltip.solcarrot.cheap.eaten",
            "tooltip.solcarrot.disabled.eaten");
    private static final Set<String> DYNAMIC_KEYS = Set.of(
            NOT_EATEN_KEY,
            "tooltip.solcarrot.hearty.eaten",
            "tooltip.solcarrot.cheap.eaten",
            "tooltip.solcarrot.disabled.eaten");

    private static MethodHandle foodListGet;
    private static MethodHandle foodListHasEaten;
    private static MethodHandle foodListCount;
    private static MethodHandle tooltipEnabled;
    private static MethodHandle isAllowed;
    private static MethodHandle isHearty;
    private static boolean available;
    private static boolean initializationComplete;

    private static Object currentFoodList;
    private static UUID currentPlayer;
    private static int currentFoodCount = -1;
    private static boolean currentTooltipEnabled;
    private static long revision;
    private static final Map<Item, Boolean> EATEN_BY_ITEM = new HashMap<>();
    private static final Map<Item, Boolean> UNEATEN_ELIGIBLE = new HashMap<>();

    static {
        initialize();
    }

    private SolCarrotSearchStatus() {}

    public static boolean refresh(@Nullable Player player) {
        if (!initializationComplete) initialize();
        if (!available || player == null) return clearState();
        try {
            Object foodList = foodListGet.invoke(player);
            int foodCount = (int) foodListCount.invoke(foodList);
            boolean enabled = (boolean) tooltipEnabled.invoke();
            UUID playerId = player.getUUID();
            boolean changed = foodList != currentFoodList
                    || !playerId.equals(currentPlayer)
                    || foodCount != currentFoodCount
                    || enabled != currentTooltipEnabled;
            currentFoodList = foodList;
            currentPlayer = playerId;
            currentFoodCount = foodCount;
            currentTooltipEnabled = enabled;
            if (changed) {
                EATEN_BY_ITEM.clear();
                UNEATEN_ELIGIBLE.clear();
                revision++;
            }
            return changed;
        } catch (Throwable exception) {
            return clearState();
        }
    }

    public static void clear() {
        clearState();
    }

    public static long revision() {
        return revision;
    }

    public static boolean isAvailable() {
        if (!initializationComplete) initialize();
        return available;
    }

    public static Query classify(String term) {
        if (!initializationComplete) initialize();
        if (!available) {
            return Query.NONE;
        }
        StringBuilder positive = new StringBuilder();
        for (String key : EATEN_KEYS) {
            positive.append(searchText(key));
        }
        return classifyText(term, positive.toString(), searchText(NOT_EATEN_KEY));
    }

    static Query classifyText(String term, String positiveText, String negativeText) {
        if (term == null || term.length() < 2) return Query.NONE;
        String normalized = term.toLowerCase(Locale.ROOT);
        boolean positive = positiveText.contains(normalized);
        boolean negative = negativeText.contains(normalized);

        // "eaten" is also a substring of "not yet eaten". Positive wins that
        // ambiguity; explicit negative phrases still only match NOT_EATEN.
        if (positive) return Query.EATEN;
        return negative ? Query.NOT_EATEN : Query.NONE;
    }

    public static boolean matches(Query query, Object ingredient) {
        if (query == Query.NONE || !available || !currentTooltipEnabled
                || currentFoodList == null
                || !(ingredient instanceof ItemStack stack) || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        if (!item.isEdible()) return false;
        try {
            boolean eaten = EATEN_BY_ITEM.computeIfAbsent(item, ignored -> {
                try {
                    return (boolean) foodListHasEaten.invoke(currentFoodList, item);
                } catch (Throwable exception) {
                    return false;
                }
            });
            if (query == Query.EATEN) return eaten;
            if (eaten) return false;
            return UNEATEN_ELIGIBLE.computeIfAbsent(item, ignored -> {
                try {
                    return (boolean) isAllowed.invoke(item)
                            && (boolean) isHearty.invoke(item);
                } catch (Throwable exception) {
                    return false;
                }
            });
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isDynamicTooltip(Component component) {
        if (component.getContents() instanceof TranslatableContents translatable
                && DYNAMIC_KEYS.contains(translatable.getKey())) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (isDynamicTooltip(sibling)) return true;
        }
        return false;
    }

    private static String searchText(String translationKey) {
        String text = Component.translatable(translationKey).getString()
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(text).append('\n');
        try {
            String pinyin = PinyinUtil.toPinyin(text);
            if (pinyin != null && !pinyin.isEmpty()) {
                result.append(pinyin.toLowerCase(Locale.ROOT)).append('\n');
            }
            String initials = PinyinUtil.toPinyinInitials(text);
            if (initials != null && !initials.isEmpty()) {
                result.append(initials.toLowerCase(Locale.ROOT)).append('\n');
            }
        } catch (Throwable ignored) {
            // Native localized text remains searchable without pinyin support.
        }
        return result.toString();
    }

    private static boolean clearState() {
        boolean changed = currentFoodList != null || currentPlayer != null
                || currentFoodCount != -1 || currentTooltipEnabled;
        currentFoodList = null;
        currentPlayer = null;
        currentFoodCount = -1;
        currentTooltipEnabled = false;
        EATEN_BY_ITEM.clear();
        UNEATEN_ELIGIBLE.clear();
        if (changed) revision++;
        return changed;
    }

    private static void initialize() {
        if (initializationComplete) return;
        try {
            if (ModList.get() == null) return;
            initializationComplete = true;
            if (!ModList.get().isLoaded("solcarrot")) return;
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> foodListClass = Class.forName(
                    "com.cazsius.solcarrot.tracking.FoodList");
            Class<?> configClass = Class.forName(
                    "com.cazsius.solcarrot.SOLCarrotConfig");
            foodListGet = lookup.findStatic(foodListClass, "get",
                    MethodType.methodType(foodListClass, Player.class));
            foodListHasEaten = lookup.findVirtual(foodListClass, "hasEaten",
                    MethodType.methodType(boolean.class, Item.class));
            foodListCount = lookup.findVirtual(foodListClass, "getEatenFoodCount",
                    MethodType.methodType(int.class));
            tooltipEnabled = lookup.findStatic(configClass, "isFoodTooltipEnabled",
                    MethodType.methodType(boolean.class));
            isAllowed = lookup.findStatic(configClass, "isAllowed",
                    MethodType.methodType(boolean.class, Item.class));
            isHearty = lookup.findStatic(configClass, "isHearty",
                    MethodType.methodType(boolean.class, Item.class));
            available = true;
        } catch (Throwable exception) {
            initializationComplete = true;
            LOGGER.warn(
                    "[RSI Grid Search] SolCarrot search compatibility is unavailable", exception);
        }
    }
}
