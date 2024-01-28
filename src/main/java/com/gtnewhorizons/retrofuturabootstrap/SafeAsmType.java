package com.gtnewhorizons.retrofuturabootstrap;

import org.objectweb.asm.Type;

@SuppressWarnings("unused") // Used for an ASM redirect
public class SafeAsmType {
    /**
     * Redirection target for {@link Type#getType(String)}, that will silently accept invalid descriptors as object descriptors, like ASM5 did.
     */
    public static Type getType(String desc) {
        try {
            return Type.getType(desc);
        } catch (IllegalArgumentException e) {
            // ASM5 Code:
            // default: return new Type(METHOD, buf, off, buf.length - off);
            // The accidental usage generally was intended to use Object types, as correct method descriptors start with
            // '('
            return Type.getObjectType(desc);
        }
    }
}
