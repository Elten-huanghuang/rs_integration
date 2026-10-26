package com.huanghuang.rsintegration.api.machine;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 声明玩家可绑定的真实机器以及选中方块后的输入输出结构。
 *
 * <p>绑定发现和 {@link #supports} 只判断稳定身份，不得读取机器进度、扫描多方块或修改
 * 世界。世界相关状态应在 {@link #describe} 或 {@link MachineStateProvider} 中读取。</p>
 */
public interface MachineIntegration {
    /**
     * 返回稳定且带自身 namespace 的集成 ID。
     */
    ResourceLocation id();

    /**
     * 声明该机器可由玩家绑定的真实方块。
     *
     * <p>该方法只描述稳定身份，不得读取世界状态或扫描附近方块。</p>
     */
    List<MachineBinding> bindings();

    /**
     * 判断方块实体是否属于该机器协议。
     *
     * <p>该方法不能读取可变机器状态。</p>
     */
    boolean supports(BlockEntity blockEntity);

    /**
     * 返回当前方块的端口、执行模式和并发合同。
     */
    MachineDescriptorResult describe(
            MachineContext context,
            BlockEntity blockEntity
    );

    /**
     * 声明各 process 在绑定具体方块前即可确定的并发合同。
     *
     * <p>返回值以 process ID 为 key。未声明或声明不完整的 process 仍会
     * 按 exclusive 处理；运行期 descriptor 必须与声明兼容。</p>
     */
    default Map<ResourceLocation, PlannedConcurrency>
    planningConcurrency() {
        return Map.of();
    }

    /**
     * 返回机器的内部完整物品库存。
     *
     * <p>只有 {@link ItemPortAccess#MACHINE_INVENTORY} 端口会使用该库存。
     * 返回的槽位编号必须与 descriptor 中的端口槽位一致。</p>
     */
    default Optional<IItemHandler> internalItemHandler(
            MachineContext context,
            BlockEntity blockEntity
    ) {
        return Optional.empty();
    }

    /**
     * 返回可选的状态提供器。TICKED_AND_BUSY 机器必须提供。
     */
    default Optional<MachineStateProvider> stateProvider() {
        return Optional.empty();
    }
}
