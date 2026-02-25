package com.gtnewhorizons.rfbplugins.compat.transformers;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.asm.UnsafeReflectionRedirector;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.Attributes;
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
 * Replaces the broken "remove final from Field.modifiers" approach with a redirection to our Unsafe-based method.
 * In the future this will be migrated to a JNI shim, as Unsafe is going to be removed eventually.
 */
public class UnsafeReflectionTransformer implements RfbClassTransformer {
    /** Attribute to set to "true" on a JAR to skip class transforms from this transformer entirely */
    public static final Attributes.Name MANIFEST_SAFE_ATTRIBUTE = new Attributes.Name("Has-Safe-Reflection");

    @Pattern("[a-z0-9-]+")
    @Override
    public @NotNull String id() {
        return "unsafe-reflection";
    }

    final String CLASS_NAME = Type.getInternalName(Class.class);
    final String FIELD_NAME = Type.getInternalName(Field.class);
    final String REDIRECTION_NAME = Type.getInternalName(UnsafeReflectionRedirector.class);
    final String CLASS_GET_DECLARED_FIELD_STRING_DESC = "(Ljava/lang/String;)Ljava/lang/reflect/Field;";
    final String CLASS_GET_DECLARED_FIELD_EMPTY_DESC = "()[Ljava/lang/reflect/Field;";

    // Redirect set methods with a type that can be coerced to int, and get methods with types int can be coerced to
    final Set<String> REDIRECT_FIELD_METHODS = new HashSet<>(Arrays.asList(
            "setInt(Ljava/lang/Object;I)V",
            "setByte(Ljava/lang/Object;B)V",
            "setShort(Ljava/lang/Object;S)V",
            "setChar(Ljava/lang/Object;C)V",
            "getInt(Ljava/lang/Object;)I",
            "getLong(Ljava/lang/Object;)J",
            "getFloat(Ljava/lang/Object;)F",
            "getDouble(Ljava/lang/Object;)D",
            "set(Ljava/lang/Object;Ljava/lang/Object;)V",
            "get(Ljava/lang/Object;)Ljava/lang/Object"));

    final ClassHeaderMetadata.NeedleIndex scanIndex = new ClassHeaderMetadata.NeedleIndex(new byte[][] {
                CLASS_GET_DECLARED_FIELD_STRING_DESC.getBytes(StandardCharsets.UTF_8),
                CLASS_GET_DECLARED_FIELD_EMPTY_DESC.getBytes(StandardCharsets.UTF_8),
                FIELD_NAME.getBytes(StandardCharsets.UTF_8)
            })
            .exactMatch();

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

        for (MethodNode method : node.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode rawInsn : method.instructions) {
                if (rawInsn.getType() == AbstractInsnNode.METHOD_INSN) {
                    final MethodInsnNode insn = (MethodInsnNode) rawInsn;
                    if (insn.owner.equals(CLASS_NAME)
                            && insn.name.equals("getDeclaredField")
                            && insn.desc.equals(CLASS_GET_DECLARED_FIELD_STRING_DESC)) {
                        // getDeclaredField(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;
                        insn.setOpcode(Opcodes.INVOKESTATIC);
                        insn.owner = REDIRECTION_NAME;
                        insn.desc = "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;";
                        transformed = true;
                    } else if (insn.owner.equals(CLASS_NAME)
                            && insn.name.equals("getDeclaredFields")
                            && insn.desc.equals(CLASS_GET_DECLARED_FIELD_EMPTY_DESC)) {
                        // getDeclaredFields(Ljava/lang/Class;)[Ljava/lang/reflect/Field;
                        insn.setOpcode(Opcodes.INVOKESTATIC);
                        insn.owner = REDIRECTION_NAME;
                        insn.desc = "(Ljava/lang/Class;)[Ljava/lang/reflect/Field;";
                        transformed = true;
                    } else if (insn.owner.equals(FIELD_NAME)
                            && REDIRECT_FIELD_METHODS.contains(insn.name + insn.desc)) {
                        // add a Field argument at the start
                        String newDesc = "(Ljava/lang/reflect/Field;" + insn.desc.substring(1);
                        insn.setOpcode(Opcodes.INVOKESTATIC);
                        insn.owner = REDIRECTION_NAME;
                        insn.desc = newDesc;
                        transformed = true;
                    }
                }
            }
        }

        return transformed;
    }
}
