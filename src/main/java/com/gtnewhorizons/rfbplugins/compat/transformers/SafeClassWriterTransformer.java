package com.gtnewhorizons.rfbplugins.compat.transformers;

import com.gtnewhorizons.retrofuturabootstrap.SafeAsmClassWriter;
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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Transforms all construction and extending of asm's {@link org.objectweb.asm.ClassWriter} to our {@link com.gtnewhorizons.retrofuturabootstrap.SafeAsmClassWriter}
 */
public class SafeClassWriterTransformer implements RfbClassTransformer {
    /** Attribute to set to "true" on a JAR to skip class transforms from this transformer entirely */
    public static final Attributes.Name MANIFEST_SAFE_ATTRIBUTE = new Attributes.Name("Has-Safe-ClassWriters");

    @Pattern("[a-z0-9-]+")
    @Override
    public @NotNull String id() {
        return "safe-class-writer";
    }

    final String CLASS_WRITER_NAME = ClassWriter.class.getName().replace('.', '/');
    final byte[] CLASS_WRITER_BYTES = CLASS_WRITER_NAME.getBytes(StandardCharsets.UTF_8);
    final String SAFE_WRITER_NAME = SafeAsmClassWriter.class.getName().replace('.', '/');

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

        return ClassHeaderMetadata.hasSubstring(classNode.getOriginalBytes(), CLASS_WRITER_BYTES);
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
        if (node.superName.equals(CLASS_WRITER_NAME)) {
            node.superName = SAFE_WRITER_NAME;
        }
        if (node.methods == null) {
            return;
        }
        for (MethodNode method : node.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode rawInsn : method.instructions) {
                if (rawInsn.getType() == AbstractInsnNode.TYPE_INSN) {
                    final TypeInsnNode insn = (TypeInsnNode) rawInsn;
                    if (insn.desc.equals(CLASS_WRITER_NAME)) {
                        insn.desc = SAFE_WRITER_NAME;
                    }
                } else if (rawInsn.getType() == AbstractInsnNode.METHOD_INSN) {
                    final MethodInsnNode insn = (MethodInsnNode) rawInsn;
                    if (insn.owner.equals(CLASS_WRITER_NAME) && insn.name.equals("<init>")) {
                        insn.owner = SAFE_WRITER_NAME;
                    }
                }
            }
        }
    }
}
