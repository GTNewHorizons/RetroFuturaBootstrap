package net.minecraft.launchwrapper;

import java.io.File;
import java.util.List;
import launchwrapper.ITweaker;

public class VanillaTweaker implements ITweaker {
    /** Cached arguments */
    private List<String> args;

    /** Caches the arguments list */
    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        this.args = args;
    }

    /** Registers the net.minecraft.launchwrapper.injector.VanillaTweakInjector transformer */
    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        // snip
        throw new UnsupportedOperationException("NYI, TODO");
    }

    /** The Minecraft main class */
    @Override
    public String getLaunchTarget() {
        return "net.minecraft.client.Minecraft";
    }

    /** Forwards a copy of the cached arguments */
    @Override
    public String[] getLaunchArguments() {
        return args.toArray(new String[0]);
    }
}
