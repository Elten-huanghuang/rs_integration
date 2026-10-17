package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;

/**
 * Let's Do 系列（farm_and_charm / candlelight）烹饪设备的反射入口。
 * <p>
 * 这两个模组共用一套烹饪机制：{@code farm_and_charm} 定义配方类型
 * {@code farm_and_charm:pot_cooking} 与配方类 {@code ...recipe.CookingPotRecipe}，
 * candlelight 的「大汤锅」（烛火晚宴的汤锅/铜汤锅）直接复用同一套配方类型，
 * 只是方块实体换成了 {@code net.satisfy.candlelight.entity.LargeCookingPotBlockEntity}。
 * <p>
 * 它们的库存都没有注册 {@code IItemHandler} 能力（只实现了 doapi 的
 * {@code ImplementedInventory}），因此委托只能反射 {@code getItems()}。
 * <p>
 * 两个模组各自独立可缺省：candlelight 需要 farm_and_charm 才能启动，
 * 但 farm_and_charm 不需要 candlelight，所以字段一律声明为可选，
 * 类缺失时只把对应字段留空，不做硬链接。
 */
@ModReflection(modId = ModIds.FARM_AND_CHARM, description = "Let's Do cooking machines (farm_and_charm / candlelight)")
public final class LetsDoReflection {

    /** farm_and_charm 烹饪锅的方块实体（也用于校验 candlelight 复用的配方类型）。 */
    public static volatile Class<?> farmAndCharmCookingPotBE;
    /** 烘焙机方块实体。 */
    public static volatile Class<?> farmAndCharmRoasterBE;
    /** candlelight 大汤锅方块实体（烛火晚宴的汤锅/铜汤锅）。 */
    public static volatile Class<?> candlelightCookingPotBE;
    /** bakery 小烹饪锅方块实体（与 candlelight 共用 farm_and_charm 的配方类型）。 */
    public static volatile Class<?> bakeryCookingPotBE;

    static {
        register("net.satisfy.farm_and_charm.block.entity.CookingPotBlockEntity",
                "farmAndCharmCookingPotBE", false);
        register("net.satisfy.farm_and_charm.block.entity.RoasterBlockEntity",
                "farmAndCharmRoasterBE", false);
        register("net.satisfy.candlelight.entity.LargeCookingPotBlockEntity",
                "candlelightCookingPotBE", false);
        register("net.satisfy.bakery.block.entity.SmallCookingPotBlockEntity",
                "bakeryCookingPotBE", false, ModIds.BAKERY);
    }

    private static void register(String className, String fieldName, boolean required) {
        register(className, fieldName, required, ModIds.FARM_AND_CHARM);
    }

    private static void register(String className, String fieldName, boolean required,
                                String modId) {
        String description = modId + "." + className.substring(className.lastIndexOf('.') + 1);
        try {
            Field targetField = LetsDoReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(
                    modId, description, className, required));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("LetsDoReflection field not found: " + fieldName, e);
        }
    }

    /** farm_and_charm 烹饪锅。 */
    public static boolean isFarmAndCharmCookingPot(Object blockEntity) {
        return matches(farmAndCharmCookingPotBE, blockEntity);
    }

    /** farm_and_charm 烘焙机。 */
    public static boolean isFarmAndCharmRoaster(Object blockEntity) {
        return matches(farmAndCharmRoasterBE, blockEntity);
    }

    /** candlelight 大汤锅（烛火晚宴的汤锅/铜汤锅）。 */
    public static boolean isCandlelightCookingPot(Object blockEntity) {
        return matches(candlelightCookingPotBE, blockEntity);
    }

    /** bakery 小烹饪锅。 */
    public static boolean isBakeryCookingPot(Object blockEntity) {
        return matches(bakeryCookingPotBE, blockEntity);
    }

    /**
     * 只按 {@code Class} 对象判断实例归属：探针字段里存的是 {@code Class<?>}，
     * 类缺失时保持为 null，不会触发可选依赖的类加载。
     */
    private static boolean matches(Class<?> type, Object candidate) {
        if (type == null || !(candidate instanceof BlockEntity)) return false;
        return type.isInstance(candidate);
    }
}
