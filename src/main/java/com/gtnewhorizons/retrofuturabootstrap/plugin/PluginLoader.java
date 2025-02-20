package com.gtnewhorizons.retrofuturabootstrap.plugin;

import com.gtnewhorizons.retrofuturabootstrap.BuildConfig;
import com.gtnewhorizons.retrofuturabootstrap.Main;
import com.gtnewhorizons.retrofuturabootstrap.algorithm.StableTopologicalSort;
import com.gtnewhorizons.retrofuturabootstrap.api.PluginContext;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformerHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPlugin;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPluginHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPluginMetadata;
import com.gtnewhorizons.retrofuturabootstrap.versioning.DefaultArtifactVersion;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Class to scan and load RFB {@link RfbPlugin}s */
public final class PluginLoader {

    public static final String META_INF = "META-INF";
    public static final String RFB_PLUGINS_DIR = "rfb-plugin";
    private static final ArrayList<FileSystem> jarFilesystems = new ArrayList<>();
    // Metadata of plugins in loading order
    public static final ArrayList<RfbPluginMetadata> loadedPluginMetadata = new ArrayList<>();
    public static final Map<String, RfbPluginMetadata> loadedPluginMetadataById = new HashMap<>();
    public static final ArrayList<RfbPluginHandle> loadedPlugins = new ArrayList<>();
    public static final Map<String, RfbPluginHandle> loadedPluginsById = new HashMap<>();

