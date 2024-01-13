package com.gtnewhorizons.retrofuturabootstrap;

import com.gtnewhorizons.retrofuturabootstrap.api.RfbApi;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

public final class RfbApiImpl implements RfbApi {
    public static final RfbApiImpl INSTANCE = new RfbApiImpl();

    private RfbApiImpl() {}

    @Override
    public ClassLoader platformClassLoader() {
        return URLClassLoaderBase.getPlatformClassLoader();
    }

    @Override
    public ClassLoader systemClassLoader() {
        return ClassLoader.getSystemClassLoader();
    }

    @Override
    public SimpleTransformingClassLoader compatClassLoader() {
        return Main.compatLoader;
    }

    @Override
    public LaunchClassLoader launchClassLoader() {
        return Launch.classLoader;
    }
}
