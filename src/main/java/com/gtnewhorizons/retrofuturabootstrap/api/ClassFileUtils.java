package com.gtnewhorizons.retrofuturabootstrap.api;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utilities for quickly processing class files without fully parsing them.
 */
public final class ClassFileUtils {
    // Read unsigned byte as an int helper.
    private static int nth(byte[] arr, int i) {
        return ((int) arr[i]) & 0xff;
    }

    /**
     * Sanity-checks the validity of the class header.
     * @param classBytes Class data
     * @param offset Index at which the class data starts, use 0 if it's the whole array
     * @return If the class passes simple sanity checks.
     */
    @Contract("null, _ -> false")
    public static boolean isValidClass(byte @Nullable [] classBytes, int offset) {
        if (classBytes == null) {
            return false;
        }
        if (classBytes.length < offset + 24) {
            return false;
        }
        final int magic = (nth(classBytes, 0) << 24)
                | (nth(classBytes, 1) << 16)
                | (nth(classBytes, 2) << 8)
                | (nth(classBytes, 3));
        if (magic != 0xCAFEBABE) {
            return false;
        }
        return true;
    }

    /**
     * @param classBytes Class data
     * @param offset Index at which the class data starts, use 0 if it's the whole array
     * @return The major version number of the class file. See {@link org.objectweb.asm.Opcodes#V1_8}, {@link org.objectweb.asm.Opcodes#V17}
     */
    public static int majorVersion(byte @NotNull [] classBytes, int offset) {
        return (nth(classBytes, 6) << 8) | nth(classBytes, 7);
    }

    /**
     * Searches for a sub"string" (byte array) in a longer byte array. Not efficient for long search strings.
     * @param classBytes The long byte string to search in.
     * @param substring The short substring to search for.
     * @return If the substring was found somewhere in the long string.
     */
    public static boolean hasSubstring(final byte @Nullable [] classBytes, final byte @NotNull [] substring) {
        if (classBytes == null) {
            return false;
        }
        final int classLen = classBytes.length;
        final int subLen = substring.length;
        if (classLen < subLen) {
            return false;
        }
        outer:
        for (int startPos = 0; startPos + subLen - 1 < classLen; startPos++) {
            for (int i = 0; i < subLen; i++) {
                if (classBytes[startPos + i] != substring[i]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
