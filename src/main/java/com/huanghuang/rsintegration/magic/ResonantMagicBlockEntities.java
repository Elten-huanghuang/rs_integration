package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.ModBlocks;
import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.eventbus.api.IEventBus;

/** 共振方块实体注册；可选模组缺失时使用通用实体。 */
public final class ResonantMagicBlockEntities {
    private static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(
            ForgeRegistries.BLOCK_ENTITY_TYPES, RSIntegrationMod.MOD_ID);

    public static final RegistryObject<BlockEntityType<BlockEntity>> MANA_POOL =
            TYPES.register("resonant_mana_pool", () -> BlockEntityType.Builder.of(
                    ResonantMagicBlockEntities::createMana, ModBlocks.RESONANT_MANA_POOL.get())
                    .build(null));
    public static final RegistryObject<BlockEntityType<ResonantMagicBlockEntity>> SOURCE_TANK =
            TYPES.register("resonant_source_tank", () -> BlockEntityType.Builder.of(
                    ResonantMagicBlockEntities::createSource, ModBlocks.RESONANT_SOURCE_TANK.get())
                    .build(null));

    private ResonantMagicBlockEntities() {}

    public static void init(IEventBus bus) {
        TYPES.register(bus);
    }

    private static BlockEntity createMana(net.minecraft.core.BlockPos pos,
                                          net.minecraft.world.level.block.state.BlockState state) {
        if (ModList.get().isLoaded("botania")) {
            BlockEntity nativeEntity = createOptional(
                    "com.huanghuang.rsintegration.magic.BotaniaResonantManaPoolBlockEntity",
                    pos, state, MANA_POOL.get());
            if (nativeEntity != null) return nativeEntity;
        }
        return new ResonantMagicBlockEntity(MANA_POOL.get(), pos, state, MagicResourceKind.MANA,
                MagicResourceKind.MANA.capacity());
    }

    private static ResonantMagicBlockEntity createSource(net.minecraft.core.BlockPos pos,
                                                          net.minecraft.world.level.block.state.BlockState state) {
        if (ModList.get().isLoaded("ars_nouveau")) {
            BlockEntity optional = createOptional(
                    "com.huanghuang.rsintegration.magic.ArsResonantSourceTankBlockEntity",
                    pos, state, SOURCE_TANK.get());
            if (optional instanceof ResonantMagicBlockEntity nativeEntity) return nativeEntity;
        }
        return new ResonantMagicBlockEntity(SOURCE_TANK.get(), pos, state, MagicResourceKind.SOURCE,
                MagicResourceKind.SOURCE.capacity());
    }

    private static BlockEntity createOptional(String className,
                                              net.minecraft.core.BlockPos pos,
                                              net.minecraft.world.level.block.state.BlockState state,
                                              BlockEntityType<?> type) {
        try {
            Class<?> clazz = Class.forName(className, true,
                    ResonantMagicBlockEntities.class.getClassLoader());
            return (BlockEntity) clazz
                    .getConstructor(BlockEntityType.class, net.minecraft.core.BlockPos.class,
                            net.minecraft.world.level.block.state.BlockState.class)
                    .newInstance(type, pos, state);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    public static BlockEntityType<?> typeFor(net.minecraft.world.level.block.Block block) {
        if (block == ModBlocks.RESONANT_MANA_POOL.get()) return MANA_POOL.get();
        if (block == ModBlocks.RESONANT_SOURCE_TANK.get()) return SOURCE_TANK.get();
        return null;
    }
}
