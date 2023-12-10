
## A specification of the classes needed to reproduce LaunchWrapper's behaviours and APIs
This includes private APIs, because mods often use reflection to access hidden fields.

### License
This specification is based off launchwrapper's behaviour, and preserves the field naming&order due to the delicate compatibility needs of many modifications to the Minecraft game that rely on them heavily (e.g. by using Java reflection APIs).
However, it does not reproduce any significant code of the original, so hereby I license this specification under the MIT license:

```
Copyright (c) 2023 eigenraven

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Package outline
 - `net.minecraft.launchwrapper.injector.AlphaVanillaTweakInjector`: fake applet injector to run alpha versions
 - `net.minecraft.launchwrapper.injector.IndevVanillaTweakInjector`: asm patches to indev vanilla
 - `net.minecraft.launchwrapper.injector.VanillaTweakInjector`: asm patches to the vanilla game
 - `net.minecraft.launchwrapper.AlphaVanillaTweaker`: entrypoint for old minecraft alpha versions
 - `net.minecraft.launchwrapper.IClassNameTransformer`: interface for a String->String class **name** transformer
 - `net.minecraft.launchwrapper.IClassTransformer`: interface for a byte[]->byte[] class transformer
 - `net.minecraft.launchwrapper.IndevVanillaTweaker`: entrypoint for really old minecraft indev versions
 - `net.minecraft.launchwrapper.ITweaker`: describes an entrypoint, allowing injection of transformers
 - `net.minecraft.launchwrapper.Launch`: `main` entrypoint
 - `net.minecraft.launchwrapper.LaunchClassLoader`: the transforming class loader
 - `net.minecraft.launchwrapper.LogWrapper`: a bunch of simple log4j helper functions
 - `net.minecraft.launchwrapper.VanillaTweaker`: the vanilla game entrypoint in tweaker format

### Class specifications

#### net.minecraft.launchwrapper.IClassNameTransformer

```java
package net.minecraft.launchwrapper;
public interface IClassNameTransformer {
    /** Maps from live to in-jar class names to fetch the original class files */
    String unmapClassName(String name);
    /** Maps from live to "transformed" class names, passed to the class transformers and used as the loaded class name */
    String remapClassName(String name);
}
```

#### net.minecraft.launchwrapper.IClassTransformer

```java
package net.minecraft.launchwrapper;
public interface IClassTransformer {
    /** Given a class untransformed and transformed names, and optionally exisiting class bytes, returns the new class bytes. Either or both of the input and output bytes can be null to add/remove a class. */
    byte[] transform(String name, String transformedName, byte[] basicClass);
}
```

#### net.minecraft.launchwrapper.ITweaker

```java
package net.minecraft.launchwrapper;
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
```

#### net.minecraft.launchwrapper.Launch

```java
package net.minecraft.launchwrapper;

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

```

#### net.minecraft.launchwrapper.LaunchClassLoader

```java
package net.minecraft.launchwrapper;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;

public class LaunchClassLoader extends URLClassLoader {
    /** Internal IO buffer size */
    public static final int BUFFER_SIZE = 1 << 12;
    /** A list keeping track of addURL calls */
    private List<URL> sources;
    /** A reference to the classloader that loaded this class */
    private ClassLoader parent = getClass().getClassLoader();

    /** An ArrayList of all class transformers used, mutable, often modified via reflection */
    private List<IClassTransformer> transformers = new ArrayList<>(2);
    /** A ConcurrentHashMap cache of all classes loaded via this loader */
    private Map<String, Class<?>> cachedClasses = new ConcurrentHashMap<>();
    /** A HashSet (probably wrong, not thread safe) cache of class names that previously caused exceptions when attempting to load them */
    private Set<String> invalidClasses = new HashSet<>(1000);

