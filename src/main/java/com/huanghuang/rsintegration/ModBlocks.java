package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.magic.ResonantMagicBlock;
import com.huanghuang.rsintegration.magic.ResonantManaPoolBlock;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** RS 专用的跨维度魔法存储方块。具体能量能力在后续模块中接入。 */
public final class ModBlocks {

    private static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, RSIntegrationMod.MOD_ID);

    public static final RegistryObject<Block> RESONANT_MANA_POOL = BLOCKS.register(
            "resonant_mana_pool",
            () -> createManaPoolBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    public static final RegistryObject<Block> RESONANT_SOURCE_TANK = BLOCKS.register(
            "resonant_source_tank",
            () -> new ResonantMagicBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    private ModBlocks() {}

    private static Block createManaPoolBlock(BlockBehaviour.Properties properties) {
        if (ModList.get().isLoaded(ModIds.BOTANIA)) {
            try {
                Class<?> type = Class.forName(
                        "com.huanghuang.rsintegration.mods.botania.BotaniaResonantManaPoolBlock",
                        true, ModBlocks.class.getClassLoader());
                return (Block) type.getConstructor(BlockBehaviour.Properties.class)
                        .newInstance(properties);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Botania 初始化失败时仍保留普通共振池方块。
            }
        }
        return new ResonantManaPoolBlock(properties);
    }

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
