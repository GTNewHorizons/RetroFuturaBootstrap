package net.minecraft.launchwrapper;

import com.gtnewhorizons.retrofuturabootstrap.Main;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

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
        if (!(Launch.class.getClassLoader() instanceof ExtensibleClassLoader)) {
            throw new UnsupportedOperationException(
                    "RetroFuturaBootstrap requires launching using com.gtnewhorizons.retrofuturabootstrap.Main");
        }
        new Launch().launch(args);
    }

    /** A reference to the asm-enabled class loader used by launchwrapper */
    public static LaunchClassLoader classLoader;
    /** Holds a reference to the original error stream, before it gets redirected to logs */
    private static final PrintStream originalSysErr = System.err;

    /** RFL: Standard blackboard key, an ArrayList(String) of tweakers, mutable */
    public static final String BLACKBOARD_TWEAK_CLASSES = "TweakClasses";
    /** RFL: Standard blackboard key, an ArrayList(String) of commandline arguments, mutable */
    public static final String BLACKBOARD_ARGUMENT_LIST = "ArgumentList";
    /** RFL: Standard blackboard key, an ArrayList(ITweaker) of the currently loaded tweakers */
    public static final String BLACKBOARD_TWEAKS = "Tweaks";

    /**
     * <ol>
     *     <li>Fetches the current classpath of this class's loader</li>
     *     <li>Constructs a new LaunchClassLoader using the scanned classpath, putting it in classLoader</li>
     *     <li>Sets the current thread's context classloader to the constructed LaunchClassLoader</li>
     *     <li>Initializes blackboard to an empty map</li>
     * </ol>
     */
    private Launch() {
        LogWrapper.configureLogging();
        blackboard = new HashMap<>();
        final ExtensibleClassLoader parentLoader =
                (ExtensibleClassLoader) getClass().getClassLoader();
        final LaunchClassLoader lcl =
                new LaunchClassLoader(parentLoader.asURLClassLoader().getURLs());
        classLoader = lcl;
        Thread.currentThread().setContextClassLoader(lcl);
    }

    /**
     * Hack for mixin line-number-based stacktrace detection, the line number of the realLaunch() call has to be less than 132.
     * See: <a href="https://github.com/LegacyModdingMC/UniMix/blob/bbd3c93bd0e1f5979dbeb983cc7f55e73a86e281/src/launchwrapper/java/org/spongepowered/asm/service/mojang/MixinServiceLaunchWrapper.java#L183-L185">org.spongepowered.asm.service.mojang.MixinServiceLaunchWrapper#getInitialPhase()</a>.
     */
    private void launch(String[] args) {
        realLaunch(args);
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
    private void realLaunch(String[] args) {
        final OptionParser parser = new OptionParser();
        final OptionSpec<String> aVersion =
                parser.accepts("version").withRequiredArg().ofType(String.class);
        final OptionSpec<File> aGameDir =
                parser.accepts("gameDir").withRequiredArg().ofType(File.class);
        final OptionSpec<File> aAssetsDir =
                parser.accepts("assetsDir").withRequiredArg().ofType(File.class);
        final OptionSpec<String> aTweakClass = parser.accepts("tweakClass")
                .withOptionalArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_TWEAK);
        final OptionSpec<String> aRemainder = parser.nonOptions().ofType(String.class);
        parser.allowsUnrecognizedOptions();

        final OptionSet options = parser.parse(args);
        final String version = options.valueOf(aVersion);
        final File gameDir = options.valueOf(aGameDir);
        final File assetsDir = options.valueOf(aAssetsDir);
        final List<String> tweakClasses = new ArrayList<>(options.valuesOf(aTweakClass));
        final List<String> remainingArgs = options.valuesOf(aRemainder);

        if ((Main.cfgDumpLoadedClasses || Main.cfgDumpLoadedClassesPerTransformer)
                && Main.classDumpDirectory.get() == null) {
            final Path gamePath = gameDir.toPath();
            final FileSystem fs = gamePath.getFileSystem();
            Path dumpPath = gamePath.resolve(Main.RFB_CLASS_DUMP_PREFIX);
            try {
                Files.createDirectory(dumpPath);
            } catch (FileAlreadyExistsException fae) {
                for (int i = 0; i < 1000; i++) {
                    dumpPath = gamePath.resolve(Main.RFB_CLASS_DUMP_PREFIX + "_" + i);
                    try {
                        Files.createDirectory(dumpPath);
                    } catch (FileAlreadyExistsException e) {
                        continue;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    break;
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Main.classDumpDirectory.set(dumpPath);
        }

        blackboard.put(BLACKBOARD_TWEAK_CLASSES, tweakClasses);
        final List<String> argumentList = new ArrayList<>();
        blackboard.put(BLACKBOARD_ARGUMENT_LIST, argumentList);
        final List<ITweaker> tweaks = new ArrayList<>();
        blackboard.put(BLACKBOARD_TWEAKS, tweaks);

        final Set<String> dedupTweakClasses = new HashSet<>();
        final List<ITweaker> allTweakers = new ArrayList<>();
        ITweaker firstTweaker = null;

        while (!tweakClasses.isEmpty()) {
            for (Iterator<String> iter = tweakClasses.iterator(); iter.hasNext(); ) {
                try {
                    final String tweakClass = iter.next();
                    if (dedupTweakClasses.contains(tweakClass)) {
                        LogWrapper.logger.warn("Duplicate tweaker class {}", tweakClass);
                        iter.remove();
                        continue;
                    }
                    dedupTweakClasses.add(tweakClass);
                    final int lastDot = tweakClass.lastIndexOf('.');
                    final String tweakPackagePrefix = (lastDot == -1) ? tweakClass : tweakClass.substring(0, lastDot);
                    classLoader.addClassLoaderExclusion(tweakPackagePrefix);

                    LogWrapper.logger.info("Constructing tweaker {}", tweakClass);
                    Class<?> tweakerClass = Class.forName(tweakClass, true, classLoader);
                    ITweaker tweaker = (ITweaker) tweakerClass.getConstructor().newInstance();
                    tweaks.add(tweaker);
                    iter.remove();
                    if (firstTweaker == null) {
                        firstTweaker = tweaker;
                    }
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            }

            for (Iterator<ITweaker> iter = tweaks.iterator(); iter.hasNext(); ) {
                final ITweaker tweaker = iter.next();
                LogWrapper.logger.info(
                        "Installing tweaker {}", tweaker.getClass().getName());
                tweaker.acceptOptions(argumentList, gameDir, assetsDir, version);
                tweaker.injectIntoClassLoader(classLoader);
                allTweakers.add(tweaker);
                iter.remove();
            }
        }

        for (final ITweaker tweaker : allTweakers) {
            Collections.addAll(argumentList, tweaker.getLaunchArguments());
        }

        argumentList.addAll(remainingArgs);

        try {
            final String launchTargetName =
                    Objects.requireNonNull(firstTweaker, "No tweaker supplied").getLaunchTarget();
            final Class<?> launchTarget = Class.forName(launchTargetName, false, classLoader);
            final Method mainM = launchTarget.getMethod("main", String[].class);
            mainM.invoke(null, (Object) argumentList.toArray(new String[0]));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