    /** A HashSet of all class prefixes (e.g. "org.lwjgl.") to redirect to the parent classloader, often modified via reflection */
    private Set<String> classLoaderExceptions = new HashSet<>();
    /** A HashSet of all class prefixes (e.g. "org.objectweb.asm.") to NOT run class transformers on, often modified via reflection */
    private Set<String> transformerExceptions = new HashSet<>();
    /** An unused cache of package manifests, the field with a non-null CHM value needs to stay here due to reflective usage */
    private Map<Package, Manifest> packageManifests = new ConcurrentHashMap<>();
    /** A ConcurrentHashMap cache of class bytes loaded via this classloader */
    private Map<String, byte[]> resourceCache = new ConcurrentHashMap<>(1000);
    /** A concurrent cache of class bytes that previously caused exceptions when attempting to load them */
    private Set<String> negativeResourceCache = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** A transformer used to remap class names */
    private IClassNameTransformer renameTransformer;

    /** A dummy empty manifest field, used reflectively by some mods */
    private static final Manifest EMPTY = new Manifest();

    /** A utility IO buffer */
    private final ThreadLocal<byte[]> loadBuffer = new ThreadLocal<byte[]>();

    /** A list of filenames forbidden by Windows, <a href="https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file#naming-conventions">MSDN entry</a> */
    private static final String[] RESERVED_NAMES = {"CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"};

