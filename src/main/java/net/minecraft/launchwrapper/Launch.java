package net.minecraft.launchwrapper;

import java.io.File;
import java.io.PrintStream;
import java.util.Map;

public class Launch {
    /** Default tweaker to launch with when no override is specified on the command line */
    private static final String DEFAULT_TWEAK = "net.minecraft.launchwrapper.VanillaTweaker";
    /** Game root directory (.minecraft) path, set from commandline options */
    public static File minecraftHome;
    /** Assets directory, set from commandline options */
    public static File assetsDir;
    /** A shared map of a variety of options, such as:
     * <ul>
     *     <li>TweakClasses: an ArrayList(String) of tweakers, mutable</li>
     *     <li>ArgumentList: an ArrayList(String) of commandline arguments, mutable</li>
     *     <li>Tweaks: an ArrayList(ITweaker) of the currently loaded tweakers</li>
     *     <li>others set by FML/Forge</li>
     * </ul>
     */
    public static Map<String, Object> blackboard;

    /** The actual main() invoked by the game launcher */
    public static void main(String[] args) {
        new Launch().launch(args);
    }

    /** A reference to the asm-enabled class loader used by launchwrapper */
    public static LaunchClassLoader classLoader;
    /** Holds a reference to the original error stream, before it gets redirected to logs */
    private static final PrintStream originalSysErr = System.err;

    /**
     * <ol>
     *     <li>Fetches the current classpath of this class's loader</li>
     *     <li>Constructs a new LaunchClassLoader using the scanned classpath, putting it in classLoader</li>
     *     <li>Sets the current thread's context classloader to the constructed LaunchClassLoader</li>
     *     <li>Initializes blackboard to an empty map</li>
     * </ol>
     */
    private Launch() {
        // snip
    }

    /**
     * <ol>
     *     <li>Uses joptsimple to parse game options: <ul>
     *         <li>version - the game version (profile), required, String</li>
     *         <li>gameDir - alternative game directory, required, File</li>
     *         <li>assetsDir - assets directory, required, File</li>
     *         <li>tweakClass - tweak class(es) to load, defaults to DEFAULT_TWEAK, ArrayList(String)</li>
     *         <li>passes through other options</li>
     *     </ul></li>
     *     <li>Puts tweak class names from tweakClass into blackboard TweakClasses</li>
     *     <li>Initializes ArgumentList in the blackboard, later putting in arguments into it</li>
     *     <li>Initializes Tweaks in the blackboard</li>
     *     <li>While TweakClasses is not empty:<ol>
     *         <li>For each TweakClasses element:<ol>
     *             <li>Remove&Skip duplicates using a set, with a warning log</li>
     *             <li>Add a classloader exclusion for the tweaker's package</li>
     *             <li>Construct the tweaker using the ()V constructor</li>
     *             <li>Add the tweaker to Tweaks</li>
     *             <li>Remove the TweakClass element</li>
     *             <li>Save the first tweaker constructed to a variable</li>
     *         </ol></li>
     *         <li>For each ITweaker just instantiated: <ol>
     *             <li>Call acceptOptions</li>
     *             <li>Call injectIntoClassLoader</li>
     *             <li>Save to a list of all tweakers</li>
     *             <li>Remove from Tweaks</li>
     *         </ol></li>
     *     </ol></li>
     *     <li>For each tweaker in the list of all tweakers registered:<ol>
     *         <li>Append getLaunchArguments() to the argument list</li>
     *     </ol></li>
     *     <li>getLaunchTarget() from the first tweaker registered</li>
     *     <li>Load the launch target class without initializing statics</li>
     *     <li>Invoke main with the constructed argument list</li>
     * </ol>
     *
     * @param args commandline arguments
     */
    private void launch(String[] args) {
        // snip
    }
}
