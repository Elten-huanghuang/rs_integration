package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.feature.resonance.magic.BotaniaResonantManaPoolBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import vazkii.botania.api.item.ManaDissolvable;
import vazkii.botania.api.mana.ManaPool;
import vazkii.botania.api.recipe.ManaInfusionRecipe;
import vazkii.botania.common.block.block_entity.mana.ManaPoolBlockEntity;
import vazkii.botania.common.crafting.BotaniaRecipeTypes;
import vazkii.botania.common.helper.EntityHelper;
import vazkii.botania.xplat.XplatAbstractions;

import java.util.List;

/**
 * Botania Mana Pool 的公共操作边界。
 *
 * <p>共振池本身已进入原生类型链；这里保留给其它仅实现 {@link ManaPool}
 * 公共接口的第三方池，避免它们被迫依赖 Botania 的内部方块实体。</p>
 */
public final class ManaPoolInterop {
    private ManaPoolInterop() {}

    public static boolean isManaPool(BlockEntity entity) {
        return entity instanceof ManaPool;
    }

    public static ManaPool pool(BlockEntity entity) {
        return entity instanceof ManaPool manaPool ? manaPool : null;
    }

    public static int current(BlockEntity entity) {
        ManaPool pool = pool(entity);
        return pool == null ? 0 : Math.max(0, pool.getCurrentMana());
    }

    public static ManaInfusionRecipe matchingRecipe(ServerLevel level, BlockPos pos,
                                                    ItemStack stack) {
        var below = level.getBlockState(pos.below());
        ManaInfusionRecipe plain = null;
        for (ManaInfusionRecipe recipe : BotaniaRecipeTypes.getRecipes(
                level, BotaniaRecipeTypes.MANA_INFUSION_TYPE).values()) {
            if (!recipe.matches(stack)) continue;
            if (recipe.getRecipeCatalyst() != null) {
                if (recipe.getRecipeCatalyst().test(below)) return recipe;
            } else if (plain == null) {
                plain = recipe;
            }
        }
        return plain;
    }

    /**
     * 处理一个落入池中的物品。原生池继承链由 collideEntityItem 负责；这个方法
     * 只用于未继承 Botania 实体、但公开实现 ManaPool 的第三方池。
     */
    public static boolean tryInfuse(ServerLevel level, BlockPos pos,
                                    ManaPool pool, ItemEntity entity) {
        if (level.isClientSide || !entity.isAlive() || entity.getItem().isEmpty()) return false;
        ItemStack input = entity.getItem();
        if (input.getItem() instanceof ManaDissolvable dissolvable) {
            dissolvable.onDissolveTick(pool, entity);
            input = entity.getItem();
            if (input.isEmpty()) return true;
        }
        if (XplatAbstractions.INSTANCE.itemFlagsComponent(entity).manaInfusionSpawned) return false;
        ManaInfusionRecipe recipe = matchingRecipe(level, pos, input);
        if (recipe == null) return false;
        int cost = Math.max(0, recipe.getManaToConsume());
        if (pool.getCurrentMana() < cost) return false;

        ItemStack output = recipe.getRecipeOutput(level.registryAccess(), input);
        if (output == null || output.isEmpty()) return false;
        Entity owner = entity.getOwner();
        pool.receiveMana(-cost);
        EntityHelper.shrinkItem(entity);
        entity.setNoPickUpDelay();

        ItemEntity result = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 1.5,
                pos.getZ() + 0.5, output);
        XplatAbstractions.INSTANCE.itemFlagsComponent(result).manaInfusionSpawned = true;
        if (owner instanceof Player player) {
            player.awardRecipes(List.of(recipe));
        }
        level.addFreshEntity(result);
        if (pool instanceof ManaPoolBlockEntity nativePool) nativePool.craftingEffect(true);
        return true;
    }

    /** 共振池的 RS 预算目标；原生池不应把 RS 预留注入进物理池。 */
    public static boolean isResonantPool(ManaPool pool) {
        return pool instanceof BotaniaResonantManaPoolBlockEntity;
    }
}
