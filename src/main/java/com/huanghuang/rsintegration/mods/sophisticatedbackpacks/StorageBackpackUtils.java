package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageResolutionResult;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.util.ExternalItemProgressSuppression;
import com.huanghuang.rsintegration.util.RsOperationPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.crafting.Ingredient;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.FilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidUpgradeWrapper;

import java.util.List;

/** RS magnet operations through the backend-neutral storage session. */
public final class StorageBackpackUtils {
    private static final StorageBackendId RS = new StorageBackendId("refinedstorage");

    private StorageBackpackUtils() {}

    public static boolean insertItem(ContentsFilterLogic filter, ItemEntity entity,
                              BlockPos position, ResourceKey<Level> dimension,
                              List<VoidUpgradeWrapper> voidUpgrades, boolean voidUpgrade) {
        ItemStack stack = entity.getItem();
        if (stack.isEmpty() || !filter.matchesFilter(stack)) return false;
        if (voidUpgrade && matchesVoidFilter(stack, voidUpgrades)) {
            ExternalItemProgressSuppression.suppress();
            entity.discard();
            return true;
        }
        ServerPlayer player = RsOperationPlayerContext.current();
        StorageSession session = resolve(player, position, dimension);
        if (session == null) return false;
        StorageOperationResult result = session.insert(player, stack.copy(), false);
        if (!result.remainder().isPresent()) return false;
        ItemStack remainder = result.remainder().orElseThrow();
        entity.setItem(remainder);
        if (result.status() == StorageOperationStatus.SUCCESS) {
            entity.discard();
            return true;
        }
        return false;
    }

    public static ItemStack pickupItem(ContentsFilterLogic filter, Level level, ItemStack stack,
                                boolean simulate, BlockPos position, ResourceKey<Level> dimension,
                                List<VoidUpgradeWrapper> voidUpgrades, boolean voidUpgrade) {
        if (stack.isEmpty() || !filter.matchesFilter(stack)) return stack;
        if (voidUpgrade && matchesVoidFilter(stack, voidUpgrades)) {
            ExternalItemProgressSuppression.suppress();
            return ItemStack.EMPTY;
        }
        ServerPlayer player = RsOperationPlayerContext.current();
        StorageSession session = resolve(player, position, dimension);
        if (session == null) return stack;
        StorageOperationResult result = session.insert(player, stack.copy(), simulate);
        if (!result.remainder().isPresent()) return stack;
        return result.remainder().orElseThrow();
    }

    private static boolean matchesVoidFilter(ItemStack stack, List<VoidUpgradeWrapper> upgrades) {
        for (VoidUpgradeWrapper upgrade : upgrades) {
            if (upgrade.isEnabled() && upgrade.getFilterLogic().matchesFilter(stack)) return true;
        }
        return false;
    }

    public static StorageSession resolve(ServerPlayer player, BlockPos position,
                                          ResourceKey<Level> dimension) {
        if (player == null || player.server == null) return null;
        String networkId = "v1|" + dimension.location() + "@"
                + position.getX() + "," + position.getY() + "," + position.getZ();
        StorageReference reference = new StorageReference(RS, networkId);
        StorageResolutionResult result = RSIntegrationMod.STORAGE_BACKENDS.registry()
                .resolve(reference, player);
        return result.resolved() ? result.session().orElse(null) : null;
    }

    public static StorageOperationResult extractExact(StorageSession session, ServerPlayer player,
                                                       ItemStack template, int amount, boolean simulate) {
        if (session == null || player == null || template == null || template.isEmpty() || amount <= 0) {
            return null;
        }
        return session.extractExact(player, session.itemKey(template), amount, simulate);
    }

    public static StorageOperationResult extractMatching(StorageSession session, ServerPlayer player,
                                                          Ingredient ingredient, int amount, boolean simulate) {
        if (session == null || player == null || ingredient == null || amount <= 0) return null;
        return session.extractMatching(player, ingredient, amount, simulate);
    }
}
