package launchwrapper;

import java.io.File;
import java.util.List;
import net.minecraft.launchwrapper.LaunchClassLoader;

/** The launch wrapper plugin interface, implement to interact with the tweaker system. */
public interface ITweaker {
    /** Passes game options and paths into the tweaker during startup */
    void acceptOptions(List<String> args, File gameDir, final File assetsDir, String profile);
    /** Immediately after acceptOptions, lets the tweaker adjust the class loader */
    void injectIntoClassLoader(LaunchClassLoader classLoader);
    /** Which class's main method to call? */
    String getLaunchTarget();
    /** Arguments to append to the list previously constructed by tweakers before calling main() */
    String[] getLaunchArguments();
}
