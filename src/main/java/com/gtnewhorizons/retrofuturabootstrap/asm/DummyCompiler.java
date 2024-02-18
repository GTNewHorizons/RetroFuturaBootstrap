package com.gtnewhorizons.retrofuturabootstrap.asm;

/** Replaces the removed java.lang.Compiler with a dummy implementation that does nothing (as it already did in older Java versions). */
public class DummyCompiler {
    private DummyCompiler() {}

    public static boolean compileClass(Class<?> clazz) {
        return false;
    }

    public static boolean compileClasses(String string) {
        return false;
    }

    public static Object command(Object any) {
        return null;
    }

    public static void enable() {}

    public static void disable() {}
}
