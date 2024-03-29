// ASM: a very small and fast Java bytecode manipulation framework
// Copyright (c) 2000-2011 INRIA, France Telecom
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions
// are met:
// 1. Redistributions of source code must retain the above copyright
//    notice, this list of conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright
//    notice, this list of conditions and the following disclaimer in the
//    documentation and/or other materials provided with the distribution.
// 3. Neither the name of the copyright holders nor the names of its
//    contributors may be used to endorse or promote products derived from
//    this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
// THE POSSIBILITY OF SUCH DAMAGE.

package org.objectweb.asm.commons;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Opcodes;

/**
 * An {@link AnnotationVisitor} adapter for type remapping.
 *
 * @deprecated use {@link AnnotationRemapper} instead.
 * @author Eugene Kuleshov
 */
@Deprecated
public class RemappingAnnotationAdapter extends AnnotationVisitor {

    protected final Remapper remapper;

    public RemappingAnnotationAdapter(final AnnotationVisitor annotationVisitor, final Remapper remapper) {
        this(Opcodes.ASM9, annotationVisitor, remapper);
    }

    protected RemappingAnnotationAdapter(
            final int api, final AnnotationVisitor annotationVisitor, final Remapper remapper) {
        super(api, annotationVisitor);
        this.remapper = remapper;
    }

    @Override
    public void visit(final String name, final Object value) {
        av.visit(name, remapper.mapValue(value));
    }

    @Override
    public void visitEnum(final String name, final String descriptor, final String value) {
        av.visitEnum(name, remapper.mapDesc(descriptor), value);
    }

    @Override
    public AnnotationVisitor visitAnnotation(final String name, final String descriptor) {
        AnnotationVisitor annotationVisitor = av.visitAnnotation(name, remapper.mapDesc(descriptor));
        return annotationVisitor == null
                ? null
                : (annotationVisitor == av ? this : new RemappingAnnotationAdapter(annotationVisitor, remapper));
    }

    @Override
    public AnnotationVisitor visitArray(final String name) {
        AnnotationVisitor annotationVisitor = av.visitArray(name);
        return annotationVisitor == null
                ? null
                : (annotationVisitor == av ? this : new RemappingAnnotationAdapter(annotationVisitor, remapper));
    }
}
