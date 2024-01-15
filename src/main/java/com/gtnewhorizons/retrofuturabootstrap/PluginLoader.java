package com.gtnewhorizons.retrofuturabootstrap;

import java.io.IOException;
import java.net.URL;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Class to scan and load RFB {@link com.gtnewhorizons.retrofuturabootstrap.api.CompatibilityTransformerPlugin}s */
public final class PluginLoader {

    public static final String META_INF = "META-INF";
    public static final String RFB_PLUGINS_DIR = "rfb-plugin";

    public static void initializePlugins() {
        List<Path> pluginManifests = findPluginManifests();
    }

    public static List<Path> findPluginManifests() {
        ArrayList<Path> manifestsFound = new ArrayList<>();
        HashSet<URL> urlsToSearch = new HashSet<>(Arrays.asList(Main.compatLoader.getURLs()));
        Path modsDir = Main.initialGameDir.toPath().resolve("mods");
        if (Files.isDirectory(modsDir)) {
            try {
                Files.walkFileTree(
                        modsDir,
                        new HashSet<>(Arrays.asList(FileVisitOption.FOLLOW_LINKS)),
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
        for (URL urlToSearch : urlsToSearch) {
            // Main.logger.warn("RFB Searching for plugins: {}", urlToSearch);
        }
        return new ArrayList<>();
    }
}
