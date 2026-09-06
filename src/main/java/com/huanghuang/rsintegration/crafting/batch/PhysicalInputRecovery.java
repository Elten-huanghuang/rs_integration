package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.MaterialMatcher;

import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

/** Exact identity and quantity checks used before refunding physically placed inputs. */
public final class PhysicalInputRecovery {
    private PhysicalInputRecovery() {}

    public static boolean recoveredExpected(@Nullable ItemStack recovered,
                                            @Nullable ItemStack expected) {
        return recovered != null && expected != null
                && !recovered.isEmpty() && !expected.isEmpty()
                && recovered.getCount() >= expected.getCount()
                && MaterialMatcher.sameRuntimeFragment(expected, recovered);
    }

    public static boolean recoveredExpectedSlots(@Nullable List<ItemStack> recovered,
                                                 @Nullable List<ItemStack> expected) {
        if (recovered == null || expected == null || recovered.size() < expected.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            if (!recoveredExpected(recovered.get(i), expected.get(i))) return false;
        }
        return true;
    }
}
