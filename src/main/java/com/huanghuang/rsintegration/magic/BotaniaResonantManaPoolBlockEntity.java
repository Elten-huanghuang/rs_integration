package com.huanghuang.rsintegration.magic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import vazkii.botania.api.block.Wandable;
import vazkii.botania.api.mana.ManaPool;
import vazkii.botania.api.mana.spark.ManaSpark;
import vazkii.botania.api.mana.spark.SparkAttachable;

import java.util.Optional;

/** Botania 原生 ManaPool/Spark 适配；容量跟随绑定网络中的归墟盘总容量。 */
public final class BotaniaResonantManaPoolBlockEntity extends ResonantMagicBlockEntity
        implements ManaPool, SparkAttachable, Wandable {
    private static final String OUTPUTTING = "BotaniaOutputting";
    private boolean outputting;

    public BotaniaResonantManaPoolBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state, MagicResourceKind.MANA, MagicResourceKind.MANA.capacity());
    }

    @Override public Level getManaReceiverLevel() { return level; }
    @Override public BlockPos getManaReceiverPos() { return worldPosition; }
    @Override public int getCurrentMana() { return currentAmount(); }
    @Override public boolean isFull() { return getCurrentMana() >= resourceCapacity(); }
    @Override public void receiveMana(int mana) {
        if (mana > 0) {
            int space = Math.max(0, resourceCapacity() - getCurrentMana());
            insert(Math.min(mana, space));
        } else if (mana < 0) {
            int requested = (int) Math.min(-(long) mana, Integer.MAX_VALUE);
            extract(Math.min(requested, Math.max(0, getCurrentMana())));
        }
    }
    @Override public boolean canReceiveManaFromBursts() { return isBound() && !isFull() && reachable(); }
    @Override public boolean isOutputtingPower() { return outputting; }
    @Override public int getMaxMana() { return resourceCapacity(); }
    @Override public Optional<DyeColor> getColor() { return Optional.empty(); }
    @Override public void setColor(Optional<DyeColor> color) {}
    @Override public boolean canAttachSpark(ItemStack stack) { return true; }
    @Override public ManaSpark getAttachedSpark() {
        if (level == null) return null;
        AABB area = new AABB(worldPosition.above(), worldPosition.above().offset(1, 1, 1));
        var sparks = level.getEntitiesOfClass(Entity.class, area).stream()
                .filter(ManaSpark.class::isInstance)
                .map(ManaSpark.class::cast)
                .toList();
        return sparks.size() == 1 ? sparks.get(0) : null;
    }
    @Override public int getAvailableSpaceForMana() {
        return isBound() && reachable() ? Math.max(0, resourceCapacity() - getCurrentMana()) : 0;
    }
    @Override public boolean areIncomingTranfersDone() { return false; }

    /** 复用 Botania 森林法杖的原生池子模式切换：输出模式允许 Spark 抽取。 */
    @Override
    public boolean onUsedByWand(Player player, ItemStack stack, Direction side) {
        if ((player == null || player.isShiftKeyDown())
                && level != null && !level.isClientSide) {
            outputting = !outputting;
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        return true;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean(OUTPUTTING, outputting);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        outputting = tag.getBoolean(OUTPUTTING);
    }
}
