package com.huanghuang.rsintegration.compat.emi;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmiPluginBytecodeTest {
    private static final Path MAIN_CLASSES = Path.of("build", "classes", "java", "main");

    @Test
    void pluginUsesTheEmiEntrypointContract() throws IOException {
        byte[] bytes = readClass("RSEmiPlugin");
        AtomicBoolean hasEntrypoint = new AtomicBoolean();
        AtomicBoolean implementsPlugin = new AtomicBoolean();

        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                implementsPlugin.set(Arrays.asList(interfaces).contains("dev/emi/emi/api/EmiPlugin"));
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if ("Ldev/emi/emi/api/EmiEntrypoint;".equals(descriptor)) {
                    hasEntrypoint.set(true);
                }
                return super.visitAnnotation(descriptor, visible);
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertTrue(implementsPlugin.get(), "EMI must discover a real EmiPlugin implementation");
        assertTrue(hasEntrypoint.get(), "Forge EMI discovery requires @EmiEntrypoint");
    }

    @Test
    void integrationDoesNotHardLinkJemiInternals() throws IOException {
        for (String className : new String[]{
                "RSEmiPlugin",
                "EmiCraftButtonDecorator",
                "EmiCraftButtonResolver",
                "EmiCraftButtonWidget",
                "EmiCraftButtonSpec"
        }) {
            String constants = new String(readClass(className), StandardCharsets.ISO_8859_1);
            assertFalse(constants.contains("dev/emi/emi/jemi/"),
                    className + " must access JEMI wrappers reflectively");
            assertFalse(constants.contains("dev/emi/emi/screen/"),
                    className + " must not depend on EMI screen internals");
        }
    }

    @Test
    void sharedRecipeBrowserBridgeDoesNotHardLinkOptionalEmi() throws IOException {
        Path path = MAIN_CLASSES.resolve(Path.of(
                "com", "huanghuang", "rsintegration", "client", "RecipeBrowserBridge.class"));
        assertTrue(Files.isRegularFile(path), () -> "missing compiled class " + path.toAbsolutePath());
        String constants = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
        assertFalse(constants.contains("dev/emi/emi/"),
                "optional EMI types must remain isolated in EmiClientBridge");
    }

    @Test
    void emiDependencyIsOptionalAndVersionBounded() throws IOException {
        String modsToml = Files.readString(Path.of("src", "main", "resources", "META-INF", "mods.toml"));
        int dependency = modsToml.indexOf("modId = \"emi\"");
        assertTrue(dependency >= 0);
        String block = modsToml.substring(dependency, Math.min(modsToml.length(), dependency + 220));
        assertTrue(block.contains("mandatory = false"));
        assertTrue(block.contains("versionRange = \"[1.1,1.2)\""));
        assertTrue(block.contains("side = \"CLIENT\""));
    }

    private static byte[] readClass(String simpleName) throws IOException {
        Path path = MAIN_CLASSES.resolve(Path.of(
                "com", "huanghuang", "rsintegration", "compat", "emi", simpleName + ".class"));
        assertTrue(Files.isRegularFile(path), () -> "missing compiled class " + path.toAbsolutePath());
        return Files.readAllBytes(path);
    }
}
