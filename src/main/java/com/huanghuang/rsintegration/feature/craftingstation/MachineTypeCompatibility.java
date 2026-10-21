package com.huanghuang.rsintegration.feature.craftingstation;

import com.huanghuang.rsintegration.util.ModIds;

/** 客户端可用性提示与服务端执行共用的机器类型兼容规则。 */
public final class MachineTypeCompatibility {
    private MachineTypeCompatibility() {}

    public static boolean isCompatible(
            String requestedTypeId, String boundTypeId) {
        if (requestedTypeId == null || boundTypeId == null) return false;
        if (requestedTypeId.equals(boundTypeId)) return true;

        // Let's Do 的三种锅共用 farm_and_charm 的配方分类。
        if (ModIds.ID_FC_COOKING_POT.equals(requestedTypeId)) {
            return ModIds.ID_CANDLELIGHT_COOKING_POT.equals(boundTypeId)
                    || ModIds.ID_BAKERY_COOKING_POT.equals(boundTypeId);
        }
        if (ModIds.ID_CANDLELIGHT_COOKING_POT.equals(requestedTypeId)
                || ModIds.ID_BAKERY_COOKING_POT.equals(requestedTypeId)) {
            return ModIds.ID_FC_COOKING_POT.equals(boundTypeId);
        }

        return switch (boundTypeId) {
            case "ironfurnaces_furnace" ->
                    "vanilla_furnace".equals(requestedTypeId);
            case "ironfurnaces_blast_furnace" ->
                    "vanilla_blast_furnace".equals(requestedTypeId);
            case "ironfurnaces_smoker" ->
                    "vanilla_smoker".equals(requestedTypeId);
            default -> false;
        };
    }
}
