package com.gtnewhorizons.retrofuturabootstrap.api;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** An accessor to metadata about a class that is quickly accessible without fully parsing one. */
public interface FastClassAccessor {
    /** Accessible from outside its package */
    boolean isPublic();
    /** No subclasses allowed */
    boolean isFinal();
    /** Is an interface instead of a class */
    boolean isInterface();
    /** Is an abstract class that should not be instantiated */
    boolean isAbstract();
    /** Is not present in source code (often used by obfuscated code too) */
    boolean isSynthetic();
    /** Is an annotation interface */
    boolean isAnnotation();
    /** Is an enum class */
    boolean isEnum();
    /** Is a module-info instead of a real class */
    boolean isModule();
    /** Binary (slash-separated packages) name of the class */
    @NotNull
    String binaryThisName();
    /** Binary (slash-separated packages) name of the super-class, null for the Object class */
    @Nullable
    String binarySuperName();

    /** Binary (slash-separated packages) names of the implemented interfaces, list is unmodifiable */
    @NotNull
    List<@NotNull String> binaryInterfaceNames();

    static OfLoaded ofLoaded(Class<?> loadedClass) {
        return new OfLoaded(loadedClass);
    }

    static OfAsmNode ofAsmNode(ClassNode handle) {
        return new OfAsmNode(handle);
    }

    final class OfAsmNode implements FastClassAccessor {
        public final ClassNode handle;

        public OfAsmNode(ClassNode handle) {
            this.handle = handle;
        }

        @Override
        public boolean isPublic() {
            return (handle.access & Opcodes.ACC_PUBLIC) != 0;
        }

        @Override
        public boolean isFinal() {
            return (handle.access & Opcodes.ACC_FINAL) != 0;
        }

        @Override
        public boolean isInterface() {
            return (handle.access & Opcodes.ACC_INTERFACE) != 0;
        }

        @Override
        public boolean isAbstract() {
            return (handle.access & Opcodes.ACC_ABSTRACT) != 0;
        }

        @Override
        public boolean isSynthetic() {
            return (handle.access & Opcodes.ACC_SYNTHETIC) != 0;
        }

        @Override
        public boolean isAnnotation() {
            return (handle.access & Opcodes.ACC_ANNOTATION) != 0;
        }

        @Override
        public boolean isEnum() {
            return (handle.access & Opcodes.ACC_ENUM) != 0;
        }

        @Override
        public boolean isModule() {
            return (handle.access & Opcodes.ACC_MODULE) != 0;
        }

        @Override
        public @NotNull String binaryThisName() {
            return handle.name;
        }

        @Override
        public @Nullable String binarySuperName() {
            return handle.superName;
        }

        @Override
        public @NotNull List<@NotNull String> binaryInterfaceNames() {
            return handle.interfaces == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(handle.interfaces);
        }
    }

    final class OfLoaded implements FastClassAccessor {
        public final Class<?> handle;
        private @Nullable List<@NotNull String> interfacesCache = null;

        private OfLoaded(Class<?> handle) {
            this.handle = handle;
        }

        @Override
        public boolean isPublic() {
            return Modifier.isPublic(handle.getModifiers());
        }

        @Override
        public boolean isFinal() {
            return Modifier.isFinal(handle.getModifiers());
        }

        @Override
        public boolean isInterface() {
            return Modifier.isInterface(handle.getModifiers());
        }

        @Override
        public boolean isAbstract() {
            return Modifier.isAbstract(handle.getModifiers());
        }

        @Override
        public boolean isSynthetic() {
            return handle.isSynthetic();
        }

        @Override
        public boolean isAnnotation() {
            return handle.isAnnotation();
        }

        @Override
        public boolean isEnum() {
            return handle.isEnum();
        }

        @Override
        public boolean isModule() {
            return false;
        }

        @Override
        public @NotNull String binaryThisName() {
            return handle.getName().replace('.', '/');
        }

        @Override
        public @Nullable String binarySuperName() {
            final Class<?> superclass = handle.getSuperclass();
            return superclass == null ? null : superclass.getName().replace('.', '/');
        }

        @Override
        public @NotNull List<@NotNull String> binaryInterfaceNames() {
            if (interfacesCache != null) {
                return interfacesCache;
            }

            Class<?>[] interfaces = handle.getInterfaces();
            List<String> binaryInterfaceNames = new ArrayList<>(interfaces.length);
            for (Class<?> iface : interfaces) {
                binaryInterfaceNames.add(iface.getName().replace('.', '/'));
            }
            interfacesCache = Collections.unmodifiableList(binaryInterfaceNames);
            return interfacesCache;
        }
    }
}
