package com.gtnewhorizons.retrofuturabootstrap.test;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ClassHeaderMetadataTest {

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void hasSubstring() throws IOException {
        byte[] classBytes = stubClassBytes("org/lwjgl/opengl/GL11");

        Assertions.assertFalse(ClassHeaderMetadata.hasSubstring(classBytes, bytes("org/whatever")));
        Assertions.assertTrue(ClassHeaderMetadata.hasSubstring(classBytes, bytes("org")));
        Assertions.assertTrue(ClassHeaderMetadata.hasSubstring(classBytes, bytes("lwjgl")));
        Assertions.assertTrue(ClassHeaderMetadata.hasSubstring(classBytes, bytes("org/lwjgl/")));
        Assertions.assertTrue(ClassHeaderMetadata.hasSubstring(classBytes, bytes("org/lwjgl/opengl/GL11")));
        Assertions.assertFalse(ClassHeaderMetadata.hasSubstring(classBytes, bytes("org/lwjgl/opengl/GL11/meh")));
    }

    private static byte[] stubClassBytes(String stubPoolConstant) throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(byteStream);

        out.writeInt(0xCAFEBABE); // magic
        out.writeShort(0); // minor version
        out.writeShort(52); // major version (Java 8)

        // Constant pool layout:
        //  #0 unused index
        //  #1 Utf8 "Test"
        //  #2 Class #1
        //  #3 Utf8 "java/lang/Object"
        //  #4 Class #3
        //  #5 Utf8 stubPoolConstant
        int cpCount = 1 + 4 + 1;
        out.writeShort(cpCount);

        // class StubClass
        writeUtf8(out, "StubClass");
        writeClass(out, 1);
        // extends Object
        writeUtf8(out, "java/lang/Object");
        writeClass(out, 3);

        writeUtf8(out, stubPoolConstant);

        out.writeShort(0x0021); // access_flags (public + super)
        out.writeShort(2); // this_class (#2)
        out.writeShort(4); // super_class (#4)
        out.writeShort(0); // interfaces_count
        out.writeShort(0); // fields_count
        out.writeShort(0); // methods_count
        out.writeShort(0); // attributes_count

        out.flush();
        return byteStream.toByteArray();
    }

    private static void writeUtf8(DataOutputStream out, String s) throws IOException {
        out.writeByte(1);
        out.writeUTF(s);
    }

    private static void writeClass(DataOutputStream out, int nameIndex) throws IOException {
        out.writeByte(7);
        out.writeShort(nameIndex);
    }
}
