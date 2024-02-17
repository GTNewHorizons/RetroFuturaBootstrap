package com.gtnewhorizons.retrofuturabootstrap.asm;

import java.util.UUID;

/**
 * Redirect for {@link UUID#fromString(String)} to the version used in Java 8 that was less strict with validation.
 */
@SuppressWarnings("unused") // used from asms
public class UuidStringConstructor {
    public static UUID fromString(String name) {
        String[] components = name.split("-");
        if (components.length != 5) throw new IllegalArgumentException("Invalid UUID string: " + name);
        for (int i = 0; i < 5; i++) components[i] = "0x" + components[i];

        long mostSigBits = Long.decode(components[0]);
        mostSigBits <<= 16;
        mostSigBits |= Long.decode(components[1]);
        mostSigBits <<= 16;
        mostSigBits |= Long.decode(components[2]);

        long leastSigBits = Long.decode(components[3]);
        leastSigBits <<= 48;
        leastSigBits |= Long.decode(components[4]);

        return new UUID(mostSigBits, leastSigBits);
    }
}
