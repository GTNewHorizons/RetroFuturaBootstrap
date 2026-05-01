package com.gtnewhorizons.rfbplugins.compat.transformers;

import com.gtnewhorizons.retrofuturabootstrap.SharedConfig;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import org.intellij.lang.annotations.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Redirects various deprecated Java classes/methods to dummy implementations.
 */
public class DeprecatedRedirectTransformer extends Remapper implements RfbClassTransformer {

    public DeprecatedRedirectTransformer() {
        excludedPackages = Stream.concat(Arrays.stream(fromPrefixes), Arrays.stream(toPrefixes))
                .map(s -> s.replace('/', '.'))
                .toArray(String[]::new);
        quickScans = Arrays.stream(fromPrefixes)
                .map(s -> s.getBytes(StandardCharsets.UTF_8))
                .toArray(byte[][]::new);
    }

    @Pattern("[a-z0-9-]+")
    @Override
    public @NotNull String id() {
        return "undeprecator";
    }

    @Override
    public @NotNull String @Nullable [] additionalExclusions() {
        return excludedPackages;
    }

    final String[] fromPrefixes = new String[] {"java/lang/Compiler", "java/lang/SecurityManager"};
    final String[] toPrefixes = new String[] {
        "com/gtnewhorizons/retrofuturabootstrap/asm/DummyCompiler",
        "com/gtnewhorizons/retrofuturabootstrap/SecurityManager"
    };
    final byte[][] quickScans;
    final String[] excludedPackages;

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

        if (classVersion >= Opcodes.V21) {
            return false;
        }

        final byte[] original = classNode.getOriginalBytes();
        if (original == null) {
            return false;
        }
        return ClassHeaderMetadata.hasSubstrings(original, quickScans);
    }

    @Override
    public void transformClass(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull RfbClassTransformer.Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode) {
        final ClassNode inputNode = classNode.getNode();
        if (inputNode == null) {
            return;
        }

        final ClassNode outputNode = new ClassNode();
        final ClassVisitor visitor = new ClassRemapper(outputNode, this);

        try {
            inputNode.accept(visitor);
        } catch (Exception e) {
            SharedConfig.logWarning("Couldn't remap class " + className, e);
            return;
        }

        // Remap SecurityManager getter/setter
        if (outputNode.methods != null) {
            for (final MethodNode mn : outputNode.methods) {
                if (mn.instructions == null) {
                    continue;
                }
                for (final AbstractInsnNode insn : mn.instructions) {
                    if (insn.getOpcode() != Opcodes.INVOKESTATIC) {
                        continue;
                    }
                    final MethodInsnNode minsn = (MethodInsnNode) insn;
                    if (!"java/lang/System".equals(minsn.owner)) {
                        continue;
                    }
                    if ("getSecurityManager".equals(minsn.name) || "setSecurityManager".equals(minsn.name)) {
                        minsn.owner = "com/gtnewhorizons/retrofuturabootstrap/SecurityManager";
                    }
                }
            }
        }

        classNode.setNode(outputNode);
    }

    @Override
    public String map(String typeName) {
        if (typeName == null) {
            return null;
        }
        for (int pfx = 0; pfx < fromPrefixes.length; pfx++) {
            if (typeName.startsWith(fromPrefixes[pfx])) {
                return toPrefixes[pfx] + typeName.substring(fromPrefixes[pfx].length());
            }
        }
        return typeName;
    }
}
