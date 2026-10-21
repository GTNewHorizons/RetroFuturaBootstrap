package com.gtnewhorizons.rfbplugins.compat;

import com.gtnewhorizons.retrofuturabootstrap.SharedConfig;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPlugin;
import com.gtnewhorizons.retrofuturabootstrap.concurrency.ConcurrentTransformersTransformer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Thread safety fixes for class transformers of other mods, only needed with rfb.parallelClassLoading on. */
public class ConcurrentTransformersPlugin implements RfbPlugin {
    public ConcurrentTransformersPlugin() {}

    @Override
    public @NotNull RfbClassTransformer @Nullable [] makeTransformers() {
        if (!SharedConfig.cfgParallelClassLoading) {
            return null;
        }
        return new RfbClassTransformer[] {new ConcurrentTransformersTransformer()};
    }
}
