package com.gtnewhorizons.retrofuturabootstrap.api;

import org.jetbrains.annotations.NotNull;

/**
 * Provides access to the RetroFuturaBootstrap API instance.
 */
public final class RetroFuturaBootstrap {
    public static final @NotNull RfbApi API = findApi();

    private static @NotNull RfbApi findApi() {
        try {
            Class<?> RFB_API_IMPL = Class.forName("com.gtnewhorizons.retrofuturabootstrap.RfbApiImpl");
            return (RfbApi) RFB_API_IMPL.getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
