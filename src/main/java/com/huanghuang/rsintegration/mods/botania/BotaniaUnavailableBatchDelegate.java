package com.huanghuang.rsintegration.mods.botania;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import javax.annotation.Nonnull;
/** Fail-closed until the machine-specific world protocol is implemented. */
public abstract class BotaniaUnavailableBatchDelegate extends AbstractBatchDelegate {
 protected BlockPos pos;
 @Override public boolean validateAndInit(@Nonnull ServerPlayer p,@Nonnull ResourceLocation id,ResourceLocation dim,@Nonnull BlockPos pos){this.pos=pos;this.machineDim=dim;this.machineServer=p.getServer();return false;}
 @Override public boolean tryStartSingleCraft(@Nonnull ServerPlayer p){return false;}
 @Override public boolean tryStartSingleCraft(@Nonnull ServerPlayer p,@Nonnull com.huanghuang.rsintegration.crafting.ExtractionLedger l){return false;}
 @Override public ItemStack collectResult(@Nonnull ServerPlayer p){return ItemStack.EMPTY;}
 @Override protected boolean isMachineCraftFinished(@Nonnull ServerLevel l,@Nonnull net.minecraft.world.level.block.entity.BlockEntity b){return false;}
 @Override public void onBatchFinished(@Nonnull ServerPlayer p){resetState();}
 @Override public BlockPos getMachinePos(){return pos;}
}