package com.huanghuang.rsintegration.unifiedgrid;

/** Mixin 接口不引用客户端类，专用服务器也能加载。 */
public interface UnifiedGridMenuAccess {
    UnifiedGridSession rsi$unifiedSession();
}
