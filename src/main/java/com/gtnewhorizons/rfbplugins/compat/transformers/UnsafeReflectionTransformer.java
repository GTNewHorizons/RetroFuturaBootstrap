package com.gtnewhorizons.rfbplugins.compat.transformers;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.asm.UnsafeReflectionRedirector;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
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
    final byte[] CLASS_NAME_BYTES = CLASS_NAME.getBytes(StandardCharsets.UTF_8);
    final String FIELD_NAME = Type.getInternalName(Field.class);
    final byte[] FIELD_NAME_BYTES = FIELD_NAME.getBytes(StandardCharsets.UTF_8);
    final String REDIRECTION_NAME = Type.getInternalName(UnsafeReflectionRedirector.class);

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

        return ClassHeaderMetadata.hasSubstring(classNode.getOriginalBytes(), CLASS_NAME_BYTES)
                || ClassHeaderMetadata.hasSubstring(classNode.getOriginalBytes(), FIELD_NAME_BYTES);
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
                    if (insn.owner.equals(CLASS_NAME)
                            && insn.name.equals("getDeclaredField")
                            && insn.desc.equals("(Ljava/lang/String;)Ljava/lang/reflect/Field;")) {
                        // getDeclaredField(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;
                        insn.setOpcode(Opcodes.INVOKESTATIC);
                        insn.owner = REDIRECTION_NAME;
                        insn.desc = "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;";
                    } else if (insn.owner.equals(CLASS_NAME)
                            && insn.name.equals("getDeclaredFields")
                            && insn.desc.equals("()[Ljava/lang/reflect/Field;")) {
                        // getDeclaredFields(Ljava/lang/Class;)[Ljava/lang/reflect/Field;
                        insn.setOpcode(Opcodes.INVOKESTATIC);
                        insn.owner = REDIRECTION_NAME;
                        insn.desc = "(Ljava/lang/Class;)[Ljava/lang/reflect/Field;";
                    } else if (insn.owner.equals(FIELD_NAME)
                            && insn.name.equals("setInt")
                            && insn.desc.equals("(Ljava/lang/Object;I)V")) {
                        // setInt(Ljava/lang/reflect/Field;Ljava/lang/Object;I)V
                        insn.setOpcode(Opcodes.INVOKESTATIC);
                        insn.owner = REDIRECTION_NAME;
                        insn.desc = "(Ljava/lang/reflect/Field;Ljava/lang/Object;I)V";
                    } else if (insn.owner.equals(FIELD_NAME)
                            && insn.name.equals("getInt")
                            && insn.desc.equals("(Ljava/lang/Object;)I")) {
                        // getInt(Ljava/lang/reflect/Field;Ljava/lang/Object;)I
                        insn.setOpcode(Opcodes.INVOKESTATIC);
                        insn.owner = REDIRECTION_NAME;
                        insn.desc = "(Ljava/lang/reflect/Field;Ljava/lang/Object;)I";
                    } else if (insn.owner.equals(FIELD_NAME)
                            && insn.name.equals("set")
                            && insn.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)V")) {
                        // set(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)V
                        insn.setOpcode(Opcodes.INVOKESTATIC);
                        insn.owner = REDIRECTION_NAME;
                        insn.desc = "(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)V";
                    }
                }
            }
        }
    }
}
