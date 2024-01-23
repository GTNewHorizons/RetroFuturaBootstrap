package com.gtnewhorizons.retrofuturabootstrap.test;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassFileUtils;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ClassFileUtilsTest {

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void hasSubstring() {
        Assertions.assertFalse(ClassFileUtils.hasSubstring(bytes("abcd"), bytes("e")));
        Assertions.assertFalse(ClassFileUtils.hasSubstring(bytes("abcd"), bytes("ac")));
        Assertions.assertFalse(ClassFileUtils.hasSubstring(bytes("abcd"), bytes("dc")));
        Assertions.assertTrue(ClassFileUtils.hasSubstring(bytes("abcd"), bytes("bc")));
        Assertions.assertTrue(ClassFileUtils.hasSubstring(bytes("abcd"), bytes("ab")));
        Assertions.assertTrue(ClassFileUtils.hasSubstring(bytes("abcd"), bytes("cd")));
    }
}
