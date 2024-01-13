package com.gtnewhorizons.retrofuturabootstrap.api;

import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/** A simple handle to a mutable ClassNode and flags for ClassWriter. */
public final class ClassNodeHandle {
    private final byte @Nullable [] originalBytes;
    private final int readerOptions;
    private boolean initialized = false;
    private @Nullable ClassNode node = null;
    private int writerFlags = 0;

    /** Parse the class data with no reader options (for fastest speed). */
    public ClassNodeHandle(byte @Nullable [] classData) {
        this.originalBytes = classData;
        this.readerOptions = 0;
    }

    /** Parse the class data with custom reader options. */
    public ClassNodeHandle(
            byte @Nullable [] classData, @MagicConstant(flagsFromClass = ClassReader.class) int readerOptions) {
        this.originalBytes = classData;
        this.readerOptions = readerOptions;
    }

    /** Gets the parsed node of the currently processed class. */
    public @Nullable ClassNode getNode() {
        ensureInitialized();
        return node;
    }

    /** Overwrites the parsed node of the currently processed class. */
    public void setNode(@Nullable ClassNode node) {
        initialized = true;
        this.node = node;
    }

    /** Computes the byte[] array of the transformed class. Returns the original bytes if {@link ClassNodeHandle#getNode()} was never called. */
    public byte @Nullable [] computeBytes() {
        if (!initialized) {
            return originalBytes;
        }
        if (node == null) {
            return null;
        }
        final ClassWriter writer = new ClassWriter(writerFlags);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Gets the ClassWriter flags for the current class. */
    public int getWriterFlags() {
        return writerFlags;
    }

    /** Set the ClassWriter flags for the current class. */
    public void setWriterFlags(@MagicConstant(flagsFromClass = ClassWriter.class) int flags) {
        this.writerFlags = flags;
    }

    /** Combine the currently set writer flags with the given flags using bitwise OR. */
    public void orWriterFlags(@MagicConstant(flagsFromClass = ClassWriter.class) int flags) {
        this.writerFlags |= flags;
    }

    /** Set ClassWriter.COMPUTE_MAXS on the writer flags. */
    public void computeMaxs() {
        this.writerFlags |= ClassWriter.COMPUTE_MAXS;
    }

    /** Set ClassWriter.COMPUTE_FRAMES on the writer flags. */
    public void computeFrames() {
        this.writerFlags |= ClassWriter.COMPUTE_FRAMES;
    }

    private void ensureInitialized() {
        if (!initialized) {
            if (originalBytes == null) {
                node = null;
            } else {
                node = new ClassNode();
                new ClassReader(originalBytes).accept(node, readerOptions);
            }
            initialized = true;
        }
    }
}
