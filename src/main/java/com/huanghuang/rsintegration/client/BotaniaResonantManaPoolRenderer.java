package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.magic.ResonantMagicBlockEntities;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.client.event.EntityRenderersEvent;
import vazkii.botania.client.render.block_entity.ManaPoolBlockEntityRenderer;
import vazkii.botania.common.block.block_entity.mana.ManaPoolBlockEntity;

/** 将自定义方块实体类型委托给 Botania 原生魔力池渲染器。 */
public final class BotaniaResonantManaPoolRenderer
        implements BlockEntityRenderer<BlockEntity> {
    private final ManaPoolBlockEntityRenderer delegate;

    private BotaniaResonantManaPoolRenderer(BlockEntityRendererProvider.Context context) {
        delegate = new ManaPoolBlockEntityRenderer(context);
    }

    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ResonantMagicBlockEntities.MANA_POOL.get(),
                context -> new BotaniaResonantManaPoolRenderer(context));
    }

    @Override
    public void render(BlockEntity entity, float partialTick,
                       PoseStack pose, MultiBufferSource buffers, int packedLight,
                       int packedOverlay) {
        if (entity instanceof ManaPoolBlockEntity pool) {
            delegate.render(pool, partialTick, pose, buffers, packedLight, packedOverlay);
        }
    }
}
