package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;

/**
 * 随意乐事（casualness_delight）炸锅的反射入口。
 * <p>
 * 炸锅是"放置-等待-取出"模式：把原料放进唯一的物品槽，原生 tick 在整叠上累加进度，
 * 到点后把产物作为世界掉落物从锅沿弹出并只扣掉 1 个原料；锅本身没有产物槽。
 * 因此委托走世界输出捕获，而不是槽位结算。
 */
@ModReflection(modId = ModIds.CASUALNESS_DELIGHT, description = "Casualness Delight deep frying pan")
public final class CasualnessDelightReflection {

    /** 炸锅方块实体（方块类里包名拼写就是 {@code enity}，不是 {@code entity}）。 */
    public static volatile Class<?> deepFryingPanBEClass;

    static {
        register("com.va11halla.casualness_delight.enity.DeepFryingPanEnity",
                "deepFryingPanBEClass");
    }

    private static void register(String className, String fieldName) {
        String description = ModIds.CASUALNESS_DELIGHT + "."
                + className.substring(className.lastIndexOf('.') + 1);
        try {
            Field targetField = CasualnessDelightReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(
                    ModIds.CASUALNESS_DELIGHT, description, className, false));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("CasualnessDelightReflection field not found: " + fieldName, e);
        }
    }

    /** 该方块实体是不是炸锅。 */
    public static boolean isDeepFryingPan(Object candidate) {
        return deepFryingPanBEClass != null && candidate instanceof BlockEntity
                && deepFryingPanBEClass.isInstance(candidate);
    }
}