    /** A system property determining if additional debug output should be generated when loading classes */
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("legacy.debugClassLoading", "false"));
    /** A system property determining if additional debug output should be generated when loading classes on top of DEBUG */
    private static final boolean DEBUG_FINER = DEBUG && Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingFiner", "false"));
    /** A system property determining if post-transform class bytes should be dumped on top of DEBUG */
    private static final boolean DEBUG_SAVE = DEBUG && Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingSave", "false"));
    /** Folder where the class dumps are saved */
    private static File tempFolder = null;

    /**
     * <ol>
     *     <li>Constructs a parent-less super URLClassLoader</li>
     *     <li>Saves the given classpath to this.sources</li>
     *     <li>Adds class loader exclusions for:<ul>
     *         <li>java.</li>
     *         <li>sun.</li>
     *         <li>org.lwjgl.</li>
     *         <li>org.apache.logging.</li>
     *         <li>net.minecraft.launchwrapper.</li>
     *     </ul></li>
     *     <li>Adds transformer exclusions for:<ul>
     *         <li>javax.</li>
     *         <li>argo.</li>
     *         <li>org.objectweb.asm.</li>
     *         <li>com.google.common.</li>
     *         <li>org.bouncycastle.</li>
     *         <li>net.minecraft.launchwrapper.injector.</li>
     *     </ul></li>
     *     <li>If DEBUG_SAVE, finds a suitable directory to dump classes to, mkdirs it and saves it to tempFolder</li>
     * </ol>
     * 
     * @param sources The initial classpath
     */
    public LaunchClassLoader(URL[] sources) {
        // snip
    }

    /**
     * Reflectively constructs the given transformer, and adds it to the transformer list.
     * If the transformer is a IClassNameTransformer and one wasn't already registered, sets renameTransformer to it.
     * Catches any exceptions, and ignores them while logging a message.
     * @param transformerClassName A class name (like a.b.Cde) of the transformer.
     */
    public void registerTransformer(String transformerClassName) {
        // snip
    }

    /**
     * <ol>
     *     <li>If invalidClasses contains name, throw ClassNotFoundException</li>
     *     <li>If name starts with any class loader exception, forward to parent.loadClass</li>
     *     <li>If in cachedClasses, return the cached class</li>
     *     <li>If name starts with any transformer exception, super.findClass, put into cachedClasses and return. Catch&cache ClassNotFoundException.</li>
     *     <li>transformName, and check the cache again - if present, return from cache</li>
     *     <li>untransformName, find the last dot and use that to determine the package name and file path of the .class file</li>
     *     <li>Open a URLConnection using findCodeSourceConnectionFor on the determined filename</li>
     *     <li>Check package sealing for the given URLConnection, unless the untransformed name starts with "net.minecraft.", giving a severe warning if the package is already sealed. Otherwise, create and register a new Package.</li>
     *     <li>runTransformers on getClassBytes</li>
     *     <li>Save the debug class if enabled</li>
     *     <li>defineClass with the appropriate CodeSigners</li>
     *     <li>Cache&Return the class</li>
     *     <li>Throw a ClassNotFoundException if any exception happens during the transforming process</li>
     * </ol>
     */
    @Override
    public Class<?> findClass(final String name) throws ClassNotFoundException {
        // snip
    }

    /**
     * <ol>
     *     <li>If tempFolder is null, return immediately</li>
     *     <li>Generate an output path for a class org.my.Klass as tempFolder/org/my/Klass.class</li>
     *     <li>Make the directory if it doesn't already exist, delete the output file if it already exists</li>
     *     <li>Write data to the file, ignoring exceptions</li>
     * </ol>
     */
    private void saveTransformedClass(final byte[] data, final String transformedName) {
        // snip
    }

    /** A null-safe version of renameTransformer.unmapClassName(name) */
    private String untransformName(final String name) {
        // snip
    }

    /** A null-safe version of renameTransformer.remapClassName(name) */
    private String transformName(final String name) {
        // snip
    }

    /**
     * Checks the manifest path SEALED attribute, then checks the main attributes for the sealed property.
     * Returns if present and equal to "true" ignoring case.
     */
    private boolean isSealed(final String path, final Manifest manifest) {
        // snip
    }

    /**
     * Calls findResource, and if it's not null opens a connection to it, otherwise returns null.
     */
    private URLConnection findCodeSourceConnectionFor(final String name) {
        // snip
    }

    /**
     * <ol>
     *     <li>For each transformer on the transformer list, transform basicClass</li>
     *     <li>Return the updated basicClass</li>
     * </ol>
     */
    private byte[] runTransformers(final String name, final String transformedName, byte[] basicClass) {
        // snip
    }

    /**
     * Adds the given url to the classpath (via super) and the sources field.
     */
    @Override
    public void addURL(final URL url) {
        // snip
    }

    /** Returns the saved classpath list */
    public List<URL> getSources() {
        return sources;
    }

    /**
     * Tries to fully read the input stream into a byte array, using getOrCreateBuffer() as the IO buffer.
     * Returns an empty array and logs a warning if any exception happens.
     */
    private byte[] readFully(InputStream stream) {
        // snip
    }

    /**
     * A TLS helper for the loadBuffer field, creates a byte[BUFFER_SIZE] array if empty.
     */
    private byte[] getOrCreateBuffer() {
        // snip
    }

    /** Returns an unmodifiable view of the transformers list */
    public List<IClassTransformer> getTransformers() {
        return Collections.unmodifiableList(transformers);
    }

    /** Adds a new entry to the end of the class loader exclusions list */
    public void addClassLoaderExclusion(String toExclude) {
        classLoaderExceptions.add(toExclude);
    }

    /** Adds a new entry to the end of the transformer exclusions list */
    public void addTransformerExclusion(String toExclude) {
        transformerExceptions.add(toExclude);
    }

    /**
     * <ol>
     *     <li>If negativeResourceCache contains name, return null</li>
     *     <li>If resourceCache contains name, return the value</li>
     *     <li>If there is no '.' in the name and the name is one of the reserved names (ignore case), cache&return getClassBytes("_name")</li>
     *     <li>Find&load the resource with the path of name with . replaced with / and .class appended</li>
     *     <li>Cache&return the contents</li>
     *     <li>Silently close the stream regardless of any exceptions (but don't suppress exceptions)</li>
     * </ol>
     */
    public byte[] getClassBytes(String name) throws IOException {
        // snip
    }

    /** Null-safe, exception-safe close function that silently ignores any errors */
    private static void closeSilently(Closeable closeable) {
        // snip
    }

    /** Removes all of entriesToClear from negativeResourceCache */
    public void clearNegativeEntries(Set<String> entriesToClear) {
        negativeResourceCache.removeAll(entriesToClear);
    }
}
```

#### net.minecraft.launchwrapper.LogWrapper

```java
package net.minecraft.launchwrapper;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LogWrapper {
    /** Singleton instance */
    public static LogWrapper log = new LogWrapper();
    /** The actual log4j log */
    private Logger myLog;
    /** Guard for log configuration */
    private static boolean configured;

    /**
     * <ul>
     * <li> Initializes myLog with the LaunchWrapper log4j logger. </li>
     * <li> Sets the configured guard to true </li>
     * </ul>
     */
    private static void configureLogging() {
        // snip
    }

    /** Switches the output to a different Logger object */
    public static void retarget(Logger to) {
        log.myLog = to;
    }

    /**
     * <ul>
     * <li>Sets up a "logChannel" logger in log4j</li>
     * <li>Logs the given data using String.format as the formatter (for compat it's useful to detect curly brace specifiers and use message formats instead in that case)</li>
     * </ul>
     */
    public static void log(String logChannel, Level level, String format, Object... data) {
        // snip
    }

    /** Like above, but logs to myLog (and configures logging first if guard is false). */
    public static void log(Level level, String format, Object... data) {
        // snip
    }

    /** Like above, with a Throwable */
    public static void log(String logChannel, Level level, Throwable ex, String format, Object... data) {
        // snip
    }

    /** Like above, with a Throwable */
    public static void log(Level level, Throwable ex, String format, Object... data) {
        // snip
    }

    /** Trivial wrapper */
    public static void severe(String format, Object... data) {
        log(Level.ERROR, format, data);
    }

    /** Trivial wrapper */
    public static void warning(String format, Object... data) {
        log(Level.WARN, format, data);
    }

    /** Trivial wrapper */
    public static void info(String format, Object... data) {
        log(Level.INFO, format, data);
    }

    /** Trivial wrapper */
    public static void fine(String format, Object... data) {
        log(Level.DEBUG, format, data);
    }

    /** Trivial wrapper */
    public static void finer(String format, Object... data) {
        log(Level.TRACE, format, data);
    }

    /** Trivial wrapper */
    public static void finest(String format, Object... data) {
        log(Level.TRACE, format, data);
    }

    /** Ensures a given logger name is initialized (pointless?!) */
    public static void makeLog(String logChannel) {
        LogManager.getLogger(logChannel);
    }
}

