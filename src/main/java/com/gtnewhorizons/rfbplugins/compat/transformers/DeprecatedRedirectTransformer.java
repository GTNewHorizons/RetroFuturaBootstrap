package com.gtnewhorizons.rfbplugins.compat.transformers;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.asm.DummyCompiler;
import java.nio.charset.StandardCharsets;
import java.util.jar.Manifest;
import org.intellij.lang.annotations.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Redirects various deprecated Java classes/methods to dummy implementations.
 */
public class DeprecatedRedirectTransformer implements RfbClassTransformer {
    @Pattern("[a-z0-9-]+")
    @Override
    public @NotNull String id() {
        return "undeprecator";
    }

    final String COMPILER_NAME = "java/lang/Compiler";
    final byte[] COMPILER_NAME_BYTES = COMPILER_NAME.getBytes(StandardCharsets.UTF_8);
    final String COMPILER_REDIRECTION_NAME = Type.getInternalName(DummyCompiler.class);

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
        final int classVersion;
        if (classNode.getOriginalMetadata() != null) {
            classVersion = classNode.getOriginalMetadata().majorVersion;
        } else {
            classVersion = 8;
        }

        return (classVersion < Opcodes.V21
                && ClassHeaderMetadata.hasSubstring(classNode.getOriginalBytes(), COMPILER_NAME_BYTES));
    }

    @Override
    public void transformClass(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull RfbClassTransformer.Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode) {
        final ClassNode node = classNode.getNode();
        if (node == null || node.methods == null) {
            return;
        }
        for (MethodNode method : node.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode rawInsn : method.instructions) {
                if (rawInsn.getType() == AbstractInsnNode.METHOD_INSN) {
                    final MethodInsnNode insn = (MethodInsnNode) rawInsn;
                    if (insn.owner.equals(COMPILER_NAME)) {
                        insn.owner = COMPILER_REDIRECTION_NAME;
                    }
                }
            }
        }
    }
}
