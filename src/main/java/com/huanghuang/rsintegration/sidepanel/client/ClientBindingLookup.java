package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.network.binding.MachineBindingService;
import com.huanghuang.rsintegration.sidepanel.data.BindingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/** Client-side read-only lookup for server-synchronized machine bindings. */
public final class ClientBindingLookup {
    private static boolean registered;

    private ClientBindingLookup() {}

    public static void register() {
        if (registered) return;
        registered = true;
        MachineBindingService.installClientBindingView(ClientBindingLookup::getView);
    }

    public static List<BindingStorage.BindingEntry> getBindings(ItemStack stack) {
        UUID bookId = MachineBindingService.bookId(stack);
        if (bookId == null) return BindingStorage.readLegacyBindings(stack);
        return BindingCache.getInstance().getEntriesByBookId(bookId);
    }

    public static boolean hasBinding(ItemStack stack, ResourceLocation dim, BlockPos pos) {
        for (BindingStorage.BindingEntry entry : getBindings(stack)) {
            if (entry.dim().equals(dim) && entry.pos().equals(pos)) return true;
        }
        return false;
    }

    private static List<BindingStorage.BindingEntry> getView(ItemStack stack) {
        return getBindings(stack);
    }
}
