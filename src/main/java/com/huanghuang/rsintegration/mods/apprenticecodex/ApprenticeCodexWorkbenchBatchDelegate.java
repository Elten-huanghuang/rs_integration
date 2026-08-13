package com.huanghuang.rsintegration.mods.apprenticecodex;

import com.huanghuang.rsintegration.mods.common.ReflectiveMenuBatchDelegate;

public final class ApprenticeCodexWorkbenchBatchDelegate extends ReflectiveMenuBatchDelegate {
    public ApprenticeCodexWorkbenchBatchDelegate() {
        super(ApprenticeCodexRSModule.WORKBENCH_RECIPE,
                "jp.aquafactory.apprenticecodex.block.spellcasterworkbench.SpellcasterWorkbenchMenu",
                "apprenticecodex:spellcaster_workbench", 3, 3, false);
    }
}