    public static void initializePlugins() throws Throwable {
        final List<RfbPluginMetadata> pluginMetadata = findPluginManifests();

        pluginMetadata.add(makeRfbMetadata());
        pluginMetadata.add(makeJavaMetadata());

        final Optional<List<RfbPluginMetadata>> sortedMetadata = new PluginSorter(pluginMetadata).resolve();
        if (!sortedMetadata.isPresent()) {
            throw new RuntimeException(
                    "There was a critical error during RFB plugin dependency resolution, check the log above for details.");
        }
        final List<RfbPluginMetadata> sorted = sortedMetadata.get();
        loadedPluginMetadata.clear();
        loadedPluginMetadata.addAll(sorted);
        loadedPluginMetadataById.clear();
        for (final RfbPluginMetadata pluginMeta : sorted) {
            loadedPluginMetadataById.put(pluginMeta.id(), pluginMeta);
            for (RfbPluginMetadata.IdAndVersion extraId : pluginMeta.additionalVersions()) {
                loadedPluginMetadataById.put(extraId.id(), pluginMeta);
            }
            Main.addSilentClasspathUrl(pluginMeta.classpathEntry());
        }
        loadedPlugins.clear();
        loadedPluginsById.clear();
        final PluginContext loadingContext =
                new PluginContext(loadedPluginMetadata, loadedPlugins, loadedPluginMetadataById, loadedPluginsById);
        for (final RfbPluginMetadata pluginMeta : sorted) {
            final String className = pluginMeta.className();
            try {
                final Class<?> klass = Class.forName(className, true, Main.compatLoader);
                if (!RfbPlugin.class.isAssignableFrom(klass)) {
                    throw new RuntimeException("Plugin class " + className
                            + " does not implement the required RfbPlugin interface, source: "
                            + pluginMeta.source());
                }

                final RfbPlugin plugin = (RfbPlugin) klass.getConstructor().newInstance();
                final RfbPluginHandle handle = new RfbPluginHandle(pluginMeta, plugin);
                Main.logger.info(
                        "Constructed RFB plugin {} ({}): {} ({})",
                        pluginMeta.idAndVersion(),
                        pluginMeta.name(),
                        className,
                        pluginMeta.source());
                loadedPlugins.add(handle);
                loadedPluginsById.put(pluginMeta.id(), handle);
                for (RfbPluginMetadata.IdAndVersion extraId : pluginMeta.additionalVersions()) {
                    loadedPluginsById.put(extraId.id(), handle);
                }
                plugin.onConstruction(loadingContext);

                final RfbClassTransformer[] earlyTransformers = plugin.makeEarlyTransformers();
                if (earlyTransformers != null && earlyTransformers.length > 0) {
                    if (Arrays.stream(earlyTransformers).anyMatch(Objects::isNull)) {
                        Main.logger.fatal(
                                "RFB plugin {} ({}) provided a null early class transformer.",
                                pluginMeta.idAndVersion(),
                                pluginMeta.name());
                        throw new NullPointerException(
                                "Null early class transformer returned from RFB plugin " + pluginMeta.idAndVersion());
                    }
                    final List<RfbClassTransformerHandle> toAdd = Arrays.stream(earlyTransformers)
                            .map(xformer -> new RfbClassTransformerHandle(pluginMeta, plugin, xformer))
                            .collect(Collectors.toList());
                    Main.mutateRfbTransformers(list -> list.addAll(toAdd));
                    for (RfbClassTransformerHandle newlyRegistered : toAdd) {
                        handle.registerAdditionalTransformer(newlyRegistered);
                        newlyRegistered.transformer().onRegistration(Objects.requireNonNull(Main.compatLoader));
                        newlyRegistered.transformer().onRegistration(Objects.requireNonNull(Main.launchLoader));
                    }
                }

            } catch (ReflectiveOperationException e) {
                Throwable cause = e;
                if (e instanceof InvocationTargetException) {
                    cause = e.getCause();
                }
                throw new RuntimeException(
                        "Error constructing plugin " + className + ", source: " + pluginMeta.source(), cause);
            }
        }

        if (loadedPlugins.size() != loadedPluginMetadata.size()) {
            final String[] metaClasses = loadedPluginMetadata.stream()
                    .map(RfbPluginMetadata::className)
                    .toArray(String[]::new);
            final String[] loadClasses =
                    loadedPlugins.stream().map(p -> p.getClass().getName()).toArray(String[]::new);
            Main.logger.fatal(
                    "RFB loaded plugin and metadata array size mismatch.\nMetadata: {}\n  Loaded: {}",
                    Arrays.toString(metaClasses),
                    Arrays.toString(loadClasses));
            throw new IllegalStateException("Loaded plugin and metadata array size mismatch.");
        }

        closeJarFilesystems();

        // Ensure makeTransformers is only called once for each plugin.
        final IdentityHashMap<RfbPluginHandle, RfbClassTransformer[]> madeTransformersCache =
                new IdentityHashMap<>(loadedPlugins.size());

        // It is incredibly unlikely any thread would cause a retry in this compare-and-swap loop as we're still very
        // early in the loading phase, worst case this code will re-run a couple of times if that does happen.
        Main.mutateRfbTransformers(newTransformers -> {
            final List<RfbClassTransformerHandle> toRegister = new ArrayList<>();
            for (final RfbPluginHandle handle : loadedPlugins) {
                final RfbClassTransformer[] xformers = madeTransformersCache.computeIfAbsent(
                        handle, h -> h.plugin().makeTransformers());
                if (xformers == null || xformers.length < 1) {
                    continue;
                }
                for (final RfbClassTransformer xformer : xformers) {
                    if (xformer == null) {
                        throw new NullPointerException("Null transformer produced by RFB plugin "
                                + handle.metadata().id());
                    }
                    final RfbClassTransformerHandle xhandle =
                            new RfbClassTransformerHandle(handle.metadata(), handle.plugin(), xformer);
                    newTransformers.add(xhandle);
                    toRegister.add(xhandle);
                    handle.registerAdditionalTransformer(xhandle);
                }
            }
            final String[] emptyStrA = new String[0];
            final IdentityHashMap<RfbClassTransformerHandle, String[]> sortAfterLut = new IdentityHashMap<>();
            final IdentityHashMap<RfbClassTransformerHandle, String[]> sortBeforeLut = new IdentityHashMap<>();
            final IdentityHashMap<RfbClassTransformerHandle, Boolean> sortLastLut = new IdentityHashMap<>();
            for (final RfbClassTransformerHandle xhandle : newTransformers) {
                final RfbClassTransformer xformer = xhandle.transformer();
                String[] sortBefore = xformer.sortBefore();
                if (sortBefore == null) {
                    sortBefore = emptyStrA;
                }
                String[] sortAfter = xformer.sortAfter();
                if (sortAfter == null) {
                    sortAfter = emptyStrA;
                }
                boolean sortLast = Arrays.asList(sortAfter).contains("*");
                sortBeforeLut.put(xhandle, sortBefore);
                sortAfterLut.put(xhandle, sortAfter);
                sortLastLut.put(xhandle, sortLast);
            }
            // sort transformers

            {
                final Comparator<RfbClassTransformerHandle> initialSorter =
                        Comparator.<RfbClassTransformerHandle, Boolean>comparing(sortLastLut::get)
                                .thenComparing(RfbClassTransformerHandle::id);
                newTransformers.sort(initialSorter);
                final List<List<Integer>> edges = new ArrayList<>(newTransformers.size());
                final Map<String, Integer> idLookup = new HashMap<>();
                for (int i = 0; i < newTransformers.size(); i++) {
                    edges.add(new ArrayList<>(0));
                    final RfbClassTransformerHandle newTransformer = newTransformers.get(i);
                    idLookup.put(newTransformer.id(), i);
                    for (String additionalId : newTransformer.additionalIds()) {
                        idLookup.put(additionalId, i);
                    }
                }
                for (int i = 0; i < newTransformers.size(); i++) {
                    final RfbClassTransformerHandle handle = newTransformers.get(i);
                    final String[] before = sortBeforeLut.get(handle);
                    final String[] after = sortAfterLut.get(handle);
                    for (String dep : before) {
                        final Integer depIdx = idLookup.get(dep);
                        if (depIdx != null) {
                            edges.get(i).add(depIdx);
                        }
                    }
                    for (String dep : after) {
                        final Integer depIdx = idLookup.get(dep);
                        if (depIdx != null) {
                            edges.get(depIdx).add(i);
                        }
                    }
                }
                try {
                    final List<RfbClassTransformerHandle> toposorted =
                            StableTopologicalSort.sort(newTransformers, edges);
                    newTransformers.clear();
                    newTransformers.addAll(toposorted);
                } catch (StableTopologicalSort.CycleException err) {
                    final Set<RfbClassTransformerHandle> cycle = err.cyclicElements(RfbClassTransformerHandle.class);
                    Main.logger.error("Cycle found among the following RFB class transformers, aborting launch:");
                    for (final RfbClassTransformerHandle xformer : cycle) {
                        Main.logger.error(
                                "{} ({})",
                                xformer.id(),
                                xformer.pluginMetadata().idAndVersion());
                    }
                    throw new RuntimeException("Cycle among RFB transformer sorting constraints.");
                }

                for (RfbClassTransformerHandle newlyRegistered : toRegister) {
                    newlyRegistered.transformer().onRegistration(Objects.requireNonNull(Main.compatLoader));
                    newlyRegistered.transformer().onRegistration(Objects.requireNonNull(Main.launchLoader));
                }
            }
        });
    }

