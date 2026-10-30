package com.huanghuang.rsintegration.feature.foodorder;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Sol Carrot 已吃记录的反射桥。
 *
 * <p>主模组并没有在编译期依赖 Sol Carrot；这里只在模组存在时按类名加载，
 * 任何链接失败都会退化为空集合，不影响点餐功能。</p>
 */
public final class FoodOrderSolCarrot {

    private static boolean initialized;
    private static boolean available;
    private static Method foodListGet;
    private static Method getEatenFoods;
    private static Method foodInstanceGetItem;

    private FoodOrderSolCarrot() {}

    public static boolean available() {
        initialize();
        return available;
    }

    /** 返回 Sol Carrot 记录的已吃物品 registry id；未安装时返回空集合。 */
    public static Set<String> eatenIds(Player player) {
        initialize();
        if (!available || player == null) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        try {
            Object foodList = foodListGet.invoke(null, player);
            if (foodList == null) {
                return result;
            }
            Object eatenFoods = getEatenFoods.invoke(foodList);
            if (!(eatenFoods instanceof Iterable<?> iterable)) {
                return result;
            }
            for (Object foodInstance : iterable) {
                if (foodInstance == null) {
                    continue;
                }
                Object item = foodInstanceGetItem.invoke(foodInstance);
                if (item instanceof Item edible) {
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(edible);
                    if (id != null) {
                        result.add(id.toString());
                    }
                }
            }
        } catch (Throwable throwable) {
            // 版本不匹配时直接关闭桥，避免每次列表构建都重复异常。
            available = false;
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-FoodOrder] Sol Carrot 已吃记录不可用，后续改读本模组存档", throwable);
        }
        return result;
    }

    private static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (!ModList.get().isLoaded("solcarrot")) {
            return;
        }
        try {
            Class<?> foodListClass = Class.forName("com.cazsius.solcarrot.tracking.FoodList");
            Class<?> foodInstanceClass = Class.forName("com.cazsius.solcarrot.tracking.FoodInstance");
            foodListGet = foodListClass.getMethod("get", Player.class);
            getEatenFoods = foodListClass.getMethod("getEatenFoods");
            foodInstanceGetItem = foodInstanceClass.getMethod("getItem");
            available = true;
        } catch (Throwable throwable) {
            available = false;
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-FoodOrder] 未找到 Sol Carrot API，跳过已吃记录合并", throwable);
        }
    }
}
