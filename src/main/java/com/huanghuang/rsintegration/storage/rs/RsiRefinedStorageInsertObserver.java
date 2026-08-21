package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.storage.StorageInsertObserver;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Preserves RSI's existing cache and menu notifications for tracked RS inserts. */
final class RsiRefinedStorageInsertObserver implements StorageInsertObserver {
    static final RsiRefinedStorageInsertObserver INSTANCE = new RsiRefinedStorageInsertObserver();

    private RsiRefinedStorageInsertObserver() {}

    @Override
    public void beforePerform(ServerPlayer player, StorageReference reference,
                              ItemStack acceptedEstimate) {
        MaterialSources.invalidateFor(player);
        player.containerMenu.broadcastChanges();
    }
}