    private static final URI myURI;
    private static final URL myJar;

    static {
        try {
            myURI = Objects.requireNonNull(PluginLoader.class.getResource("PluginLoader.class"))
                    .toURI();
            myJar = PluginLoader.class.getProtectionDomain().getCodeSource().getLocation();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private static RfbPluginMetadata makeJavaMetadata() {
        return new RfbPluginMetadata(
                myJar,
                myURI,
                "java",
                "Java",
                new DefaultArtifactVersion(Main.JAVA_VERSION),
                new RfbPluginMetadata.IdAndVersion[0],
                "com.gtnewhorizons.rfbplugins.compat.DummyJavaPlugin",
                new RfbPluginMetadata.IdAndVersionRange[0],
                new String[0],
                new String[0],
                new String[0],
                new String[0],
                false);
    }

    private static RfbPluginMetadata makeRfbMetadata() {
        return new RfbPluginMetadata(
                myJar,
                myURI,
                "rfb",
                "RetroFuturaBootstrap",
                new DefaultArtifactVersion(BuildConfig.VERSION),
                new RfbPluginMetadata.IdAndVersion[0],
                "com.gtnewhorizons.rfbplugins.compat.DummyRfbPlugin",
                new RfbPluginMetadata.IdAndVersionRange[0],
                new String[0],
                new String[0],
                new String[0],
                new String[0],
                false);
    }

    private static List<RfbPluginMetadata> findPluginManifests() {
        final URL[] earlyCp = Main.compatLoader.getURLs();
        final HashSet<URI> urisToSearch = new HashSet<>(earlyCp.length);
        for (URL entry : earlyCp) {
            try {
                urisToSearch.add(entry.toURI());
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }
        final File initialGameDir = Main.initialGameDir;
        final Path gamePath = initialGameDir != null
                ? initialGameDir.toPath()
                : Paths.get(".").toAbsolutePath();
        final Path modsDir = gamePath.resolve("mods");
        if (Files.isDirectory(modsDir)) {
            try {
                Files.walkFileTree(
                        modsDir,
                        new HashSet<>(Collections.singletonList(FileVisitOption.FOLLOW_LINKS)),
                        256,
                        new SimpleFileVisitor<Path>() {

                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                Objects.requireNonNull(file);
                                Objects.requireNonNull(attrs);
                                if (file.getFileName()
                                        .toString()
                                        .toLowerCase(Locale.ROOT)
                                        .endsWith(".jar")) {
                                    urisToSearch.add(file.toUri());
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        int count = 0;
        final List<RfbPluginMetadata> pluginMetadata = new ArrayList<>();
        // TODO: cache
        final String zipPrefix = META_INF + "/" + RFB_PLUGINS_DIR + "/";
        for (final URI uriToSearch : urisToSearch) {
            try {
                final boolean isJar =
                        uriToSearch.getPath().toLowerCase(Locale.ROOT).endsWith(".jar");
                count++;
                final Path root = Paths.get(uriToSearch);
                if (!isJar && !Files.isDirectory(root)) {
                    Main.logger.warn("Skipping {} in RFB plugin search.", root);
                    continue;
                }
                if (isJar) {
                    try (final ZipFile zip = new ZipFile(root.toFile(), ZipFile.OPEN_READ, StandardCharsets.UTF_8)) {
                        for (final Enumeration<? extends ZipEntry> enm = zip.entries(); enm.hasMoreElements(); ) {
                            final ZipEntry ze = enm.nextElement();
                            if (ze.isDirectory() || !ze.getName().startsWith(zipPrefix)) {
                                continue;
                            }
                            final URI uri = new URI("jar:" + root.toUri() + "!" + ze.getName());
                            final String filename = ze.getName().replace(zipPrefix, "");
                            if (filename.contains("/")) {
                                continue;
                            }
                            try (final InputStream is = zip.getInputStream(ze);
                                    final Reader rdr = new InputStreamReader(is, StandardCharsets.UTF_8);
                                    final BufferedReader bufReader = new BufferedReader(rdr)) {
                                pluginMetadata.add(parseMetadata(uriToSearch.toURL(), uri, filename, bufReader));
                            } catch (Exception e) {
                                Main.logger.error("Skipping invalid plugin manifest {}", uri, e);
                            }
                        }
                    } catch (Exception e) {
                        Main.logger.error("Error while parsing plugin manifests from jar file: " + root, e);
                    }
                } else {
                    final Path pluginsDir = root.resolve(META_INF).resolve(RFB_PLUGINS_DIR);
                    if (!Files.isDirectory(pluginsDir)) {
                        continue;
                    }
                    Files.walkFileTree(
                            pluginsDir,
                            new HashSet<>(Collections.singletonList(FileVisitOption.FOLLOW_LINKS)),
                            2,
                            new SimpleFileVisitor<Path>() {

                                @Override
                                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                        throws IOException {
                                    Objects.requireNonNull(file);
                                    Objects.requireNonNull(attrs);
                                    if (file.getFileName()
                                            .toString()
                                            .toLowerCase(Locale.ROOT)
                                            .endsWith(".properties")) {
                                        try (BufferedReader reader =
                                                Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                                            pluginMetadata.add(parseMetadata(
                                                    uriToSearch.toURL(),
                                                    file.toUri(),
                                                    file.getFileName().toString(),
                                                    reader));
                                        } catch (Exception e) {
                                            Main.logger.error("Skipping invalid plugin manifest {}", file, e);
                                        }
                                    }
                                    return FileVisitResult.CONTINUE;
                                }
                            });
                }
            } catch (Exception e) {
                Main.logger.warn("Could not scan path for RFB plugins: {}", uriToSearch, e);
            }
        }
        Main.logger.info("Successfully scanned {} paths for RFB plugins.", count);

        return pluginMetadata;
    }

    private static RfbPluginMetadata parseMetadata(
            URL classpathEntry, URI source, String filename, BufferedReader contents) throws IOException {
        final int dot = filename.lastIndexOf('.');
        final String id = filename.substring(0, dot);
        final Properties props = new Properties();
        props.load(contents);
        return new RfbPluginMetadata(classpathEntry, source, id, props);
    }

    private static void closeJarFilesystems() {
        for (FileSystem fs : jarFilesystems) {
            try {
                fs.close();
            } catch (Exception e) {
                /*ignored*/
            }
        }
        jarFilesystems.clear();
        jarFilesystems.trimToSize();
    }
}
