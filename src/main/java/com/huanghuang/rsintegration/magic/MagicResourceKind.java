package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;

/**
 * 归墟盘和混合终端里可存储的魔法资源身份。
 *
 * <p>三种资源各自独立计量，默认按原生单位 1:1 转移，不引入互换比例。每种资源
 * 有自己的单盘容量上界；网络容量按已挂载的归墟盘累加，并在原生 int 范围处饱和。</p>
 */
public enum MagicResourceKind {
    MANA("botania", "mana", "types.rs_integration.mana.name", Integer.MAX_VALUE),
    SOURCE("ars_nouveau", "source", "types.rs_integration.source.name", Integer.MAX_VALUE),
    SOUL("goety", "soul", "types.rs_integration.goety_soul.name", Integer.MAX_VALUE);

    private final String namespace;
    private final String path;
    private final String translationKey;
    private final int capacity;

    MagicResourceKind(String namespace, String path, String translationKey, int capacity) {
        this.namespace = namespace;
        this.path = path;
        this.translationKey = translationKey;
        this.capacity = capacity;
    }

    /** 资源所属模组；也是 BD 资源 Key 的命名空间。 */
    public String namespace() {
        return namespace;
    }

    public String path() {
        return path;
    }

    /** 稳定身份，例如 {@code botania:mana}。 */
    public String id() {
        return namespace + ":" + path;
    }

    /** 翻译键；客户端外（如日志、网络包）只允许传键，不允许就地解析成文本。 */
    public String translationKey() {
        return translationKey;
    }

    public Component displayName() {
        return Component.translatable(translationKey);
    }

    /** 统一盘单条目数量上界。 */
    public int capacity() {
        return capacity;
    }

    /**
     * 是否允许 RSI 递归合成直接消耗网络库存。
     *
     * <p>Mana 和 Source 已有原生网络或 Beyond Dimensions 原生 Key，RSI 不再维护
     * 第二套递归供能账；统一盘中的这两种资源只保留手动充放和展示。</p>
     */
    public boolean supportsRsiRecursiveCrafting() {
        return this == MANA || this == SOUL;
    }

    /** 该资源所属模组是否已安装。 */
    public boolean installed() {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(namespace);
    }

    /**
     * 该资源在当前安装组合和配置下是否可用。
     *
     * <p>配置尚未加载时按启用处理，避免模组构造阶段读到 null 把资源当成已关闭。</p>
     */
    public boolean available() {
        if (!installed()) return false;
        ForgeConfigSpec.BooleanValue flag = switch (this) {
            case MANA -> RSIntegrationConfig.ENABLE_BOTANIA;
            case SOURCE -> RSIntegrationConfig.ENABLE_ARS_NOUVEAU;
            case SOUL -> RSIntegrationConfig.ENABLE_GOETY;
        };
        return flag == null || flag.get();
    }

    /** 只保留当前安装状态下真正可用的资源。 */
    public static MagicResourceKind[] installed(boolean botania, boolean arsNouveau, boolean goety) {
        java.util.List<MagicResourceKind> result = new java.util.ArrayList<>(3);
        if (botania) result.add(MANA);
        if (arsNouveau) result.add(SOURCE);
        if (goety) result.add(SOUL);
        return result.toArray(new MagicResourceKind[0]);
    }

    @Nullable
    public static MagicResourceKind byNamespace(String namespace) {
        for (MagicResourceKind kind : values()) {
            if (kind.namespace.equals(namespace)) return kind;
        }
        return null;
    }
}
