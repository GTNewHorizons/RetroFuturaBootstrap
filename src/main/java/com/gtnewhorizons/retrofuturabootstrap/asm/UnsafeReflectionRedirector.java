package com.gtnewhorizons.retrofuturabootstrap.asm;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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

    public static CallSite bsm(MethodHandles.Lookup caller, String name, MethodType methodType) throws Exception {
        MethodType newType = methodType.insertParameterTypes(0, MethodHandles.Lookup.class);
        MethodHandle target = caller.findStatic(UnsafeReflectionRedirector.class, name, newType)
                .bindTo(caller)
                .asType(methodType);
        return new ConstantCallSite(target);
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
    public static void setInt(MethodHandles.Lookup caller, Field field, Object target, int value)
            throws Throwable {
        if (field == fieldModifiers) {
            setModifiers(target, value);
            return;
        }
        MethodHandle setter = caller.unreflectSetter(field);
        if (Modifier.isStatic(field.getModifiers())) {
            setter.invoke((int) value);
        } else {
            setter.invoke(target, (int) value);
        }
    }

    /** {@link Field#setShort(Object, short)} */
    public static void setShort(MethodHandles.Lookup caller, Field field, Object target, short value)
            throws Throwable {
        if (field == fieldModifiers) {
            setModifiers(target, value);
            return;
        }
        MethodHandle setter = caller.unreflectSetter(field);
        if (Modifier.isStatic(field.getModifiers())) {
            setter.invoke((short) value);
        } else {
            setter.invoke(target, (short) value);
        }
    }

    /** {@link Field#setByte(Object, byte)} */
    public static void setByte(MethodHandles.Lookup caller, Field field, Object target, byte value)
            throws Throwable {
        if (field == fieldModifiers) {
            setModifiers(target, value);
            return;
        }
        MethodHandle setter = caller.unreflectSetter(field);
        if (Modifier.isStatic(field.getModifiers())) {
            setter.invoke((byte) value);
        } else {
            setter.invoke(target, (byte) value);
        }
    }

    /** {@link Field#setChar(Object, char)} */
    public static void setChar(MethodHandles.Lookup caller, Field field, Object target, char value)
            throws Throwable {
        if (field == fieldModifiers) {
            setModifiers(target, value);
            return;
        }
        MethodHandle setter = caller.unreflectSetter(field);
        if (Modifier.isStatic(field.getModifiers())) {
            setter.invoke((char) value);
        } else {
            setter.invoke(target, (char) value);
        }
    }

    /** {@link Field#getInt(Object)} */
    public static int getInt(MethodHandles.Lookup caller, Field field, Object target) throws Throwable {
        if (field == fieldModifiers) {
            return getModifiers(target);
        }
        MethodHandle getter = caller.unreflectGetter(field);
        if (Modifier.isStatic(field.getModifiers())) {
            return (int) getter.invoke();
        } else {
            return (int) getter.invoke(target);
        }
    }

    /** {@link Field#getLong(Object)} */
    public static long getLong(MethodHandles.Lookup caller, Field field, Object target) throws Throwable {
        if (field == fieldModifiers) {
            return getModifiers(target);
        }
        MethodHandle getter = caller.unreflectGetter(field);
        if (Modifier.isStatic(field.getModifiers())) {
            return (long) getter.invoke();
        } else {
            return (long) getter.invoke(target);
        }
    }

    /** {@link Field#getFloat(Object)} */
    public static float getFloat(MethodHandles.Lookup caller, Field field, Object target) throws Throwable {
        if (field == fieldModifiers) {
            return getModifiers(target);
        }
        MethodHandle getter = caller.unreflectGetter(field);
        if (Modifier.isStatic(field.getModifiers())) {
            return (float) getter.invoke();
        } else {
            return (float) getter.invoke(target);
        }
    }

    /** {@link Field#getDouble(Object)} */
    public static double getDouble(MethodHandles.Lookup caller, Field field, Object target) throws Throwable {
        if (field == fieldModifiers) {
            return getModifiers(target);
        }
        MethodHandle getter = caller.unreflectGetter(field);
        if (Modifier.isStatic(field.getModifiers())) {
            return (double) getter.invoke();
        } else {
            return (double) getter.invoke(target);
        }
    }

    /** {@link Field#set(Object, Object)} */
    public static void set(MethodHandles.Lookup caller, Field field, Object target, Object value)
            throws Throwable {
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
        } else if (target instanceof Dummy
                && value instanceof Field) { // Do nothing if trying to cast Field to Dummy$modifiers
            return;
        } else {
            MethodHandle setter = caller.unreflectSetter(field);
            if (Modifier.isStatic(field.getModifiers())) {
                setter.invoke(value);
            } else {
                setter.invoke(target, value);
            }
        }
    }

    /** {@link Field#get(Object)} */
    public static Object get(MethodHandles.Lookup caller, Field field, Object target) throws Throwable {
        if (field == fieldModifiers) {
            return getModifiers(target);
        }
        MethodHandle getter = caller.unreflectGetter(field);
        if (Modifier.isStatic(field.getModifiers())) {
            return getter.invoke();
        } else {
            return getter.invoke(target);
        }
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
