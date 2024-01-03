package com.gtnewhorizons.retrofuturabootstrap.api;

/**
 * Provides access to the RetroFuturaBootstrap API instance.
 */
public final class RetroFuturaBootstrap {
    public static final RfbApi API = findApi();

    private static RfbApi findApi() {
        try {
            Class<?> RFB_API_IMPL = Class.forName("com.gtnewhorizons.retrofuturabootstrap.RfbApiImpl");
            return (RfbApi) RFB_API_IMPL.getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
