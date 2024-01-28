package com.gtnewhorizons.rfbplugins.compat;

import com.gtnewhorizons.retrofuturabootstrap.api.RetroFuturaBootstrap;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPlugin;
import com.gtnewhorizons.rfbplugins.compat.transformers.InterfaceMethodRefFixer;
import com.gtnewhorizons.rfbplugins.compat.transformers.UnsafeReflectionTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ModernJavaCompatibilityPlugin implements RfbPlugin {
    public ModernJavaCompatibilityPlugin() {}

    public static final Logger log = LogManager.getLogger("RFB-ModernJava");

    @Override
    public @NotNull RfbClassTransformer @Nullable [] makeTransformers() {
        if (RetroFuturaBootstrap.API.javaMajorVersion() < 9) {
            // Not needed for Java 8.
            return null;
        }
        return new RfbClassTransformer[] {
            new InterfaceMethodRefFixer(), new UnsafeReflectionTransformer(),
        };
    }
}
