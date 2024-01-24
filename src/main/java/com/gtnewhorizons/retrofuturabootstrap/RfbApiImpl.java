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
    public @NotNull ClassLoader originalSystemClassLoader() {
        return RfbSystemClassLoader.class.getClassLoader();
    }

    @Override
    public @NotNull RfbSystemClassLoader compatClassLoader() {
        if (Main.compatLoader == null) {
            throw new IllegalStateException();
        }
        return Main.compatLoader;
    }

    @Override
    public int javaMajorVersion() {
        return Main.JAVA_MAJOR_VERSION;
    }

    @Override
    public String javaVersion() {
        return Main.JAVA_VERSION;
    }
}
