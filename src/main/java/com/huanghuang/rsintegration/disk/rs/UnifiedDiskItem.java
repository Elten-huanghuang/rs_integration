package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.config.RSStorageConfig;
import com.huanghuang.rsintegration.disk.UnifiedDiskDropProtection;
import com.huanghuang.rsintegration.util.DiskNameStyle;
import com.refinedmods.refinedstorage.api.storage.StorageType;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskProvider;
import com.refinedmods.refinedstorage.apiimpl.API;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** 固定 ITEM provider 作为原版槽位入口，另一个 Fluid 视图在挂载时补齐。 */
public final class UnifiedDiskItem extends Item implements IStorageDiskProvider {
    private static final Logger LOGGER = LogManager.getLogger(UnifiedDiskItem.class);
    public UnifiedDiskItem() { super(new Properties().stacksTo(1).fireResistant()); }

    @Override public Component getName(ItemStack stack) {
        return DiskNameStyle.guixu(super.getName(stack));
    }

    @Override public StorageType getType() { return StorageType.ITEM; }
    @Override public int getCapacity(ItemStack stack) { return -1; }
    @Override public UUID getId(ItemStack stack) { return isValid(stack) ? stack.getTag().getUUID("Id") : null; }
    @Override public void setId(ItemStack stack, UUID id) { stack.getOrCreateTag().putUUID("Id", id); }
    @Override public boolean isValid(ItemStack stack) { return stack.hasTag() && stack.getTag().hasUUID("Id"); }
    public UUID worldId(ItemStack stack) { return stack.hasTag() && stack.getTag().hasUUID("World") ? stack.getTag().getUUID("World") : null; }
    public void setIdentity(ItemStack stack, UUID id, UUID world) {
        setId(stack, id); stack.getOrCreateTag().putUUID("World", world); stack.getOrCreateTag().putInt("Format", 1);
    }

    public boolean initialize(ItemStack stack, ServerLevel level, UUID owner) {
        if (isValid(stack)) return stack.getTag().getInt("Format") == 1 && worldId(stack) != null;
        if (stack.hasTag() && (stack.getTag().contains("Id") || stack.getTag().contains("World"))) return false;
        UnifiedDiskManager manager = UnifiedDiskManager.get(level);
        if (!manager.enabled()) return false;
        try {
            // 空检查点先提交成功，最后才把 UUID 写到物品。
            UnifiedDiskRoot root = manager.create(owner);
            API.instance().getStorageDiskManager(level).set(root.id(), root);
            API.instance().getStorageDiskManager(level).markForSaving();
            setIdentity(stack, root.id(), manager.worldId());
            return true;
        } catch (IOException e) {
            LOGGER.error("[RSI] 统一盘初始化失败，保留未初始化物品", e);
            return false;
        }
    }

    @Override public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level instanceof ServerLevel serverLevel && entity instanceof ServerPlayer player && !isValid(stack)) {
            if (serverLevel.getGameTime() % 100 == 0) UnifiedDiskInventoryInitialization.request(serverLevel, player);
        }
    }

    @Override public int getEntityLifespan(ItemStack stack, Level level) { return Integer.MAX_VALUE; }

    /** 原版掉落物在扣血前调用此方法，覆盖爆炸、火、岩浆、雷击、仙人掌等伤害。 */
    @Override public boolean canBeHurtBy(DamageSource source) { return false; }

    @Override public boolean onEntityItemUpdate(ItemStack stack, ItemEntity entity) {
        UnifiedDiskDropProtection.update(entity);
        // 继续原版移动、拾取延迟、漏斗和玩家拾取逻辑。
        return false;
    }

    @Override public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        if (!RSStorageConfig.enabled(RSStorageConfig.UNIFIED_DISK)) {
            tooltip.add(Component.translatable("item.rs_integration.unified_storage_disk.disabled").withStyle(ChatFormatting.RED));
        }
        UnifiedDiskTooltip.View view = DistExecutor.unsafeCallWhenOn(Dist.CLIENT,
                () -> () -> UnifiedDiskTooltipClient.view(stack));
        UnifiedDiskTooltip.append(tooltip, getId(stack), view);
    }
}
