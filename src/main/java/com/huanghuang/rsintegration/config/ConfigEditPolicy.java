package com.huanghuang.rsintegration.config;

import java.util.Set;

/** 客户端偏好可本地编辑，远程服务器控制的参数只能查看。 */
public final class ConfigEditPolicy {
    private ConfigEditPolicy() {}

    public static boolean canEdit(String fileId, boolean loaded, boolean remoteServer, boolean singleplayer) {
        return loaded && Set.of("client", "common", "server", "storage").contains(fileId) && (fileId.equals("client")
                || (!remoteServer && (!fileId.equals("server") || singleplayer)));
    }
}
