package com.huanghuang.rsintegration.mods.botania;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import vazkii.botania.api.block.PetalApothecary;
import vazkii.botania.api.recipe.PetalApothecaryRecipe;
import vazkii.botania.common.block.block_entity.PetalApothecaryBlockEntity;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
public final class PetalApothecaryBatchDelegate extends AbstractBatchDelegate {
 private ServerLevel level; private BlockPos pos; private PetalApothecaryRecipe recipe; private INetwork network; private ItemStack expected=ItemStack.EMPTY; private boolean started; private long startTick; private java.util.Set<java.util.UUID> entitiesBefore=java.util.Set.of();
 @Override public boolean validateAndInit(@Nonnull ServerPlayer p,@Nonnull ResourceLocation id,ResourceLocation dim,@Nonnull BlockPos at){pos=at;machineDim=dim;machineServer=p.getServer();level=dim==null?p.serverLevel():p.getServer().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,dim));if(level==null||!(level.getBlockEntity(pos) instanceof PetalApothecaryBlockEntity))return false;var r=level.getRecipeManager().byKey(id).orElse(null);if(!(r instanceof PetalApothecaryRecipe rr))return false;recipe=rr;expected=rr.getResultItem(level.registryAccess()).copy();network=CraftPacketUtils.resolveNetworkForCraft(p,level.dimension(),pos);return network!=null&&!expected.isEmpty();}
 @Override public List<IngredientSpec> getRequiredMaterials(){if(recipe==null)return null;List<IngredientSpec>s=new ArrayList<>();for(Ingredient i:recipe.getIngredients())if(!i.isEmpty())s.add(new IngredientSpec(i,1));if(!recipe.getReagent().isEmpty())s.add(new IngredientSpec(recipe.getReagent(),1));return s;}
 @Override public boolean tryStartSingleCraft(@Nonnull ServerPlayer p){List<ItemStack> m=BotaniaDelegateSupport.extractAtomically(network,getRequiredMaterials());return !m.isEmpty()&&start(m);}
 @Override public boolean tryStartSingleCraft(@Nonnull ServerPlayer p,@Nonnull ExtractionLedger l){return false;}
 @Override public boolean tryStartWithMaterials(@Nonnull ServerPlayer p,@Nonnull List<ItemStack>m,@Nonnull ExtractionLedger l){return start(m);}
 private boolean start(List<ItemStack>m){entitiesBefore=BotaniaDelegateSupport.snapshot(level,new AABB(pos).inflate(1.5));if(!(level.getBlockEntity(pos) instanceof PetalApothecaryBlockEntity be))return false;if(be.getFluid()==PetalApothecary.State.LAVA)return false;if(be.getFluid()==PetalApothecary.State.EMPTY)be.setFluid(PetalApothecary.State.WATER,false);for(ItemStack s:m){ItemEntity e=new ItemEntity(level,pos.getX()+.5,pos.getY()+1.1,pos.getZ()+.5,s.copy());e.setDeltaMovement(0,0,0);BotaniaDelegateSupport.protectOperationInput(e);level.addFreshEntity(e);be.collideEntityItem(e);}started=true;startTick=level.getGameTime();markCraftStarted();return true;}
 @Override protected boolean isMachineCraftFinished(@Nonnull ServerLevel l,@Nonnull BlockEntity be){return started&&l.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(1.5),e->BotaniaDelegateSupport.isNew(e,entitiesBefore)&&e.isAlive()&&!e.getItem().isEmpty()&&ItemStack.isSameItem(e.getItem(),expected)&&l.getGameTime()>=startTick).stream().findAny().isPresent();}
 @Override public ItemStack collectResult(@Nonnull ServerPlayer p){for(ItemEntity e:level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(1.5),x->BotaniaDelegateSupport.isNew(x,entitiesBefore)&&x.isAlive()&&ItemStack.isSameItem(x.getItem(),expected))){ItemStack s=e.getItem().copy();e.discard();return s;}return ItemStack.EMPTY;}
 @Override public ItemStack getExpectedOutput(){return expected.isEmpty()?null:expected;}@Override public AABB getOutputCaptureRegion(){return new AABB(pos).inflate(1.5);}@Override public BlockPos getMachinePos(){return pos;}@Override public void onBatchFinished(@Nonnull ServerPlayer p){resetState();}
}