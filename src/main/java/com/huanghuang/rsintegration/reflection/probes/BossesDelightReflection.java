package com.huanghuang.rsintegration.reflection.probes;

import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.reflection.contract.ReflectionContract;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;

/**
 * BOMD 乐事（bosses_delight）灵魂锅的反射入口。
 * <p>
 * 灵魂锅的方块实体继承了农夫乐事的 {@code SyncedBlockEntity}，并自带一套
 * 9 槽 {@code ItemStackHandler}；它与农夫乐事烹饪锅的差别在于：热源不是"下方的火"，
 * 而是下方方块的 {@code bosses_delight:cold_sources} 标签（冰 / 浮冰 / 蓝冰），
 * 且配方类型独立（{@code bosses_delight:soul_cooking}）。
 * <p>
 * 只缓存方块实体类用于"这台机器是不是灵魂锅"的判定；热源与配方判定都走
 * 委托里的类名/标签比较，不硬链接可选依赖。
 */
@ModReflection(modId = ModIds.BOSSES_DELIGHT, description = "BOMD Delight soul cooking pot")
public final class BossesDelightReflection {

    /** 灵魂锅方块实体。 */
    public static volatile Class<?> soulCookingPotBEClass;

    static {
        register("io.github.rcneg.bossesdelight.blocks.entity.SoulCookingPotBlockEntity",
                "soulCookingPotBEClass");
    }

    private static void register(String className, String fieldName) {
        String description = ModIds.BOSSES_DELIGHT + "."
                + className.substring(className.lastIndexOf('.') + 1);
        try {
            Field targetField = BossesDelightReflection.class.getDeclaredField(fieldName);
            ContractValidation.register(new ReflectionContract(
                    ModIds.BOSSES_DELIGHT, description, className, false));
            ContractValidation.registerTarget(description, targetField);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("BossesDelightReflection field not found: " + fieldName, e);
        }
    }

    /** 该方块实体是不是灵魂锅。 */
    public static boolean isSoulCookingPot(Object candidate) {
        return soulCookingPotBEClass != null && candidate instanceof BlockEntity
                && soulCookingPotBEClass.isInstance(candidate);
    }
}
