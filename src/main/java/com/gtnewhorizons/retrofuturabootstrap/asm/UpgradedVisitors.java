package com.gtnewhorizons.retrofuturabootstrap.asm;

import com.gtnewhorizons.retrofuturabootstrap.api.RetroFuturaBootstrap;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.signature.SignatureVisitor;

@SuppressWarnings("unused") // used as ASM redirection target
public class UpgradedVisitors {
    public static final int NEWEST_ASM_VERSION = RetroFuturaBootstrap.API.newestAsmVersion();

    public static final java.lang.Class<?>[] ALL_VISITORS = new java.lang.Class[] {
        Annotation.class, Class.class, Field.class, Method.class, Module.class, RecordComponent.class, Signature.class,
    };

    public static class Annotation extends AnnotationVisitor {
        protected Annotation(int api) {
            super(NEWEST_ASM_VERSION);
        }

        protected Annotation(int api, AnnotationVisitor annotationVisitor) {
            super(NEWEST_ASM_VERSION, annotationVisitor);
        }
    }

    public static class Class extends ClassVisitor {
        protected Class(int api) {
            super(NEWEST_ASM_VERSION);
        }

        protected Class(int api, ClassVisitor classVisitor) {
            super(NEWEST_ASM_VERSION, classVisitor);
        }
    }

    public static class Field extends FieldVisitor {
        protected Field(int api) {
            super(NEWEST_ASM_VERSION);
        }

        protected Field(int api, FieldVisitor fieldVisitor) {
            super(NEWEST_ASM_VERSION, fieldVisitor);
        }
    }

    public static class Method extends MethodVisitor {
        private final int originalApi;

        protected Method(int api) {
            super(NEWEST_ASM_VERSION);
            originalApi = api;
        }

        protected Method(int api, MethodVisitor methodVisitor) {
            super(NEWEST_ASM_VERSION, methodVisitor);
            originalApi = api;
        }

        @Override
        @Deprecated
        @SuppressWarnings("deprecation")
        public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor) {
            int opcodeAndSource = opcode | (originalApi < Opcodes.ASM5 ? Opcodes.SOURCE_DEPRECATED : 0);
            visitMethodInsn(opcodeAndSource, owner, name, descriptor, opcode == Opcodes.INVOKEINTERFACE);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (originalApi < Opcodes.ASM5 && (opcode & Opcodes.SOURCE_DEPRECATED) == 0) {
                visitMethodInsn(opcode & ~Opcodes.SOURCE_MASK, owner, name, descriptor);
                return;
            }
            if (mv != null) {
                mv.visitMethodInsn(opcode & ~Opcodes.SOURCE_MASK, owner, name, descriptor, isInterface);
            }
        }
    }

    public static class Module extends ModuleVisitor {
        protected Module(int api) {
            super(NEWEST_ASM_VERSION);
        }

        protected Module(int api, ModuleVisitor moduleVisitor) {
            super(NEWEST_ASM_VERSION, moduleVisitor);
        }
    }

    public static class RecordComponent extends RecordComponentVisitor {
        protected RecordComponent(int api) {
            super(NEWEST_ASM_VERSION);
        }

        protected RecordComponent(int api, RecordComponentVisitor recordComponentVisitor) {
            super(NEWEST_ASM_VERSION, recordComponentVisitor);
        }
    }

    public static class Signature extends SignatureVisitor {
        protected Signature(int api) {
            super(NEWEST_ASM_VERSION);
        }
    }
}
