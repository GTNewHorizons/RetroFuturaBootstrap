package com.gtnewhorizons.rfbplugins.compat;

import com.gtnewhorizons.retrofuturabootstrap.api.RetroFuturaBootstrap;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ModernJavaCompatibilityPlugin implements RfbPlugin {
    public ModernJavaCompatibilityPlugin() {}

    @Override
    public @NotNull RfbClassTransformer @Nullable [] makeTransformers() {
        if (RetroFuturaBootstrap.API.javaMajorVersion() < 9) {
            // Not needed for Java 8.
            return null;
        }
        return new RfbClassTransformer[] {

        };
    }
}
