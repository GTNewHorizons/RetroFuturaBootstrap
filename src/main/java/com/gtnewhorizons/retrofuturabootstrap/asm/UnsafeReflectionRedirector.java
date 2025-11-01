package com.gtnewhorizons.retrofuturabootstrap.asm;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.SwitchPoint;
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
    private static final MethodHandles.Lookup self = MethodHandles.lookup();
    private static final Unsafe unsafe;

    private static final ClassValue<Map<Field, Accessors>> fieldAccessors = new ClassValue<Map<Field, Accessors>>() {
        @Override
        protected Map<Field, Accessors> computeValue(@NotNull Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

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
        MethodHandle target = self.findStatic(UnsafeReflectionRedirector.class, name, newType)
                .bindTo(caller)
                .asType(methodType);
        return new ConstantCallSite(target);
    }

    private static class Accessors {
        final MethodHandle getExact;
        final MethodHandle setExact;
        final MethodHandle getErased;
        final MethodHandle setErased;
        private final SwitchPoint sp;

        void unlock() {
            if (sp != null) {
                SwitchPoint.invalidateAll(new SwitchPoint[] {sp});
            }
        }

        boolean isUnlocked() {
            return sp != null && sp.hasBeenInvalidated();
        }

        Accessors(MethodHandles.Lookup caller, Field field) throws IllegalAccessException, NoSuchMethodException {
            Class<?> type = field.getType();
            MethodHandle getter = caller.unreflectGetter(field);
            MethodHandle setter = caller.unreflectSetter(field);
            if (Modifier.isStatic(field.getModifiers())) {
                // add a dummy first argument for static fields, so the target will be passed in and ignored
                getter = MethodHandles.dropArguments(getter, 0, Object.class);
                setter = MethodHandles.dropArguments(setter, 0, Object.class);

                MethodHandle unsafeSetter = makeUnsafeSetter(field);
                sp = new SwitchPoint();
                setter = sp.guardWithTest(setter, unsafeSetter);
            } else {
                sp = null;
            }
            this.getExact = getter.asType(MethodType.methodType(type, Object.class));
            this.setExact = setter.asType(MethodType.methodType(void.class, Object.class, type));
            this.getErased = getter.asType(MethodType.methodType(Object.class, Object.class));
            this.setErased = setter.asType(MethodType.methodType(void.class, Object.class, Object.class));
        }
    }

    private static MethodHandle makeUnsafeSetter(Field field) throws NoSuchMethodException, IllegalAccessException {
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException("Field must be static");
        }
        Object base = unsafe.staticFieldBase(field);
        long off = unsafe.staticFieldOffset(field);
        Class<?> type = field.getType();

        String name;
        MethodType shape;
        if (type.isPrimitive()) {
            name = "put" + Character.toUpperCase(type.getName().charAt(0))
                    + type.getName().substring(1) + "Volatile";
            shape = MethodType.methodType(void.class, Object.class, long.class, type);
        } else {
            name = "putObjectVolatile";
            shape = MethodType.methodType(void.class, Object.class, long.class, Object.class);
        }

        MethodHandle mh = self.findVirtual(Unsafe.class, name, shape).bindTo(unsafe);
        return MethodHandles.insertArguments(mh, 1, base, off);
    }

    private static Accessors getAccessors(MethodHandles.Lookup caller, Field field) throws Throwable {
        Map<Field, Accessors> forClass = fieldAccessors.get(caller.lookupClass());
        Accessors accessors = forClass.get(field);
        if (accessors == null) {
            accessors = new Accessors(caller, field);
            forClass.put(field, accessors);
        }
        return accessors;
    }

    /** {@link Class#getDeclaredField(String)} */
    private static Field getDeclaredField(Class<?> klass, String name) throws NoSuchFieldException, SecurityException {
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
    private static Field[] getDeclaredFields(Class<?> klass) throws SecurityException {
        if (klass == fieldClass) {
            return new Field[] {fieldModifiers};
        }
        return klass.getDeclaredFields();
    }

    /** {@link Field#setInt(Object, int)} */
    private static void setInt(MethodHandles.Lookup caller, Field field, Object target, int value) throws Throwable {
        if (field == fieldModifiers) {
            setModifiers(caller, target, value);
            return;
        }
        getAccessors(caller, field).setExact.invokeExact(target, value);
    }

    /** {@link Field#setShort(Object, short)} */
    private static void setShort(MethodHandles.Lookup caller, Field field, Object target, short value)
            throws Throwable {
        if (field == fieldModifiers) {
            setModifiers(caller, target, value);
            return;
        }
        getAccessors(caller, field).setExact.invokeExact(target, (short) value);
    }

    /** {@link Field#setByte(Object, byte)} */
    private static void setByte(MethodHandles.Lookup caller, Field field, Object target, byte value) throws Throwable {
        if (field == fieldModifiers) {
            setModifiers(caller, target, value);
            return;
        }
        getAccessors(caller, field).setExact.invokeExact(target, (byte) value);
    }

    /** {@link Field#setChar(Object, char)} */
    private static void setChar(MethodHandles.Lookup caller, Field field, Object target, char value) throws Throwable {
        if (field == fieldModifiers) {
            setModifiers(caller, target, value);
            return;
        }
        getAccessors(caller, field).setExact.invokeExact(target, (char) value);
    }

    /** {@link Field#getInt(Object)} */
    private static int getInt(MethodHandles.Lookup caller, Field field, Object target) throws Throwable {
        if (field == fieldModifiers) {
            return getModifiers(caller, target);
        }
        return (int) getAccessors(caller, field).getExact.invokeExact(target);
    }

    /** {@link Field#getLong(Object)} */
    private static long getLong(MethodHandles.Lookup caller, Field field, Object target) throws Throwable {
        if (field == fieldModifiers) {
            return getModifiers(caller, target);
        }
        return (long) getAccessors(caller, field).getExact.invokeExact(target);
    }

    /** {@link Field#getFloat(Object)} */
    private static float getFloat(MethodHandles.Lookup caller, Field field, Object target) throws Throwable {
        if (field == fieldModifiers) {
            return getModifiers(caller, target);
        }
        return (float) getAccessors(caller, field).getExact.invokeExact(target);
    }

    /** {@link Field#getDouble(Object)} */
    private static double getDouble(MethodHandles.Lookup caller, Field field, Object target) throws Throwable {
        if (field == fieldModifiers) {
            return getModifiers(caller, target);
        }
        return (double) getAccessors(caller, field).getExact.invokeExact(target);
    }

    /** {@link Field#set(Object, Object)} */
    private static void set(MethodHandles.Lookup caller, Field field, Object target, Object value) throws Throwable {
        if (field == fieldModifiers && canCoerceToInt(value)) {
            setModifiers(caller, target, coerceToInt(value));
            return;
        }
        getAccessors(caller, field).setErased.invokeExact(target, value);
    }

    /** {@link Field#get(Object)} */
    private static Object get(MethodHandles.Lookup caller, Field field, Object target) throws Throwable {
        if (field == fieldModifiers) {
            return getModifiers(caller, target);
        }
        return getAccessors(caller, field).getErased.invokeExact(target);
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

    private static void setModifiers(MethodHandles.Lookup caller, Object target, int value) throws Throwable {
        final Field targetF = (Field) target;
        final int actualModifiers = targetF.getModifiers();
        if (Modifier.isStatic(actualModifiers) && Modifier.isFinal(actualModifiers)) {
            if ((value & Modifier.FINAL) == 0) {
                getAccessors(caller, targetF).unlock();
            }
        } else {
            targetF.setAccessible(true);
        }
    }

    private static int getModifiers(MethodHandles.Lookup caller, Object target) throws Throwable {
        final Field targetF = (Field) target;
        int modifiers = targetF.getModifiers();
        if (getAccessors(caller, targetF).isUnlocked()) {
            modifiers &= ~Modifier.FINAL;
        }
        return modifiers;
    }
}
