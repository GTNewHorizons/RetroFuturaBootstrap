package com.gtnewhorizons.retrofuturabootstrap;

import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * Simple ClassWriter that uses LaunchClassLoader for common superclass finding instead of the system loader.
 */
public class SafeAsmClassWriter extends ClassWriter {
    public SafeAsmClassWriter(int flags) {
        super(flags);
    }

    public SafeAsmClassWriter(ClassReader classReader, int flags) {
        super(classReader, flags);
    }

    @Override
    protected ClassLoader getClassLoader() {
        final ExtensibleClassLoader launchLoader = Main.launchLoader;
        return launchLoader != null ? launchLoader.asURLClassLoader() : Main.compatLoader;
    }
}
