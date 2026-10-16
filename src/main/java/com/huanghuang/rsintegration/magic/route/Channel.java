package com.huanghuang.rsintegration.magic.route;

/**
 * 一条资源路径的类型。
 *
 * <p>取代原来裸 {@code int} 表达「存量」的做法：同一个数字在不同路径下含义不同，
 * 必须显式声明是「已挂载归墟盘之和」还是「某个后端网络内的库存」。调用方据此
 * 知道自己读到的量到底覆盖了什么范围。</p>
 */
public enum Channel {
    /** 没有可用路径。 */
    NONE,
    /** 所有已挂载归墟盘之和；与服务端当前挂载状态无关的单个网络。 */
    DISK_MOUNTED,
    /** 指定后端网络内部的库存。 */
    BACKEND_LOCAL
}
