package com.gtnewhorizons.retrofuturabootstrap.asm;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
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
            setModifiers(target, value);
        }
        field.setInt(target, value);
    }

    /** {@link Field#setShort(Object, short)} */
    public static void setShort(Field field, Object target, short value)
            throws IllegalArgumentException, IllegalAccessException {
        if (field == fieldModifiers) {
            setModifiers(target, value);
        }
        field.setShort(target, value);
    }

    /** {@link Field#setByte(Object, byte)} */
    public static void setByte(Field field, Object target, byte value)
            throws IllegalArgumentException, IllegalAccessException {
        if (field == fieldModifiers) {
            setModifiers(target, value);
        }
        field.setByte(target, value);
    }

    /** {@link Field#setChar(Object, char)} */
    public static void setChar(Field field, Object target, char value)
            throws IllegalArgumentException, IllegalAccessException {
        if (field == fieldModifiers) {
            setModifiers(target, value);
        }
        field.setChar(target, value);
    }

    /** {@link Field#getInt(Object)} */
    public static int getInt(Field field, Object target) throws IllegalArgumentException, IllegalAccessException {
        if (field == fieldModifiers) {
            return getModifiers(target);
        }
        return field.getInt(target);
    }

    /** {@link Field#getLong(Object)} */
    public static long getLong(Field field, Object target) throws IllegalArgumentException, IllegalAccessException {
        if (field == fieldModifiers) {
            return getModifiers(target);
        }
        return field.getLong(target);
    }

    /** {@link Field#getFloat(Object)} */
    public static float getFloat(Field field, Object target) throws IllegalArgumentException, IllegalAccessException {
        if (field == fieldModifiers) {
            return getModifiers(target);
        }
        return field.getFloat(target);
    }

    /** {@link Field#getDouble(Object)} */
    public static double getDouble(Field field, Object target) throws IllegalArgumentException, IllegalAccessException {
        if (field == fieldModifiers) {
            return getModifiers(target);
        }
        return field.getDouble(target);
    }

    /** {@link Field#set(Object, Object)} */
    public static void set(Field field, Object target, Object value)
            throws IllegalArgumentException, IllegalAccessException {
        if (field == fieldModifiers && canCoerceToInt(value)) {
            setModifiers(target, coerceToInt(value));
            return;
        }

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

    /** {@link Field#get(Object)} */
    public static Object get(Field field, Object target) throws IllegalArgumentException, IllegalAccessException {
        if (field == fieldModifiers) {
            return getModifiers(target);
        }
        return field.get(target);
    }

    private static int coerceToInt(Object obj) {
        if (obj instanceof Number) {
            return ((Number) obj).intValue();
        } else if (obj instanceof Character) {
            return (Character) obj;
        } else {
            throw new IllegalArgumentException("Passed non-integer-coerceable value to coerceToInt: " + obj);
        }
    }

    private static boolean canCoerceToInt(Object obj) {
        return obj instanceof Byte || obj instanceof Short || obj instanceof Integer || obj instanceof Character;
    }

    private static void setModifiers(Object target, int value) {
        final Field targetF = (Field) target;
        final int actualModifiers = targetF.getModifiers();
        if (Modifier.isStatic(actualModifiers) && Modifier.isFinal(actualModifiers)) {
            if ((value & Modifier.FINAL) == 0) {
                unlockField(targetF);
            }
        } else {
            targetF.setAccessible(true);
        }
    }

    private static int getModifiers(Object target) {
        final Field targetF = (Field) target;
        final boolean isUnlocked = isFieldUnlocked(targetF);
        int modifiers = targetF.getModifiers();
        if (isUnlocked) {
            modifiers &= ~Modifier.FINAL;
        }
        return modifiers;
    }
}
