package com.gtnewhorizons.retrofuturabootstrap;

import com.gtnewhorizons.retrofuturabootstrap.api.RfbApi;
import org.jetbrains.annotations.NotNull;

public final class RfbApiImpl implements RfbApi {
    public static final RfbApiImpl INSTANCE = new RfbApiImpl();

    private RfbApiImpl() {}

    @Override
    public ClassLoader platformClassLoader() {
        return URLClassLoaderBase.getPlatformClassLoader();
    }

    @Override
    public @NotNull ClassLoader systemClassLoader() {
        return ClassLoader.getSystemClassLoader();
    }

    @Override
    public @NotNull SimpleTransformingClassLoader compatClassLoader() {
        return Main.compatLoader;
    }
}
