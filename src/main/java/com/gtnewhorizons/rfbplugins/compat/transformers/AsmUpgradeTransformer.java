package com.gtnewhorizons.rfbplugins.compat.transformers;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.asm.UpgradedTreeNodes;
import com.gtnewhorizons.retrofuturabootstrap.asm.UpgradedVisitors;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
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
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * For most coremods it's perfectly safe to upgrade the ASM opcode version to latest, as they only modify/scan a small subset of class data.
 * This allows those transformers to work on newer classes.
 */
public class AsmUpgradeTransformer implements RfbClassTransformer {
    private static final byte[] quickScan = "org/objectweb/asm".getBytes(StandardCharsets.UTF_8);

    private final Map<String, String> upgradeMap = new HashMap<>();

    public AsmUpgradeTransformer() {
        for (Class<?> visitor : UpgradedVisitors.ALL_VISITORS) {
            upgradeMap.put(Type.getInternalName(visitor.getSuperclass()), Type.getInternalName(visitor));
        }
        for (Class<?> visitor : UpgradedTreeNodes.ALL_NODES) {
            upgradeMap.put(Type.getInternalName(visitor.getSuperclass()), Type.getInternalName(visitor));
        }
    }

    @Pattern("[a-z0-9-]+")
    @Override
    public @NotNull String id() {
        return "asm-upgrader";
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

        final ClassHeaderMetadata metadata = classNode.getOriginalMetadata();
        if (metadata == null) {
            return false;
        }
        return metadata.hasSubstring(quickScan);
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

        if (node.superName != null) {
            final String superclass = upgradeMap.get(node.superName);
            if (superclass != null) {
                node.superName = superclass;
                transformed = true;
            }
        }

        for (MethodNode method : node.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode rawInsn : method.instructions) {
                if (rawInsn.getType() == AbstractInsnNode.TYPE_INSN && rawInsn.getOpcode() == Opcodes.NEW) {
                    final TypeInsnNode insn = (TypeInsnNode) rawInsn;
                    final String upgraded = upgradeMap.get(insn.desc);
                    if (upgraded != null) {
                        insn.desc = upgraded;
                        transformed = true;
                    }
                } else if (rawInsn.getType() == AbstractInsnNode.METHOD_INSN) {
                    final MethodInsnNode insn = (MethodInsnNode) rawInsn;
                    if (insn.name.equals("<init>")) {
                        final String upgraded = upgradeMap.get(insn.owner);
                        if (upgraded != null) {
                            insn.owner = upgraded;
                            transformed = true;
                        }
                    }
                }
            }
        }

        return transformed;
    }
}
