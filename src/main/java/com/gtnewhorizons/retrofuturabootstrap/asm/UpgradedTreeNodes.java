package com.gtnewhorizons.retrofuturabootstrap.asm;

import com.gtnewhorizons.retrofuturabootstrap.api.RetroFuturaBootstrap;
import org.objectweb.asm.tree.ClassNode;

@SuppressWarnings("unused") // Used from ASM
public class UpgradedTreeNodes {
    public static final int NEWEST_ASM_VERSION = RetroFuturaBootstrap.API.newestAsmVersion();

    public static final java.lang.Class<?>[] ALL_NODES = new java.lang.Class[] {Class.class};

    public static class Class extends ClassNode {
        public Class() {
            super(NEWEST_ASM_VERSION);
        }

        public Class(int api) {
            super(NEWEST_ASM_VERSION);
        }

        @Override
        public void check(int api) {
            super.check(NEWEST_ASM_VERSION);
        }
    }
}
