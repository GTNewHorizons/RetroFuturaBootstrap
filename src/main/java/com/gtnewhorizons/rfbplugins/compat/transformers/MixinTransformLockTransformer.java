package com.gtnewhorizons.rfbplugins.compat.transformers;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Manifest;
import org.intellij.lang.annotations.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Makes Mixin run class transformers and change its mixin config state only while holding LaunchClassLoader's
 * transformer lock, so that it never does either while the class preloader's worker transforms a class:
 * <ul>
 *     <li>{@code MixinServiceLaunchWrapper.applyTransformers} runs the LaunchClassLoader transformers itself when
 *     Mixin reads a class that isn't loaded, possibly outside any class load (e.g. while preparing mixin configs).</li>
 *     <li>{@code MixinProcessor.selectConfigs} and {@code prepareConfigs} change the mixin configs that transforming a
 *     class reads; GTNHMixins calls them directly (by reflection) to prepare late mixins, outside any class load.</li>
 * </ul>
 * Each such method is renamed to {@code rfb$locked$<name>} and replaced by a method with the original name and
 * descriptor that calls {@code LaunchClassLoader.rfb$invokeLocked}, which takes the lock if needed and calls the
 * original by reflection.
 */
public class MixinTransformLockTransformer implements RfbClassTransformer {
    private static final class Target {
        final String className;
        final String method;
        final String desc;

        Target(String className, String method, String desc) {
            this.className = className;
            this.method = method;
            this.desc = desc;
        }
    }

    private static final List<Target> TARGETS = Arrays.asList(
            new Target(
                    "org.spongepowered.asm.service.mojang.MixinServiceLaunchWrapper",
                    "applyTransformers",
                    "(Ljava/lang/String;Ljava/lang/String;[BLorg/spongepowered/asm/util/perf/Profiler;)[B"),
            new Target(
                    "org.spongepowered.asm.mixin.transformer.MixinProcessor",
                    "selectConfigs",
                    "(Lorg/spongepowered/asm/mixin/MixinEnvironment;)V"),
            new Target(
                    "org.spongepowered.asm.mixin.transformer.MixinProcessor",
                    "prepareConfigs",
                    "(Lorg/spongepowered/asm/mixin/MixinEnvironment;Lorg/spongepowered/asm/mixin/transformer/ext/Extensions;)I"));

    public static final String PREFIX = "rfb$locked$";
    private static final String HELPER_OWNER = "net/minecraft/launchwrapper/LaunchClassLoader";
    private static final String HELPER = "rfb$invokeLocked";
    private static final String HELPER_DESC =
            "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;";

    @Pattern("[a-z0-9-]+")
    @Override
    public @NotNull String id() {
        return "mixin-transform-lock";
    }

    @Override
    public boolean shouldTransformClass(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode) {
        if (!classNode.isPresent()) {
            return false;
        }
        for (Target t : TARGETS) {
            if (t.className.equals(className)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean transformClassIfNeeded(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode) {
        final ClassNode node = classNode.getNode();
        if (node == null) {
            return false;
        }
        boolean transformed = false;
        for (Target t : TARGETS) {
            if (!t.className.equals(className)) {
                continue;
            }
            for (MethodNode method : node.methods) {
                if (method.name.equals(t.method)
                        && method.desc.equals(t.desc)
                        && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                    node.methods.add(wrap(method));
                    transformed = true;
                    break;
                }
            }
        }
        return transformed;
    }

    /** Renames the method and returns a replacement that calls it through LaunchClassLoader.rfb$invokeLocked. */
    private static MethodNode wrap(MethodNode original) {
        final String name = original.name;
        original.name = PREFIX + name;
        final MethodNode wrapper = new MethodNode(
                original.access & ~Opcodes.ACC_SYNCHRONIZED,
                name,
                original.desc,
                original.signature,
                original.exceptions == null ? null : original.exceptions.toArray(new String[0]));
        final Type[] args = Type.getArgumentTypes(original.desc);
        final Type ret = Type.getReturnType(original.desc);
        final InsnList code = wrapper.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new LdcInsnNode(original.name));
        code.add(new IntInsnNode(Opcodes.BIPUSH, args.length));
        code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int local = 1;
        for (int i = 0; i < args.length; i++) {
            if (args[i].getSort() == Type.OBJECT || args[i].getSort() == Type.ARRAY) {
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new IntInsnNode(Opcodes.BIPUSH, i));
                code.add(new VarInsnNode(Opcodes.ALOAD, local));
                code.add(new InsnNode(Opcodes.AASTORE));
            } else {
                throw new IllegalArgumentException("primitive parameters aren't supported: " + original.desc);
            }
            local += args[i].getSize();
        }
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER_OWNER, HELPER, HELPER_DESC, false));
        switch (ret.getSort()) {
            case Type.VOID:
                code.add(new InsnNode(Opcodes.POP));
                code.add(new InsnNode(Opcodes.RETURN));
                break;
            case Type.INT:
                code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
                code.add(new InsnNode(Opcodes.IRETURN));
                break;
            case Type.OBJECT:
            case Type.ARRAY:
                code.add(new TypeInsnNode(Opcodes.CHECKCAST, ret.getInternalName()));
                code.add(new InsnNode(Opcodes.ARETURN));
                break;
            default:
                throw new IllegalArgumentException("unsupported return type: " + original.desc);
        }
        wrapper.maxStack = 6;
        wrapper.maxLocals = local;
        return wrapper;
    }
}
