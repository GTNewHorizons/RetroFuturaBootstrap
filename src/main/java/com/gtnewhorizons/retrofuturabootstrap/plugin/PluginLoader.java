package com.gtnewhorizons.retrofuturabootstrap.plugin;

import com.gtnewhorizons.retrofuturabootstrap.BuildConfig;
import com.gtnewhorizons.retrofuturabootstrap.Main;
import com.gtnewhorizons.retrofuturabootstrap.api.CompatibilityTransformerPlugin;
import com.gtnewhorizons.retrofuturabootstrap.api.CompatibilityTransformerPluginMetadata;
import com.gtnewhorizons.retrofuturabootstrap.api.PluginContext;
import com.gtnewhorizons.retrofuturabootstrap.api.SimpleClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.versioning.DefaultArtifactVersion;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/** Class to scan and load RFB {@link com.gtnewhorizons.retrofuturabootstrap.api.CompatibilityTransformerPlugin}s */
public final class PluginLoader {

    public static final String META_INF = "META-INF";
    public static final String RFB_PLUGINS_DIR = "rfb-plugin";
    private static final ArrayList<FileSystem> jarFilesystems = new ArrayList<>();
    // Metadata of plugins in loading order
    private static final ArrayList<CompatibilityTransformerPluginMetadata> loadedPluginMetadata = new ArrayList<>();
    private static final Map<String, CompatibilityTransformerPluginMetadata> loadedPluginMetadataById = new HashMap<>();
    private static final ArrayList<CompatibilityTransformerPlugin> loadedPlugins = new ArrayList<>();
    private static final Map<String, CompatibilityTransformerPlugin> loadedPluginsById = new HashMap<>();

