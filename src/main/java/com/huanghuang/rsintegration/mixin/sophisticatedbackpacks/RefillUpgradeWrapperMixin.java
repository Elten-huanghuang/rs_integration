package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.BackpackRSUtils;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.p3pp3rf1y.sophisticatedbackpacks.upgrades.refill.RefillUpgradeItem;
import net.p3pp3rf1y.sophisticatedbackpacks.upgrades.refill.RefillUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.FilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeWrapperBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.function.Consumer;

@Mixin(value = RefillUpgradeWrapper.class)
public abstract class RefillUpgradeWrapperMixin
        extends UpgradeWrapperBase<RefillUpgradeWrapper, RefillUpgradeItem> {

    @Unique
    private static final String RS_BLOCK_POS_TAG = "RSBlockPos";
    @Unique
    private static final String RS_BLOCK_DIMENSION_TAG = "RSBlockDimension";

    @Unique
    private boolean rsi$isRs;
    @Unique
    private BlockPos rsi$rsBlockPos;
    @Unique
    private ResourceKey<Level> rsi$rsDimensionKey;

    @Unique
    private static volatile boolean rsi$bcChecked;
    @Unique
    private static volatile boolean rsi$bcLoaded;
    @Unique
    private static volatile java.lang.reflect.Method rsi$isTwoHandedMethod;
    @Unique
    private static volatile boolean rsi$tsReflectionFailed;

    protected RefillUpgradeWrapperMixin(IStorageWrapper storageWrapper, ItemStack upgrade,
                                        Consumer<ItemStack> upgradeSaveHandler) {
        super(storageWrapper, upgrade, upgradeSaveHandler);
    }

    @Shadow(remap = false)
    public abstract FilterLogic getFilterLogic();

    @Shadow(remap = false)
    public abstract Map<Integer, RefillUpgradeWrapper.TargetSlot> getTargetSlots();

    @Inject(method = "<init>", at = @At(value = "RETURN"), remap = false)
    private void onInit(IStorageWrapper storageWrapper, ItemStack upgrade,
                        Consumer<ItemStack> upgradeSaveHandler, CallbackInfo ci) {
        CompoundTag tag = upgrade.getTag();
        if (tag != null && tag.contains(RS_BLOCK_POS_TAG) && tag.contains(RS_BLOCK_DIMENSION_TAG)) {
            this.rsi$isRs = true;
            this.rsi$rsBlockPos = BlockPos.of(tag.getLong(RS_BLOCK_POS_TAG));
            this.rsi$rsDimensionKey = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(tag.getString(RS_BLOCK_DIMENSION_TAG)));
        }
    }

    @Inject(method = "refillItemFor", at = @At(value = "HEAD"), remap = false, cancellable = true)
    private void onRefillItemFor(Entity entity, CallbackInfo ci) {
        if (!this.rsi$isRs) {
            if (entity instanceof Player player && rsi$checkTwoHanded(player)) {
                ci.cancel();
            }
            return;
        }
        if (!(entity instanceof Player player)) return;

        player.getCapability(ForgeCapabilities.ITEM_HANDLER, null).ifPresent(playerInv -> {
            rsi$rsRefill(player, playerInv);
        });
        ci.cancel();
    }

    @Unique
    private static volatile java.lang.reflect.Field rsi$tsMcgField;
    @Unique
    private static volatile java.lang.reflect.Field rsi$tsFillerField;
    @Unique
    private static volatile java.lang.reflect.Method rsi$mcgMethod;
    @Unique
    private static volatile java.lang.reflect.Method rsi$fillerMethod;

    @Unique
    private void rsi$rsRefill(Player player, IItemHandler playerInv) {
        INetwork network = BackpackRSUtils.getNetwork(player.level(), this.rsi$rsBlockPos, this.rsi$rsDimensionKey);
        if (network == null) return;

        IStorageCache<ItemStack> cache = network.getItemStorageCache();
        if (cache == null) return;

        FilterLogic filter = getFilterLogic();
        IItemHandler filterHandler = filter.getFilterHandler();
        if (filterHandler == null) return;
        Map<Integer, RefillUpgradeWrapper.TargetSlot> targetSlots = getTargetSlots();

        rsi$initTargetSlotReflection();
        if (rsi$tsReflectionFailed) return;

        for (int filterSlot = 0; filterSlot < filterHandler.getSlots(); filterSlot++) {
            ItemStack filterStack = filterHandler.getStackInSlot(filterSlot);
            if (filterStack.isEmpty()) continue;

            RefillUpgradeWrapper.TargetSlot targetSlot = targetSlots.getOrDefault(
                    filterSlot, RefillUpgradeWrapper.TargetSlot.ANY);

            int missing;
            try {
                Object mcg = rsi$tsMcgField.get(targetSlot);
                missing = (int) rsi$mcgMethod.invoke(mcg, player, playerInv, filterStack);
            } catch (Exception e) {
                continue;
            }

            ItemStack carried = player.containerMenu.getCarried();
            if (ItemHandlerHelper.canItemStacksStack(carried, filterStack)) {
                missing -= Math.min(missing, carried.getCount());
            }
            if (missing <= 0) continue;

            for (StackListEntry<ItemStack> cacheEntry : cache.getList().getStacks()) {
                ItemStack rsStack = cacheEntry.getStack();
                if (rsStack.isEmpty()) continue;
                if (!ItemHandlerHelper.canItemStacksStack(rsStack, filterStack)) continue;
                if (!filter.matchesFilter(rsStack)) continue;

                int toExtract = Math.min(missing, rsStack.getMaxStackSize());
                ItemStack extracted = network.extractItem(rsStack.copy(), toExtract,
                        IComparer.COMPARE_NBT, Action.PERFORM);
                if (extracted.isEmpty()) continue;

                ItemStack toFill = extracted.copy();
                ItemStack remainder;
                try {
                    Object filler = rsi$tsFillerField.get(targetSlot);
                    remainder = (ItemStack) rsi$fillerMethod.invoke(filler, player, playerInv, toFill);
                } catch (Exception e) {
                    continue;
                }

                int filled = toFill.getCount() - remainder.getCount();
                missing -= filled;

                if (filled < toFill.getCount()) {
                    ItemStack unplaced = toFill.copy();
                    unplaced.setCount(toFill.getCount() - filled);
                    ItemStack leftover = network.insertItem(unplaced, unplaced.getCount(), Action.PERFORM);
                    if (!leftover.isEmpty()) {
                        ItemHandlerHelper.giveItemToPlayer(player, leftover);
                    }
                }
                if (!remainder.isEmpty()) {
                    ItemStack leftover = network.insertItem(remainder, remainder.getCount(), Action.PERFORM);
                    if (!leftover.isEmpty()) {
                        ItemHandlerHelper.giveItemToPlayer(player, leftover);
                    }
                }
                if (missing <= 0) break;
            }
        }
    }

    @Unique
    private static void rsi$initTargetSlotReflection() {
        if (rsi$tsMcgField != null) return;
        try {
            Class<?> tsClass = RefillUpgradeWrapper.TargetSlot.class;
            rsi$tsMcgField = tsClass.getDeclaredField("missingCountGetter");
            rsi$tsMcgField.setAccessible(true);
            rsi$tsFillerField = tsClass.getDeclaredField("filler");
            rsi$tsFillerField.setAccessible(true);

            rsi$mcgMethod = rsi$tsMcgField.getType().getMethod(
                    "getMissingCount", Player.class, IItemHandler.class, ItemStack.class);
            rsi$fillerMethod = rsi$tsFillerField.getType().getMethod(
                    "fill", Player.class, IItemHandler.class, ItemStack.class);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-SB] TargetSlot reflection unavailable — RS refill disabled: {}",
                    e.toString());
            rsi$tsReflectionFailed = true;
        }
    }

    @Unique
    private static boolean rsi$checkTwoHanded(Player player) {
        if (!rsi$bcChecked) {
            rsi$bcChecked = true;
            try {
                Class<?> cls = Class.forName("net.bettercombat.logic.PlayerAttackHelper");
                rsi$isTwoHandedMethod = cls.getMethod("isTwoHandedWielding", Player.class);
                rsi$bcLoaded = true;
            } catch (Exception e) {
                rsi$bcLoaded = false;
            }
        }
        if (!rsi$bcLoaded) return false;
        try {
            return (boolean) rsi$isTwoHandedMethod.invoke(null, player);
        } catch (Exception e) {
            return false;
        }
    }
}
