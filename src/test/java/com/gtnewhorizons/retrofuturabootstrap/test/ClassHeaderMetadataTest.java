package com.gtnewhorizons.retrofuturabootstrap.test;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ClassHeaderMetadataTest {

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void hasSubstring() {
        Assertions.assertFalse(ClassHeaderMetadata.hasSubstring(bytes("abcd"), bytes("e")));
        Assertions.assertFalse(ClassHeaderMetadata.hasSubstring(bytes("abcd"), bytes("ac")));
        Assertions.assertFalse(ClassHeaderMetadata.hasSubstring(bytes("abcd"), bytes("dc")));
        Assertions.assertTrue(ClassHeaderMetadata.hasSubstring(bytes("abcd"), bytes("bc")));
        Assertions.assertTrue(ClassHeaderMetadata.hasSubstring(bytes("abcd"), bytes("ab")));
        Assertions.assertTrue(ClassHeaderMetadata.hasSubstring(bytes("abcd"), bytes("cd")));
    }
}
