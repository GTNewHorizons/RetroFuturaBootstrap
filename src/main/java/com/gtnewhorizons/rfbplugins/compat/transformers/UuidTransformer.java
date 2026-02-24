package com.gtnewhorizons.rfbplugins.compat.transformers;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.asm.UuidStringConstructor;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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
 * Redirect the {@link UUID#fromString(String)} factory function to an implementation copying Java 8's less strict behaviour.
 */
public class UuidTransformer implements RfbClassTransformer {
    /** Attribute to set to "true" on a JAR to skip class transforms from this transformer entirely */
    public static final Attributes.Name MANIFEST_SAFE_ATTRIBUTE = new Attributes.Name("Has-Safe-UUID");

    @Pattern("[a-z0-9-]+")
    @Override
    public @NotNull String id() {
        return "uuid";
    }

    final String UUID_NAME = Type.getInternalName(UUID.class);
    final byte[] UUID_NAME_BYTES = UUID_NAME.getBytes(StandardCharsets.UTF_8);
    final String REDIRECTION_NAME = Type.getInternalName(UuidStringConstructor.class);

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

        if (metadata.majorVersion >= Opcodes.V9) {
            return false;
        }

        return metadata.hasSubstring(UUID_NAME_BYTES);
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
                    if (insn.owner.equals(UUID_NAME)
                            && insn.name.equals("fromString")
                            && insn.desc.equals("(Ljava/lang/String;)Ljava/util/UUID;")) {
                        insn.owner = REDIRECTION_NAME;
                        transformed = true;
                    }
                }
            }
        }

        return transformed;
    }
}
