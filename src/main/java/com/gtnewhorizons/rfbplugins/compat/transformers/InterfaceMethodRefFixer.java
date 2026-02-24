package com.gtnewhorizons.rfbplugins.compat.transformers;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.FastClassAccessor;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.rfbplugins.compat.ModernJavaCompatibilityPlugin;
import java.util.HashMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.intellij.lang.annotations.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Fixes a bug of ASM 5.0 used in the java 8 era of modding, leading to the following exception at runtime:
 * <pre>
 * cpw.mods.fml.common.LoaderException: java.lang.IncompatibleClassChangeError: Inconsistent constant pool data in
 * classfile for class com/gtnewhorizon/structurelib/alignment/IAlignmentLimits. Method 'boolean
 * lambda$static$0(...)' at index 77 is CONSTANT_MethodRef and should be CONSTANT_InterfaceMethodRef.
 * </pre>
 * <p>
 * See e.g. <a href="https://bugs.openjdk.org/browse/JDK-8027227">Bug JDK-8027227</a>,
 * <a href="https://bugs.openjdk.org/browse/JDK-8145148">Bug JDK-8145148</a>
 */
public class InterfaceMethodRefFixer implements RfbClassTransformer {
    /** Attribute to set to "true" on a JAR to skip class transforms from this transformer entirely */
    public static final Attributes.Name MANIFEST_SAFE_ATTRIBUTE = new Attributes.Name("Has-Safe-InterfaceMethodRefs");

    @Pattern("[a-z0-9-]+")
    @Override
    public @NotNull String id() {
        return "interface-method-ref-fixer";
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

        final ClassHeaderMetadata metadata = classNode.getOriginalMetadata();
        if (metadata == null) {
            return false;
        }

        // Assume classes for java 9+ were not plagued by the asm 5.0 interfacemethodref bug.
        if (metadata.majorVersion >= Opcodes.V9) {
            return false;
        }
        if (manifest != null && "true".equals(manifest.getMainAttributes().getValue(MANIFEST_SAFE_ATTRIBUTE))) {
            return false;
        }

        return metadata.hasInvokeDynamicEntry();
    }

    @Override
    public boolean transformClass(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode) {
        final ClassNode node = classNode.getNode();
        if (node == null) {
            return false;
        }

        final boolean classIsInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
        final String internalClassName = node.name;
        boolean transformed = false;

        // classLoader.findClassMetadata() reads the class bytes and constructs ClassHeaderMetadata with expensive
        // <init> when the class hasn't been loaded by this loader. Furthermore, this method doesn't load the class,
        // so every call it reads the bytes again, luckily from cache, but creates expensive ClassHeaderMetadata again.
        // Preventing ClassHeaderMetadata re-instantiation for the same class even per class file prevents nearly
        // 10,000 instantiations during the full pack load
        final HashMap<String, Boolean> ownerInterfaceCache = new HashMap<>();

        for (MethodNode method : node.methods) {
            if (method.instructions == null) {
                continue;
            }

            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof InvokeDynamicInsnNode) {
                    transformed |= fixInvokeDynamicInsn(
                            classLoader,
                            internalClassName,
                            classIsInterface,
                            ownerInterfaceCache,
                            (InvokeDynamicInsnNode) insn);
                }
            }
        }

        return transformed;
    }

    private boolean fixInvokeDynamicInsn(
            ExtensibleClassLoader classLoader,
            String internalClassName,
            boolean classIsInterface,
            HashMap<String, Boolean> ownerInterfaceCache,
            InvokeDynamicInsnNode insn) {
        boolean transformed = false;
        final Handle fixedBootstrapMethod =
                fixHandleIfNeeded(classLoader, internalClassName, classIsInterface, ownerInterfaceCache, insn.bsm);

        if (fixedBootstrapMethod != null) {
            insn.bsm = fixedBootstrapMethod;
            transformed = true;
        }

        if (insn.bsmArgs != null) {
            for (int i = 0; i < insn.bsmArgs.length; i++) {
                final Object arg = insn.bsmArgs[i];
                if (!(arg instanceof Handle)) {
                    continue;
                }

                final Handle fixedBootstrapArg = fixHandleIfNeeded(
                        classLoader, internalClassName, classIsInterface, ownerInterfaceCache, (Handle) arg);

                if (fixedBootstrapArg != null) {
                    insn.bsmArgs[i] = fixedBootstrapArg;
                    transformed = true;
                }
            }
        }

        return transformed;
    }

    @Nullable
    private Handle fixHandleIfNeeded(
            ExtensibleClassLoader classLoader,
            String internalClassName,
            boolean classIsInterface,
            HashMap<String, Boolean> ownerInterfaceCache,
            Handle handle) {
        if (handle.isInterface()) {
            return null;
        }

        // Per JVMS §4.4.8:
        //
        // - REF_invokeInterface must reference CONSTANT_InterfaceMethodref.
        // - REF_invokeStatic and REF_invokeSpecial:
        //   * must reference CONSTANT_Methodref if the owner is a class;
        //   * must reference CONSTANT_InterfaceMethodref if the owner is an interface.
        //   (but when the class file version is < 52, only CONSTANT_Methodref can be referenced)
        // - REF_invokeVirtual, REF_newInvokeSpecial must reference CONSTANT_Methodref.
        //
        // As we're fixing incorrect references to CONSTANT_Methodref when they should be CONSTANT_InterfaceMethodref,
        // we can ignore non-invoke handles and invoke handles which cannot reference CONSTANT_InterfaceMethodref
        int tag = handle.getTag();
        if (tag != Opcodes.H_INVOKEINTERFACE && tag != Opcodes.H_INVOKESTATIC && tag != Opcodes.H_INVOKESPECIAL) {
            return null;
        }

        // We know that LambdaMetafactory is not an interface, and it's a common bootstrap method owner,
        // so we can skip a fair bit of FastClassAccessor.ofLoaded(cachedClass) class constructions
        final String owner = handle.getOwner();
        if (owner.equals("java/lang/invoke/LambdaMetafactory")) {
            return null;
        }

        final boolean shouldBeInterface = (classIsInterface && owner.equals(internalClassName))
                || ownerIsInterface(classLoader, ownerInterfaceCache, owner);

        if (!shouldBeInterface) {
            return null;
        }

        ModernJavaCompatibilityPlugin.log.debug(
                "Fixed a broken InterfaceMethodRef {} -> {}#{} ({})",
                internalClassName,
                owner,
                handle.getName(),
                handle.getDesc());

        return new Handle(handle.getTag(), owner, handle.getName(), handle.getDesc(), true);
    }

    private static boolean ownerIsInterface(
            ExtensibleClassLoader classLoader, HashMap<String, Boolean> ownerInterfaceCache, String ownerInternalName) {
        final Boolean cached = ownerInterfaceCache.get(ownerInternalName);
        if (cached != null) {
            return cached;
        }

        final FastClassAccessor metadata = classLoader.findClassMetadata(ownerInternalName.replace('/', '.'));
        final boolean isInterface = metadata != null && metadata.isInterface();

        ownerInterfaceCache.put(ownerInternalName, isInterface);
        return isInterface;
    }
}
