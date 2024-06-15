package com.gtnewhorizons.retrofuturabootstrap.asm;

import com.gtnewhorizons.retrofuturabootstrap.Main;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.FastClassAccessor;
import java.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * Simple ClassWriter that uses LaunchClassLoader for common superclass finding instead of the system loader.
 */
public class SafeAsmClassWriter extends ClassWriter {
    public static final ThreadLocal<Integer> forcedFlags = ThreadLocal.withInitial(() -> 0);
    public static final ThreadLocal<byte[]> forcedOriginalClass = new ThreadLocal<>();
    private final Map<String, InheritanceNode> inheritanceCache = new HashMap<>();

    public SafeAsmClassWriter(int flags) {
        super(flags | forcedFlags.get());
        if (forcedOriginalClass.get() != null) {
            // provide some metadata about the class in case it doesn't get initialized early in broken asm patchers.
            ClassHeaderMetadata chm = new ClassHeaderMetadata(forcedOriginalClass.get());
            super.visit(chm.majorVersion, chm.accessFlags, chm.binaryThisName, null, chm.binarySuperName, null);
        }
    }

    public SafeAsmClassWriter(ClassReader classReader, int flags) {
        super(classReader, flags | forcedFlags.get());
    }

    @Override
    protected ClassLoader getClassLoader() {
        final ExtensibleClassLoader launchLoader = Main.launchLoader;
        return launchLoader != null ? launchLoader.asURLClassLoader() : Main.compatLoader;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        ClassLoader classLoader = getClassLoader();
        if (!(classLoader instanceof ExtensibleClassLoader)) {
            Main.logger.warn(
                    "SafeAsmClassWriter#getClassLoader() didn't return an ExtensibleClassLoader, falling back to default getCommonSuperClass implementation");
            return super.getCommonSuperClass(type1, type2);
        }
        ExtensibleClassLoader loader = (ExtensibleClassLoader) classLoader;

        // Crash if types aren't real
        InheritanceNode inheritance1 = getInheritanceInfo(loader, type1);
        if (inheritance1 == null) {
            throw new TypeNotPresentException(type1, null);
        }

        InheritanceNode inheritance2 = getInheritanceInfo(loader, type2);
        if (inheritance2 == null) {
            throw new TypeNotPresentException(type2, null);
        }

        // Assignable checks, replicates the ones in asm
        if (inheritance1.isAssignableFrom(inheritance2)) {
            return type1;
        }
        if (inheritance2.isAssignableFrom(inheritance1)) {
            return type2;
        }

        // this is a bit weird but it's what ASM does
        if (inheritance1.accessor.isInterface() || inheritance2.accessor.isInterface()) {
            return "java/lang/Object";
        } else {
            do {
                inheritance1 = inheritance1.superClass;
                // We might have some cut-off inheritance trees that don't properly reach back to Object. Avoid crashing
                // on those.
                if (inheritance1 == null) {
                    return "java/lang/Object";
                }
            } while (!inheritance1.isAssignableFrom(inheritance2));
            return inheritance1.accessor.binaryThisName();
        }
    }

    private @Nullable InheritanceNode getInheritanceInfo(ExtensibleClassLoader loader, String type) {
        InheritanceNode node = inheritanceCache.get(type);
        if (node == null) {
            FastClassAccessor typeAccessor = loader.findClassMetadata(type.replace('/', '.'));
            if (typeAccessor == null) {
                Main.logger.warn("Could not find type {} during inheritance search", type);
                return null;
            }

            Set<InheritanceNode> allSuperClasses = new HashSet<>();
            String superType = typeAccessor.binarySuperName();
            InheritanceNode superClass;
            // account for Object
            if (superType != null) {
                superClass = getInheritanceInfo(loader, superType);
                if (superClass != null) {
                    allSuperClasses.addAll(superClass.allSuperClasses);
                }
            } else {
                superClass = null;
            }

            List<InheritanceNode> interfaces = new ArrayList<>();
            for (String interfaceType : typeAccessor.binaryInterfaceNames()) {
                InheritanceNode iface = getInheritanceInfo(loader, interfaceType);
                if (iface != null) {
                    interfaces.add(iface);
                    allSuperClasses.addAll(iface.allSuperClasses);
                }
            }

            node = new InheritanceNode(typeAccessor, superClass, interfaces, allSuperClasses);
            inheritanceCache.put(type, node);
        }
        return node;
    }

    /**
     * Some inheritance metadata. Can be compared with == as long as they're from the same ClassWriter because of caching.
     */
    private static class InheritanceNode {
        final @Nullable InheritanceNode superClass;
        final @NotNull List<InheritanceNode> interfaces;
        final @NotNull FastClassAccessor accessor;
        // For quick isAssignableFrom checks
        final @NotNull Set<InheritanceNode> allSuperClasses;

        InheritanceNode(
                @NotNull FastClassAccessor accessor,
                @Nullable InheritanceNode superClass,
                @NotNull List<InheritanceNode> interfaces,
                @NotNull Set<InheritanceNode> allSuperClasses) {
            this.accessor = accessor;
            this.superClass = superClass;
            this.interfaces = interfaces;
            this.allSuperClasses = allSuperClasses;
            allSuperClasses.add(this);
        }

        boolean isAssignableFrom(InheritanceNode other) {
            return other.allSuperClasses.contains(this);
        }
    }
}
