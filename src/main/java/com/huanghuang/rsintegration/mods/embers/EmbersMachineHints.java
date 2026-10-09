package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.util.ModIds;

import javax.annotation.Nullable;
import java.util.List;

/** JEI、EMI 和递归树共享的余烬机器使用条件提示。 */
public final class EmbersMachineHints {
    private EmbersMachineHints() {}

    public static List<String> tooltipKeys(@Nullable ModType type) {
        if (type == null) return List.of();
        return switch (type.id()) {
            case ModIds.ID_EMBERS_MELTER -> List.of(
                    "rsi.embers_machine.hint.ember_melter",
                    "rsi.embers_machine.hint.fluid_storage",
                    "rsi.embers_machine.hint.output_pipe");
            case ModIds.ID_EMBERS_MIXER -> List.of(
                    "rsi.embers_machine.hint.ember_mixer",
                    "rsi.embers_machine.hint.fluid_storage",
                    "rsi.embers_machine.hint.output_pipe");
            case ModIds.ID_EMBERS_STAMPER -> List.of(
                    "rsi.embers_machine.hint.stamp",
                    "rsi.embers_machine.hint.direct_capture",
                    "rsi.embers_machine.hint.ember_stamper",
                    "rsi.embers_machine.hint.stamper_fluid");
            default -> List.of();
        };
    }
}
