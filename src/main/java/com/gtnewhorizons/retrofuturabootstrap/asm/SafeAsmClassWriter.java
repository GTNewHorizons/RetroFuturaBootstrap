package com.gtnewhorizons.retrofuturabootstrap.asm;

import com.gtnewhorizons.retrofuturabootstrap.Main;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * Simple ClassWriter that uses LaunchClassLoader for common superclass finding instead of the system loader.
 */
public class SafeAsmClassWriter extends ClassWriter {
    public static final ThreadLocal<Integer> forcedFlags = ThreadLocal.withInitial(() -> 0);
    public static final ThreadLocal<byte[]> forcedOriginalClass = new ThreadLocal<>();

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
}
