package com.gtnewhorizons.retrofuturabootstrap;

import com.gtnewhorizons.retrofuturabootstrap.api.CompatibilityTransformerPluginMetadata;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/** Class to scan and load RFB {@link com.gtnewhorizons.retrofuturabootstrap.api.CompatibilityTransformerPlugin}s */
public final class PluginLoader {

    public static final String META_INF = "META-INF";
    public static final String RFB_PLUGINS_DIR = "rfb-plugin";
    private static final ArrayList<FileSystem> jarFilesystems = new ArrayList<>();

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
                        new CompatibilityTransformerPluginMetadata(id, props);
                pluginMetadata.add(meta);
            } catch (Exception e) {
                Main.logger.error("Invalid plugin manifest {}", manifest, e);
            }
        }
        for (CompatibilityTransformerPluginMetadata meta : pluginMetadata) {
            Main.logger.warn("{}", meta);
        }

        closeJarFilesystems();
    }

    private static List<Path> findPluginManifests() {
        final ArrayList<Path> manifestsFound = new ArrayList<>();
        final HashSet<URL> urlsToSearch = new HashSet<>(Arrays.asList(Main.compatLoader.getURLs()));
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
                                    urlsToSearch.add(file.toUri().toURL());
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
        for (URL urlToSearch : urlsToSearch) {
            try {
                final boolean isJar =
                        urlToSearch.getPath().toLowerCase(Locale.ROOT).endsWith(".jar");
                if (isJar) {
                    urlToSearch = new URL("jar:" + urlToSearch + "!/");
                }
                count++;
                final URI uriToSearch = urlToSearch.toURI();
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
                Main.logger.info("{}", pluginsDir);
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
                                Main.logger.info("--> {}", file);
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
                Main.logger.warn("Could not scan path for RFB plugins: {}", urlToSearch, e);
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
