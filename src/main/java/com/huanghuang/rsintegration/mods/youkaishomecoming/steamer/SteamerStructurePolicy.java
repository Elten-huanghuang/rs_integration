package com.huanghuang.rsintegration.mods.youkaishomecoming.steamer;

import java.util.List;

/** Pure structure rules for YHK's compact and full-height steamer states. */
final class SteamerStructurePolicy {
    record Layer(boolean structural, boolean lid, boolean capped) {}

    private SteamerStructurePolicy() {}

    static boolean hasLid(List<Layer> layers) {
        boolean sawStructure = false;
        for (Layer layer : layers) {
            if (layer.structural()) {
                sawStructure = true;
                if (layer.capped()) return true;
                continue;
            }
            if (layer.lid()) return sawStructure;
            return false;
        }
        return false;
    }
}
