package com.gtnewhorizons.retrofuturabootstrap.asm;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.NotNull;
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

    private static class Accessors {
        final boolean isStatic;
        final MethodHandle getter;
        final MethodHandle setter;

        MethodHandle getter(Object target) {
            return isStatic ? getter : getter.bindTo(target);
        }

        MethodHandle setter(Object target) {
            return isStatic ? setter : setter.bindTo(target);
        }

        Accessors(boolean isStatic, MethodHandle getter, MethodHandle setter) {
            this.isStatic = isStatic;
            this.getter = getter;
            this.setter = setter;
        }
    }

    private static final ClassValue<Map<Field, Accessors>> fieldAccessors = new ClassValue<Map<Field, Accessors>>() {
        @Override
        protected Map<Field, Accessors> computeValue(@NotNull Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private static Accessors getAccessors(MethodHandles.Lookup caller, Field field) throws IllegalAccessException {
        Map<Field, Accessors> classMap = fieldAccessors.get(caller.lookupClass());
        Accessors accessors = classMap.get(field);
        if (accessors == null) {
            boolean isStatic = Modifier.isStatic(field.getModifiers());
            MethodHandle getter = caller.unreflectGetter(field).asType(
                    isStatic ? MethodType.methodType(field.getType())
                            : MethodType.methodType(field.getType(), Object.class)
            );
            MethodHandle setter = caller.unreflectSetter(field).asType(
                    isStatic ? MethodType.methodType(void.class, field.getType())
                            : MethodType.methodType(void.class, Object.class, field.getType())
            );
            accessors = new Accessors(isStatic, getter, setter);
            classMap.put(field, accessors);
        }
        return accessors;
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
        if (Modifier.isStatic(field.getModifiers()) && isFieldUnlocked(field)) {
            Object base = unsafe.staticFieldBase(field);
            long off = unsafe.staticFieldOffset(field);
            unsafe.putIntVolatile(base, off, value);
            return;
        }
        getAccessors(caller, field).setter(target).invokeExact(value);
    }

    /** {@link Field#setShort(Object, short)} */
    public static void setShort(MethodHandles.Lookup caller, Field field, Object target, short value)
            throws Throwable {
        if (field == fieldModifiers) {
            setModifiers(target, value);
            return;
        }
        if (Modifier.isStatic(field.getModifiers()) && isFieldUnlocked(field)) {
            Object base = unsafe.staticFieldBase(field);
            long off = unsafe.staticFieldOffset(field);
            unsafe.putShortVolatile(base, off, value);
            return;
        }
        getAccessors(caller, field).setter(target).invokeExact((short) value);
    }

    /** {@link Field#setByte(Object, byte)} */
    public static void setByte(MethodHandles.Lookup caller, Field field, Object target, byte value)
            throws Throwable {
        if (field == fieldModifiers) {
            setModifiers(target, value);
            return;
        }
        if (Modifier.isStatic(field.getModifiers()) && isFieldUnlocked(field)) {
            Object base = unsafe.staticFieldBase(field);
            long off = unsafe.staticFieldOffset(field);
            unsafe.putByteVolatile(base, off, value);
            return;
        }
        getAccessors(caller, field).setter(target).invokeExact((byte) value);
    }

    /** {@link Field#setChar(Object, char)} */
    public static void setChar(MethodHandles.Lookup caller, Field field, Object target, char value)
            throws Throwable {
        if (field == fieldModifiers) {
            setModifiers(target, value);
            return;
        }
        if (Modifier.isStatic(field.getModifiers()) && isFieldUnlocked(field)) {
            Object base = unsafe.staticFieldBase(field);
            long off = unsafe.staticFieldOffset(field);
            unsafe.putCharVolatile(base, off, value);
            return;
        }
        getAccessors(caller, field).setter(target).invokeExact((char) value);
    }

    /** {@link Field#getInt(Object)} */
    public static int getInt(MethodHandles.Lookup caller, Field field, Object target) throws Throwable {
        if (field == fieldModifiers) {
            return getModifiers(target);
        }
        return (int) getAccessors(caller, field).getter(target).invokeExact();
    }

    /** {@link Field#getLong(Object)} */
    public static long getLong(MethodHandles.Lookup caller, Field field, Object target) throws Throwable {
        if (field == fieldModifiers) {
            return getModifiers(target);
        }
        return (long) getAccessors(caller, field).getter(target).invokeExact();
    }

    /** {@link Field#getFloat(Object)} */
    public static float getFloat(MethodHandles.Lookup caller, Field field, Object target) throws Throwable {
        if (field == fieldModifiers) {
            return getModifiers(target);
        }
        return (float) getAccessors(caller, field).getter(target).invokeExact();
    }

    /** {@link Field#getDouble(Object)} */
    public static double getDouble(MethodHandles.Lookup caller, Field field, Object target) throws Throwable {
        if (field == fieldModifiers) {
            return getModifiers(target);
        }
        return (double) getAccessors(caller, field).getter(target).invokeExact();
    }

    /** {@link Field#set(Object, Object)} */
    public static void set(MethodHandles.Lookup caller, Field field, Object target, Object value)
            throws Throwable {
        if (field == fieldModifiers && canCoerceToInt(value)) {
            setModifiers(target, coerceToInt(value));
            return;
        }

        if (Modifier.isStatic(field.getModifiers()) && isFieldUnlocked(field)) {
            if (!field.getType().isAssignableFrom(value.getClass()))
                throw new IllegalArgumentException("Field " + field.getType() + " not assignable from " + value.getClass());
            Object base = unsafe.staticFieldBase(field);
            long off = unsafe.staticFieldOffset(field);
            unsafe.putObjectVolatile(base, off, value);
            return;
        }
        if (target instanceof Dummy && value instanceof Field) return;
        getAccessors(caller, field).setter(target).invokeExact(value);
    }

    /** {@link Field#get(Object)} */
    public static Object get(MethodHandles.Lookup caller, Field field, Object target) throws Throwable {
        if (field == fieldModifiers) {
            return getModifiers(target);
        }
        return getAccessors(caller, field).getter(target).invokeExact();
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