```

#### net.minecraft.launchwrapper.VanillaTweaker

```java
package net.minecraft.launchwrapper;

import java.io.File;
import java.util.List;

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
```

#### net.minecraft.launchwrapper.injector.VanillaTweakInjector

```java
package net.minecraft.launchwrapper.injector;

public class VanillaTweakInjector implements IClassTransformer {
    /** empty */
    public VanillaTweakInjector() {}

    /**
     * <ol>
     *     <li>Only transform net.minecraft.client.Minecraft</li>
     *     <li>Find the main method</li>
     *     <li>Find the first static File-typed field, the working directory FieldNode</li>
     *     <li>Inject INVOKESTATIC net/minecraft/launchwrapper/injector/VanillaTweakInjector by insert()ing into main's instructions list</li>
     *     <li>Inject PUTSTATIC into the found field by insert()ing into main's instructions list</li>
     * </ol>
     */
    @Override
    public byte[] transform(final String name, final String transformedName, final byte[] bytes) {
        // snip
    }

    /**
     * <ol>
     *     <li>Turn off ImageIO disk caching</li>
     *     <li>Invoke loadIconsOnFrames</li>
     * </ol>
     * @return Launch.minecraftHome
     */
    public static File inject() {
        // snip
    }

    /**
     * Call lwjgl's Display.setIcon with icons loaded from "icons/icon_16x16.png" and "icons/icon_32x32.png" in the assets directory.
     * Also sets it for all AWT Frames, presumably for old mc versions.
     * Logs any exceptions without crashing.
     */
    public static void loadIconsOnFrames() {
        // snip
    }

    /**
     * A helper using BufferedImage to read iconFile, get its ARGB and put it into a bytebuffer in RGBA order
     */
    private static ByteBuffer loadIcon(final File iconFile) throws IOException {
        // snip
    }
}

```
