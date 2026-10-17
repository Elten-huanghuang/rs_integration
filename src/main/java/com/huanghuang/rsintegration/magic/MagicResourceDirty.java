package com.huanghuang.rsintegration.magic;

/**
 * 归墟盘魔法资源库存的全局变更版本号。
 *
 * <p>魔法资源不在 RS 的存储缓存里，没有 {@code IStorageCacheListener} 可订阅，所以
 * 「谁改了盘」这件事必须由写入方自己声明。物品和流体只要有人动了缓存就会广播，
 * 魔力、魔源、灵魂却是散落在各处的直接磁盘写入，靠终端轮询兜底必然滞后。</p>
 *
 * <p>所有会改变魔法资源库存的写入路径都在提交后调用 {@link #bump()}：盘内插入
 * （{@link MagicDiskStore}）、任务预留的提取与回滚（{@link MagicResourceReservations}）。
 * 读取方（混合终端的魔法通道）记住上次见过的版本号，发现变化就立即重新查询并推送，
 * 不引入任何原生类型依赖，也不会让刷新逻辑与某一种资源耦合。</p>
 *
 * <p>只在服务器主线程读写，所以用普通的 {@code volatile long} 即可；版本号回绕的概率
 * 低到可以忽略（每次写入加一，要写满 long 才回绕）。</p>
 */
public final class MagicResourceDirty {
    private static volatile long version;

    private MagicResourceDirty() {}

    /** 当前版本号；与上次记录的值不同即表示库存变过。 */
    public static long version() {
        return version;
    }

    /** 声明魔法资源库存已改变；所有写入路径在真正提交后调用。 */
    public static void bump() {
        version++;
    }
}
