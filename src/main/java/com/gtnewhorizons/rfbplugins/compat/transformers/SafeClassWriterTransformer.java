package com.gtnewhorizons.rfbplugins.compat.transformers;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.asm.SafeAsmClassWriter;
import java.nio.charset.StandardCharsets;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.intellij.lang.annotations.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Transforms all construction and extending of asm's {@link org.objectweb.asm.ClassWriter} to our {@link SafeAsmClassWriter}
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
    final String SAFE_WRITER_NAME = SafeAsmClassWriter.class.getName().replace('.', '/');
    final ClassHeaderMetadata.NeedleIndex scanIndex =
            new ClassHeaderMetadata.NeedleIndex(CLASS_WRITER_NAME.getBytes(StandardCharsets.UTF_8));

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

        final ClassHeaderMetadata metadata = classNode.getOriginalMetadata();
        if (metadata == null) {
            return false;
        }
        return metadata.hasSubstrings(scanIndex);
    }

    @Override
    public boolean transformClass(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull RfbClassTransformer.Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode) {
        final ClassNode node = classNode.getNode();
        boolean transformed = false;
        if (node == null) {
            return false;
        }

        if (node.superName.equals(CLASS_WRITER_NAME)) {
            node.superName = SAFE_WRITER_NAME;
            transformed = true;
        }

        for (MethodNode method : node.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode rawInsn : method.instructions) {
                if (rawInsn.getType() == AbstractInsnNode.TYPE_INSN && rawInsn.getOpcode() == Opcodes.NEW) {
                    final TypeInsnNode insn = (TypeInsnNode) rawInsn;
                    if (insn.desc.equals(CLASS_WRITER_NAME)) {
                        insn.desc = SAFE_WRITER_NAME;
                        transformed = true;
                    }
                } else if (rawInsn.getType() == AbstractInsnNode.METHOD_INSN) {
                    final MethodInsnNode insn = (MethodInsnNode) rawInsn;
                    if (insn.owner.equals(CLASS_WRITER_NAME) && insn.name.equals("<init>")) {
                        insn.owner = SAFE_WRITER_NAME;
                        transformed = true;
                    }
                }
            }
        }

        return transformed;
    }
}
