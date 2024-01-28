package com.gtnewhorizons.rfbplugins.compat;

import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPlugin;
import com.gtnewhorizons.rfbplugins.compat.transformers.AsmTypeTransformer;
import com.gtnewhorizons.rfbplugins.compat.transformers.SafeClassWriterTransformer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AsmSafetyPlugin implements RfbPlugin {
    public AsmSafetyPlugin() {}

    @Override
    public @NotNull RfbClassTransformer @Nullable [] makeTransformers() {
        return new RfbClassTransformer[] {new SafeClassWriterTransformer(), new AsmTypeTransformer()};
    }
}