    public static void initializePlugins() {
        final List<Path> pluginManifests = findPluginManifests();
        final List<CompatibilityTransformerPluginMetadata> pluginMetadata = new ArrayList<>(pluginManifests.size());
        for (final Path manifest : pluginManifests) {
            try {
                final String filename = manifest.getFileName().toString();
                final int dot = filename.lastIndexOf('.');
                final String id = filename.substring(0, dot);
                final Properties props = new Properties();
                try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
                final CompatibilityTransformerPluginMetadata meta =
                        new CompatibilityTransformerPluginMetadata(manifest.toUri(), id, props);
                pluginMetadata.add(meta);
            } catch (Exception e) {
                Main.logger.error("Invalid plugin manifest {}", manifest, e);
            }
        }
        pluginMetadata.add(makeRfbMetadata());
        pluginMetadata.add(makeJavaMetadata());

        final Optional<List<CompatibilityTransformerPluginMetadata>> sortedMetadata =
                new PluginSorter(pluginMetadata).resolve();
        if (!sortedMetadata.isPresent()) {
            throw new RuntimeException(
                    "There was a critical error during RFB plugin dependency resolution, check the log above for details.");
        }
        final List<CompatibilityTransformerPluginMetadata> sorted = sortedMetadata.get();
        loadedPluginMetadata.clear();
        loadedPluginMetadata.addAll(sorted);
        loadedPluginMetadataById.clear();
        for (CompatibilityTransformerPluginMetadata pluginMeta : sorted) {
            loadedPluginMetadataById.put(pluginMeta.id(), pluginMeta);
            for (CompatibilityTransformerPluginMetadata.IdAndVersion extraId : pluginMeta.additionalVersions()) {
                loadedPluginMetadataById.put(extraId.id(), pluginMeta);
            }
        }
        loadedPlugins.clear();
        loadedPluginsById.clear();
        final PluginContext loadingContext =
                new PluginContext(loadedPluginMetadata, loadedPlugins, loadedPluginMetadataById, loadedPluginsById);
        for (CompatibilityTransformerPluginMetadata pluginMeta : sorted) {
            final String className = pluginMeta.className();
            try {
                final Class<?> klass = Class.forName(className, true, Main.compatLoader);
                if (!CompatibilityTransformerPlugin.class.isAssignableFrom(klass)) {
                    throw new RuntimeException("Plugin class " + className
                            + " does not implement the required CompatibilityTransformerPlugin interface, source: "
                            + pluginMeta.source());
                }

                final CompatibilityTransformerPlugin plugin =
                        (CompatibilityTransformerPlugin) klass.getConstructor().newInstance();
                Main.logger.info(
                        "Constructed RFB plugin {} ({}): {} ({})",
                        pluginMeta.idAndVersion(),
                        pluginMeta.name(),
                        className,
                        pluginMeta.source());
                loadedPlugins.add(plugin);
                loadedPluginsById.put(pluginMeta.id(), plugin);
                for (CompatibilityTransformerPluginMetadata.IdAndVersion extraId : pluginMeta.additionalVersions()) {
                    loadedPluginsById.put(extraId.id(), plugin);
                }
                plugin.onConstruction(loadingContext);

                final SimpleClassTransformer[] earlyTransformers = plugin.getEarlyTransformers();
                if (earlyTransformers != null && earlyTransformers.length > 0) {
                    final List<SimpleClassTransformer> toAdd = Arrays.asList(earlyTransformers);
                    if (toAdd.stream().anyMatch(Objects::isNull)) {
                        Main.logger.error(
                                "RFB plugin {} ({}) provided a null early class transformer.",
                                pluginMeta.idAndVersion(),
                                pluginMeta.name());
                        throw new NullPointerException(
                                "Null early class transformer returned from RFB plugin " + pluginMeta.idAndVersion());
                    }
                    Main.mutateCompatibilityTransformers(list -> list.addAll(toAdd));
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

        closeJarFilesystems();
    }

    private static final URI myURI;

    static {
        try {
            myURI = Objects.requireNonNull(PluginLoader.class.getResource("PluginLoader.class"))
                    .toURI();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private static CompatibilityTransformerPluginMetadata makeJavaMetadata() {
        return new CompatibilityTransformerPluginMetadata(
                myURI,
                "java",
                "Java",
                new DefaultArtifactVersion(Main.JAVA_VERSION),
                new CompatibilityTransformerPluginMetadata.IdAndVersion[0],
                "com.gtnewhorizons.rfbplugins.compat.DummyJavaPlugin",
                new CompatibilityTransformerPluginMetadata.IdAndVersionRange[0],
                new String[0],
                new String[0],
                new String[0],
                new String[0],
                false);
    }

    private static CompatibilityTransformerPluginMetadata makeRfbMetadata() {
        return new CompatibilityTransformerPluginMetadata(
                myURI,
                "rfb",
                "RetroFuturaBootstrap",
                new DefaultArtifactVersion(BuildConfig.VERSION),
                new CompatibilityTransformerPluginMetadata.IdAndVersion[0],
                "com.gtnewhorizons.rfbplugins.compat.DummyRfbPlugin",
                new CompatibilityTransformerPluginMetadata.IdAndVersionRange[0],
                new String[0],
                new String[0],
                new String[0],
                new String[0],
                false);
    }

    private static List<Path> findPluginManifests() {
        final ArrayList<Path> manifestsFound = new ArrayList<>();
        final URL[] earlyCp = Main.compatLoader.getURLs();
        final HashSet<URI> urisToSearch = new HashSet<>(earlyCp.length);
        for (URL entry : earlyCp) {
            try {
                urisToSearch.add(entry.toURI());
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }
        final Path modsDir = Main.initialGameDir.toPath().resolve("mods");
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
        // TODO: cache
        for (URI uriToSearch : urisToSearch) {
            try {
                final boolean isJar =
                        uriToSearch.getPath().toLowerCase(Locale.ROOT).endsWith(".jar");
                if (isJar) {
                    uriToSearch = new URI("jar:" + uriToSearch + "!/");
                }
                count++;
                if (isJar) {
                    try {
                        jarFilesystems.add(
                                FileSystems.newFileSystem(uriToSearch, Collections.emptyMap(), Main.compatLoader));
                    } catch (FileSystemAlreadyExistsException e) {
                        /*ignore*/
                    }
                }
                final Path root = Paths.get(uriToSearch);
                if (!isJar && !Files.isDirectory(root)) {
                    Main.logger.warn("Skipping {} in RFB plugin search.", root);
                    continue;
                }
                final Path pluginsDir = root.resolve(META_INF).resolve(RFB_PLUGINS_DIR);
                if (!Files.isDirectory(pluginsDir)) {
                    continue;
                }
                Files.walkFileTree(
                        pluginsDir,
                        new HashSet<>(Collections.singletonList(FileVisitOption.FOLLOW_LINKS)),
                        1,
                        new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                Objects.requireNonNull(file);
                                Objects.requireNonNull(attrs);
                                if (file.getFileName()
                                        .toString()
                                        .toLowerCase(Locale.ROOT)
                                        .endsWith(".properties")) {
                                    manifestsFound.add(file);
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (Exception e) {
                Main.logger.warn("Could not scan path for RFB plugins: {}", uriToSearch, e);
            }
        }
        Main.logger.info("Successfully scanned {} paths for RFB plugins.", count);
        return manifestsFound;
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
