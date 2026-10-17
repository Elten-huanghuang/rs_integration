package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.mixin.core.BlockEntityTypeAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import vazkii.botania.api.BotaniaForgeCapabilities;
import vazkii.botania.api.block.Wandable;
import vazkii.botania.api.mana.ManaReceiver;
import vazkii.botania.api.mana.spark.SparkAttachable;
import vazkii.botania.common.block.block_entity.mana.ManaPoolBlockEntity;

/** Botania 原生 ManaPool/Spark 适配；容量跟随绑定网络中的归墟盘总容量。 */
public final class BotaniaResonantManaPoolBlockEntity extends ManaPoolBlockEntity
        implements ResonantMagicStorage {
    private static final String OUTPUTTING = "BotaniaOutputting";
    private final ResonantMagicStorageData storage;
    private LazyOptional<ManaReceiver> manaReceiver = LazyOptional.of(() -> this);
    private LazyOptional<SparkAttachable> sparkAttachable = LazyOptional.of(() -> this);
    private LazyOptional<Wandable> wandable = LazyOptional.of(() -> this);
    private boolean outputting;

    public BotaniaResonantManaPoolBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(pos, state);
        setRegisteredType(this, type);
        storage = new ResonantMagicStorageData(this, MagicResourceKind.MANA,
                MagicResourceKind.MANA.capacity());
    }

    private static void setRegisteredType(ManaPoolBlockEntity entity, BlockEntityType<?> type) {
        if (entity instanceof BlockEntityTypeAccessor accessor) {
            accessor.rsi$setType(type);
        }
    }

    @Override public MagicResourceKind resourceKind() { return storage.kind(); }
    @Override public int resourceCapacity() { return storage.resourceCapacity(); }
    @Override public boolean isBound() { return storage.isBound(); }
    @Override public CompoundTag binding() { return storage.binding(); }
    @Override public void setBinding(CompoundTag next) { storage.setBinding(next); }
    @Override public void clearBinding() { storage.clearBinding(); }
    @Override public boolean isBoundTo(CompoundTag next) { return storage.isBoundTo(next); }
    @Override public int currentAmount() { return storage.currentAmount(); }
    @Override public boolean reachable() { return storage.reachable(); }
    @Override public int insert(int amount) { return storage.insert(amount); }
    @Override public int extract(int amount) { return storage.extract(amount); }
    @Override public void serverTick() { storage.serverTick(); }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
        if (capability == BotaniaForgeCapabilities.MANA_RECEIVER) return manaReceiver.cast();
        if (capability == BotaniaForgeCapabilities.SPARK_ATTACHABLE) return sparkAttachable.cast();
        if (capability == BotaniaForgeCapabilities.WANDABLE) return wandable.cast();
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        manaReceiver.invalidate();
        sparkAttachable.invalidate();
        wandable.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        manaReceiver = LazyOptional.of(() -> this);
        sparkAttachable = LazyOptional.of(() -> this);
        wandable = LazyOptional.of(() -> this);
    }

    @Override public int getCurrentMana() { return storage.currentAmount(); }
    @Override public boolean isFull() { return getCurrentMana() >= resourceCapacity(); }
    @Override public void receiveMana(int mana) {
        if (mana > 0) insert(ManaPoolManaMath.insertRequest(
                mana, getCurrentMana(), resourceCapacity()));
        if (mana < 0) extract(ManaPoolManaMath.extractRequest(mana, getCurrentMana()));
    }

    @Override public boolean canReceiveManaFromBursts() {
        return isBound() && reachable() && !isFull();
    }
    @Override public boolean isOutputtingPower() { return outputting; }
    @Override public int getMaxMana() { return resourceCapacity(); }
    @Override public int getAvailableSpaceForMana() {
        return isBound() && reachable()
                ? Math.max(0, resourceCapacity() - getCurrentMana()) : 0;
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
        storage.save(tag);
        tag.putBoolean(OUTPUTTING, outputting);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        storage.load(tag);
        outputting = tag.getBoolean(OUTPUTTING);
    }

    @Override
    public void writePacketNBT(CompoundTag tag) {
        super.writePacketNBT(tag);
        storage.save(tag);
        tag.putBoolean(OUTPUTTING, outputting);
    }

    @Override
    public void readPacketNBT(CompoundTag tag) {
        super.readPacketNBT(tag);
        storage.load(tag);
        outputting = tag.getBoolean(OUTPUTTING);
    }
}
