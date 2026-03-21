package com.gtnewhorizons.retrofuturabootstrap.test;

import static com.gtnewhorizons.retrofuturabootstrap.api.BytePatternMatcher.Mode.Contains;
import static com.gtnewhorizons.retrofuturabootstrap.api.BytePatternMatcher.Mode.Equals;
import static com.gtnewhorizons.retrofuturabootstrap.api.BytePatternMatcher.Mode.StartsWith;

import com.gtnewhorizons.retrofuturabootstrap.api.BytePatternMatcher;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ClassHeaderMetadataTest {

    @Test
    void matchesBytesContains() throws IOException {
        byte[] classBytes = stubClassBytes("org/lwjgl/opengl/GL11");
        ClassHeaderMetadata metadata = new ClassHeaderMetadata(classBytes);

        Assertions.assertFalse(metadata.matchesBytes(matcher("org/whatever", Contains)));
        Assertions.assertTrue(metadata.matchesBytes(matcher("org", Contains)));
        Assertions.assertTrue(metadata.matchesBytes(matcher("lwjgl", Contains)));
        Assertions.assertTrue(metadata.matchesBytes(matcher("org/lwjgl/", Contains)));
        Assertions.assertTrue(metadata.matchesBytes(matcher("org/lwjgl/opengl/GL11", Contains)));
        Assertions.assertFalse(metadata.matchesBytes(matcher("org/lwjgl/opengl/GL11/meh", Contains)));
    }

    @Test
    void matchesBytesEquals() throws IOException {
        byte[] classBytes = stubClassBytes("org/lwjgl/opengl/GL11");
        ClassHeaderMetadata metadata = new ClassHeaderMetadata(classBytes);

        Assertions.assertFalse(metadata.matchesBytes(matcher("org/whatever", Equals)));
        Assertions.assertFalse(metadata.matchesBytes(matcher("org", Equals)));
        Assertions.assertFalse(metadata.matchesBytes(matcher("lwjgl", Equals)));
        Assertions.assertFalse(metadata.matchesBytes(matcher("org/lwjgl/", Equals)));
        Assertions.assertTrue(metadata.matchesBytes(matcher("org/lwjgl/opengl/GL11", Equals)));
        Assertions.assertFalse(metadata.matchesBytes(matcher("org/lwjgl/opengl/GL11/meh", Equals)));
    }

    @Test
    void matchesBytesStartsWith() throws IOException {
        byte[] classBytes = stubClassBytes("org/lwjgl/opengl/GL11");
        ClassHeaderMetadata metadata = new ClassHeaderMetadata(classBytes);

        Assertions.assertFalse(metadata.matchesBytes(matcher("org/whatever", StartsWith)));
        Assertions.assertTrue(metadata.matchesBytes(matcher("org", StartsWith)));
        Assertions.assertFalse(metadata.matchesBytes(matcher("lwjgl", StartsWith)));
        Assertions.assertTrue(metadata.matchesBytes(matcher("org/lwjgl/", StartsWith)));
        Assertions.assertTrue(metadata.matchesBytes(matcher("org/lwjgl/opengl/GL11", StartsWith)));
        Assertions.assertFalse(metadata.matchesBytes(matcher("org/lwjgl/opengl/GL11/meh", StartsWith)));
    }

    private static BytePatternMatcher matcher(String str, BytePatternMatcher.Mode mode) {
        return new BytePatternMatcher(str, mode);
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
