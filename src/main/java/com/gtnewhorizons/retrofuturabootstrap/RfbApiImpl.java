package com.gtnewhorizons.retrofuturabootstrap;

import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbApi;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPluginHandle;
import com.gtnewhorizons.retrofuturabootstrap.plugin.PluginLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

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
    public @NotNull ExtensibleClassLoader launchClassLoader() {
        if (Main.launchLoader == null) {
            throw new IllegalStateException();
        }
        return Main.launchLoader;
    }

    @Override
    public @Nullable RfbPluginHandle findPluginById(@NotNull String id) {
        return PluginLoader.loadedPluginsById.get(id);
    }

    @Override
    public @NotNull List<RfbPluginHandle> getLoadedPlugins() {
        return Collections.unmodifiableList(PluginLoader.loadedPlugins);
    }

    @Override
    public int newestAsmVersion() {
        return Opcodes.ASM9;
    }

    @Override
    public int javaMajorVersion() {
        return Main.JAVA_MAJOR_VERSION;
    }

    @Override
    public @NotNull String javaVersion() {
        return Main.JAVA_VERSION;
    }

    @Override
    public @NotNull Path gameDirectory() {
        return Main.initialGameDir == null ? Paths.get(".") : Main.initialGameDir.toPath();
    }

    @Override
    public @NotNull Path assetsDirectory() {
        return Main.initialAssetsDir == null ? Paths.get(".") : Main.initialAssetsDir.toPath();
    }

    @Override
    public long currentPid() {
        return URLClassLoaderBase.getCurrentPid();
    }
}
