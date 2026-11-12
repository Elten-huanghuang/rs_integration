package com.huanghuang.rsintegration.mods.rs.monitor;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RsMonitorClientLoadingTest {
    private static final String CLIENT_CLASS =
            "com.huanghuang.rsintegration.mods.rs.monitor.RsMonitorNavigationClient";
    private static final String AUTO_SUBSCRIBER =
            "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;";

    @Test
    void automaticSubscriberDiscoveryDoesNotResolveNativeRsTypesWhenRsIsAbsent() throws IOException {
        byte[] bytecode = clientBytecode();
        ClassNode node = new ClassNode();
        new ClassReader(bytecode).accept(node, ClassReader.SKIP_CODE);
        WithoutRsClassLoader loader = new WithoutRsClassLoader(bytecode);

        // Forge 先读取注解，再对自动订阅类反射全部公开方法；复现报告中的加载顺序。
        assertDoesNotThrow(() -> {
            if (node.visibleAnnotations != null && node.visibleAnnotations.stream()
                    .anyMatch(annotation -> AUTO_SUBSCRIBER.equals(annotation.desc))) {
                loader.loadClass(CLIENT_CLASS).getMethods();
            }
        }, "未安装 RS 时，自动订阅扫描不能触发原生 RS 客户端类型加载");
        assertEquals(0, loader.nativeRsLoadAttempts);
    }

    @Test
    void missingRsFixtureReproducesTheReportedReflectionFailure() throws IOException {
        WithoutRsClassLoader loader = new WithoutRsClassLoader(clientBytecode());

        assertThrows(NoClassDefFoundError.class,
                () -> loader.loadClass(CLIENT_CLASS).getMethods());
        assertTrue(loader.nativeRsLoadAttempts > 0);
    }

    @Test
    void installedRsAllowsExplicitSubscriberInspection() {
        assertDoesNotThrow(() -> Class.forName(CLIENT_CLASS, false, getClass().getClassLoader())
                .getMethods());
    }

    private static byte[] clientBytecode() throws IOException {
        String resource = "/" + CLIENT_CLASS.replace('.', '/') + ".class";
        try (InputStream input = RsMonitorClientLoadingTest.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("缺少客户端类资源：" + resource);
            return input.readAllBytes();
        }
    }

    private static final class WithoutRsClassLoader extends ClassLoader {
        private final byte[] bytecode;
        private int nativeRsLoadAttempts;

        private WithoutRsClassLoader(byte[] bytecode) {
            super(RsMonitorClientLoadingTest.class.getClassLoader());
            this.bytecode = bytecode;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.refinedmods.refinedstorage.")) {
                nativeRsLoadAttempts++;
                throw new ClassNotFoundException("模拟未安装 RS：" + name);
            }
            if (!CLIENT_CLASS.equals(name)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = defineClass(name, bytecode, 0, bytecode.length);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }
}
