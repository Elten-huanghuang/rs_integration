package com.huanghuang.rsintegration.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** 多个功能共享的文案。保留翻译组件，由游戏按当前语言渲染。 */
public enum SharedText {
    ALL("rsi.common.all"),
    ITEMS("rsi.common.items"),
    FAVORITE("rsi.common.favorite"),
    REFRESH("rsi.common.refresh"),
    SEARCH_HINT("rsi.common.search_hint"),
    IDLE("rsi.common.idle"),
    RECIPE("rsi.common.recipe"),
    DETAIL_PAIR("rsi.common.detail_pair"),
    CRAFT_STEPS("rsi.progress.hud.steps"),
    MISSING_MATERIAL("rsi.plan.material.missing"),
    STORAGE_NETWORK("rsi.common.storage_network"),
    STORAGE_NETWORK_UNAVAILABLE("rsi.progress.reason.network_unavailable"),
    OPEN_BOUND_MACHINE("rsi.jei.open_machine"),
    CRAFT_AND_SUBMIT_QUEST("gui.rs_integration.jei.ftb_quest_submit"),
    RESONANCE_BACKPACK("rsi.resonance_backpack.title"),
    AUTO_EAT("rsi.auto_eat.page"),
    RITUAL_PEDESTALS_NOT_FOUND("rsi.fa.error.pedestal_not_found"),
    SPIRIT_REQUIRED("rsi.malum.warn.spirit_required"),
    UPGRADE_BIND_GUIDE("item.rs_integration.equipment.guide.bind_upgrade"),
    BLOCK_BIND_GUIDE("item.rs_integration.equipment.guide.bind_block"),
    BLOCK_UNBIND_GUIDE("item.rs_integration.equipment.guide.unbind_block");

    private final String key;

    SharedText(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public MutableComponent text(Object... args) {
        return Component.translatable(key, args);
    }

    public static String machineTypeKey(String typeId) {
        return "rsi.batch.mod." + typeId;
    }
}
