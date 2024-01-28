package com.gtnewhorizons.retrofuturabootstrap.asm;

import com.gtnewhorizons.retrofuturabootstrap.api.RetroFuturaBootstrap;
import org.objectweb.asm.tree.ClassNode;

@SuppressWarnings("unused") // Used from ASM
public class UpgradedTreeNodes {
    public static final int NEWEST_ASM_VERSION = RetroFuturaBootstrap.API.newestAsmVersion();

    public static final java.lang.Class<?>[] ALL_NODES = new java.lang.Class[] {Class.class};

    public static final class Class extends ClassNode {
        public Class() {
            super();
        }

        public Class(int api) {
            super(NEWEST_ASM_VERSION);
        }
    }
}
