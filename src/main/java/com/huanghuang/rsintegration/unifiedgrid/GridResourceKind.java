package com.huanghuang.rsintegration.unifiedgrid;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.magic.MagicResourceStack;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 混合终端的资源类别：物品、流体固定占前两位，后面按当前装好的魔法资源追加。
 *
 * <p>类别编号会随安装组合变化，所以它只用于一次登录会话内的会话身份和大类分组，
 * 不能当作跨端的稳定身份：真正稳定的身份是资源注册名（物品 ID、流体 ID、
 * {@link MagicResourceKind#id()}），增量同步也只在同一会话世代里比较编号。
 * 物品、流体与魔法资源各自计量，物品个数与流体 mB 不互相换算。</p>
 */
public final class GridResourceKind {
    public static final int ITEM_ID = 0;
    public static final int FLUID_ID = 1;
    /** 第一个魔法资源类别编号；没有装任何魔法模组时等于 {@link #count()}。 */
    public static final int FIRST_MAGIC_ID = 2;

    private static final GridResourceKind ITEM = new GridResourceKind(ITEM_ID, null);
    private static final GridResourceKind FLUID = new GridResourceKind(FLUID_ID, null);

    private final int id;
    @Nullable
    private final MagicResourceKind magic;

    private GridResourceKind(int id, @Nullable MagicResourceKind magic) {
        this.id = id;
        this.magic = magic;
    }

    /** 物品、流体和已安装模组的魔法资源；顺序固定，编号即下标。 */
    public static GridResourceKind[] values() { return Holder.TYPES.clone(); }

    public static int count() { return Holder.TYPES.length; }

    public static GridResourceKind item() { return ITEM; }

    public static GridResourceKind fluid() { return FLUID; }

    public static GridResourceKind fromId(int id) {
        GridResourceKind[] types = Holder.TYPES;
        if (id < 0 || id >= types.length) throw new IllegalArgumentException("无效资源类别");
        return types[id];
    }

    /** 该魔法资源在当前安装组合下是否有终端类别。 */
    public static boolean magicInstalled(MagicResourceKind kind) {
        return ofMagic(kind) != null;
    }

    /** 魔法资源对应的类别；未安装该模组时返回 null。 */
    @Nullable
    public static GridResourceKind ofMagic(MagicResourceKind kind) {
        for (GridResourceKind type : Holder.TYPES) {
            if (type.magic == kind) return type;
        }
        return null;
    }

    public int id() { return id; }

    public boolean isItem() { return this == ITEM; }

    public boolean isFluid() { return this == FLUID; }

    public boolean isMagic() { return magic != null; }

    /** 魔法资源的稳定身份；物品和流体返回 null。 */
    @Nullable
    public MagicResourceKind magicKind() { return magic; }

    /**
     * 显示模板的数量；模板始终是单个单位，数量变化走条目增量。
     */
    public int amount(Object stack) {
        if (this == ITEM) return ((ItemStack) stack).getCount();
        if (this == FLUID) return ((FluidStack) stack).getAmount();
        if (magic != null) return ((MagicResourceStack) stack).getAmount();
        throw new IllegalArgumentException("未知资源类别");
    }

    /** 复制成数量为 1 的显示模板。 */
    public Object copyTemplate(Object stack) {
        if (this == ITEM && stack instanceof ItemStack item) return item.copyWithCount(1);
        if (this == FLUID && stack instanceof FluidStack fluid) {
            FluidStack copy = fluid.copy();
            copy.setAmount(1);
            return copy;
        }
        if (magic != null && stack instanceof MagicResourceStack resource && resource.kind() == magic) {
            MagicResourceStack copy = resource.copy();
            copy.setAmount(1);
            return copy;
        }
        throw new IllegalArgumentException("资源与类别不一致");
    }

    /** 客户端排序和过滤用的分类序号，物品和流体在最前。 */
    public int sortGroup() { return id; }

    private static List<MagicResourceKind> installedMagic() {
        List<MagicResourceKind> installed = new ArrayList<>(MagicResourceKind.values().length);
        for (MagicResourceKind kind : MagicResourceKind.values()) {
            if (kind.available()) installed.add(kind);
        }
        return installed;
    }

    /** 延迟到第一次使用时才读取模组列表，避免在模组构造阶段冻结错误的安装组合。 */
    private static final class Holder {
        private static final GridResourceKind[] TYPES = build();

        private static GridResourceKind[] build() {
            List<MagicResourceKind> installed = installedMagic();
            GridResourceKind[] types = new GridResourceKind[FIRST_MAGIC_ID + installed.size()];
            types[ITEM_ID] = ITEM;
            types[FLUID_ID] = FLUID;
            for (int index = 0; index < installed.size(); index++) {
                types[FIRST_MAGIC_ID + index] = new GridResourceKind(FIRST_MAGIC_ID + index, installed.get(index));
            }
            return types;
        }
    }
}
