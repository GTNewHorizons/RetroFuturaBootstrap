package com.gtnewhorizons.rfbplugins.compat.transformers;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import java.nio.charset.StandardCharsets;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.intellij.lang.annotations.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * ASM 5 accepted "a/b/Klass" in Type.getType, newer asm correctly rejects it as invalid - it should be "La/b/Klass;".
 */
public class AsmTypeTransformer implements RfbClassTransformer {
    /** Attribute to set to "true" on a JAR to skip class transforms from this transformer entirely */
    public static final Attributes.Name MANIFEST_SAFE_ATTRIBUTE = new Attributes.Name("Has-Safe-AsmGetTypeUsage");

    private static final byte[] quickScan = "org/objectweb/asm/Type".getBytes(StandardCharsets.UTF_8);

    @Pattern("[a-z0-9-]+")
    @Override
    public @NotNull String id() {
        return "asm-type-fixer";
    }

    @Override
    public boolean shouldTransformClass(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull RfbClassTransformer.Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode) {
        if (!classNode.isPresent()) {
            return false;
        }
        if (manifest != null && "true".equals(manifest.getMainAttributes().getValue(MANIFEST_SAFE_ATTRIBUTE))) {
            return false;
        }
        // Assume classes for java 9+ were tested against a newer asm.
        if (classNode.getOriginalMetadata() != null && classNode.getOriginalMetadata().majorVersion >= Opcodes.V9) {
            return false;
        }
        if (classNode.isOriginal()) {
            final byte[] original = classNode.getOriginalBytes();
            return ClassHeaderMetadata.hasSubstring(original, quickScan);
        }
        return true;
    }

    @Override
    public void transformClass(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull RfbClassTransformer.Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode) {
        final ClassNode node = classNode.getNode();
        if (node == null) {
            return;
        }
        if (node.methods == null) {
            return;
        }
        for (MethodNode method : node.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn.getType() == AbstractInsnNode.METHOD_INSN) {
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    if ("org/objectweb/asm/Type".equals(mi.owner)
                            && ("getType".equals(mi.name) || "getReturnType".equals(mi.name))
                            && "(Ljava/lang/String;)Lorg/objectweb/asm/Type;".equals(mi.desc)) {
                        mi.owner = "com/gtnewhorizons/retrofuturabootstrap/asm/SafeAsmType";
                    }
                }
            }
        }
    }
}
