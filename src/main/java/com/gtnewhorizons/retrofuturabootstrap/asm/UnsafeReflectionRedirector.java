package com.gtnewhorizons.retrofuturabootstrap.asm;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import sun.misc.Unsafe;

/**
 * A handler for the somewhat common pattern of modifying the internal Field#modifiers modifier to write to "static final" fields.
 */
@SuppressWarnings("unused") // used from ASM
public class UnsafeReflectionRedirector {
    /** Provides some dummy fields to use instead of Java-internal handles */
    public static class Dummy {
        private int modifiers;
    }

    private static final Class<?> fieldClass = Field.class;
    private static final Field fieldModifiers;
    private static final Set<Field> unlockedFields = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Unsafe unsafe;

    private static synchronized void unlockField(Field f) {
        unlockedFields.add(f);
    }

    private static synchronized boolean isFieldUnlocked(Field f) {
        return unlockedFields.contains(f);
    }

    static {
        try {
            fieldModifiers = Dummy.class.getDeclaredField("modifiers");
            final Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = (Unsafe) theUnsafe.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** {@link Class#getDeclaredField(String)} */
    public static Field getDeclaredField(Class<?> klass, String name) throws NoSuchFieldException, SecurityException {
        if (klass == fieldClass) {
            if ("modifiers".equals(name)) {
                return fieldModifiers;
            } else {
                throw new NoSuchFieldException(klass.getName() + "#" + name);
            }
        }
        return klass.getDeclaredField(name);
    }

    /** {@link Class#getDeclaredFields()} */
    public static Field[] getDeclaredFields(Class<?> klass) throws NoSuchFieldException, SecurityException {
        if (klass == fieldClass) {
            return new Field[] {fieldModifiers};
        }
        return klass.getDeclaredFields();
    }

    /** {@link Field#setInt(Object, int)} */
    public static void setInt(Field field, Object target, int value)
            throws IllegalArgumentException, IllegalAccessException {
        if (field == fieldModifiers) {
            final Field targetF = (Field) target;
            final int actualModifiers = targetF.getModifiers();
            if (Modifier.isStatic(actualModifiers) && Modifier.isFinal(actualModifiers)) {
                if ((value & Modifier.FINAL) == 0) {
                    unlockField(targetF);
                }
            } else {
                targetF.setAccessible(true);
            }
            return;
        }
        field.setInt(target, value);
    }

    /** {@link Field#getInt(Object)} */
    public static int getInt(Field field, Object target) throws IllegalArgumentException, IllegalAccessException {
        if (field == fieldModifiers) {
            final Field targetF = (Field) target;
            final boolean isUnlocked = isFieldUnlocked(targetF);
            int modifiers = targetF.getModifiers();
            if (isUnlocked) {
                modifiers &= ~Modifier.FINAL;
            }
            return modifiers;
        }
        return field.getInt(target);
    }

    /** {@link Field#set(Object, Object)} */
    public static void set(Field field, Object target, Object value)
            throws IllegalArgumentException, IllegalAccessException {
        if (isFieldUnlocked(field)) {
            // Only static final fields are redirected to Unsafe.
            if (!Modifier.isStatic(field.getModifiers())) {
                throw new IllegalStateException("unsafe redirect of non-static field set");
            }
            if (!field.getType().isAssignableFrom(value.getClass())) {
                throw new IllegalArgumentException(
                        "Field " + field.getType() + " not assignable from " + value.getClass());
            }
            final long staticOffset = unsafe.staticFieldOffset(field);
            final Object staticObject = unsafe.staticFieldBase(field);
            unsafe.putObjectVolatile(staticObject, staticOffset, value);
        } else {
            field.set(target, value);
        }
    }
}
