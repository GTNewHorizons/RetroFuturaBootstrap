package com.gtnewhorizons.retrofuturabootstrap.preload;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Classes loaded during the previous startups, in the order and at the checkpoint they were first needed. */
final class PreloadList {

    private static final int FORMAT = 3;

    static final class Entry {

        static final int PENDING = 0, LOADED = 1, MISSING = 2, FAILED = 3;

        final String name;
        final int checkpoint;
        final double rank;
        volatile int status = PENDING;

        Entry(String name, int checkpoint, double rank) {
            this.name = name;
            this.checkpoint = checkpoint;
            this.rank = rank;
        }
    }

    /** A class LaunchClassLoader defined during this startup, in definition order. */
    static final class Define {

        final String name;
        final int checkpoint;
        final boolean byPreloader;

        Define(String name, int checkpoint, boolean byPreloader) {
            this.name = name;
            this.checkpoint = checkpoint;
            this.byPreloader = byPreloader;
        }
    }

    private PreloadList() {}

    static List<Entry> read(File file) {
        final List<Entry> entries = new ArrayList<>();
        if (!file.isFile()) return entries;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(new FileInputStream(file)), StandardCharsets.UTF_8))) {
            final String header = in.readLine();
            if (header == null || !header.equals("v" + FORMAT)) return entries;
            String line;
            while ((line = in.readLine()) != null) {
                final String[] parts = line.split(" ", 3);
                if (parts.length != 3) continue;
                entries.add(new Entry(parts[2], Integer.parseInt(parts[0]), Double.parseDouble(parts[1])));
            }
        } catch (IOException | RuntimeException e) {
            ClassPreloader.log().warn("Could not read {}, starting over", file, e);
            entries.clear();
        }
        return entries;
    }

    /**
     * Classes something else needed this time get the position they were needed at, measured against the old list so
     * both stay comparable. Old entries stay unless they no longer exist or fail to load: a preloaded class never shows
     * up as needed again, so there is no telling whether it still is.
     */
    static List<Entry> merge(List<Entry> previous, List<Define> defines) {
        final Map<String, Entry> old = new HashMap<>(previous.size() * 2);
        for (Entry e : previous) old.put(e.name, e);
        final Map<String, Entry> result = new HashMap<>(Math.max(16, (previous.size() + defines.size()) * 2));
        double position = -1;
        int positionCheckpoint = -1;
        int sinceKnown = 0;
        for (Define d : defines) {
            final Entry known = old.get(d.name);
            final boolean sameSpot = known != null && known.checkpoint == d.checkpoint;
            if (sameSpot) {
                position = known.rank;
                positionCheckpoint = d.checkpoint;
                sinceKnown = 0;
            } else if (d.checkpoint != positionCheckpoint) {
                position = -1;
                positionCheckpoint = d.checkpoint;
                sinceKnown = 0;
            }
            if (d.byPreloader || result.containsKey(d.name)) continue;
            final double rank = sameSpot ? known.rank : position + ++sinceKnown / 65536.0;
            result.put(d.name, new Entry(d.name, d.checkpoint, rank));
        }
        for (Entry e : previous) {
            if (e.status == Entry.MISSING || e.status == Entry.FAILED) continue;
            result.putIfAbsent(e.name, e);
        }
        final List<Entry> sorted = new ArrayList<>(result.values());
        sorted.sort(Comparator.<Entry>comparingInt(e -> e.checkpoint).thenComparingDouble(e -> e.rank));
        return sorted;
    }

    static void write(File file, List<Entry> entries) throws IOException {
        final File parent = file.getParentFile();
        if (parent != null) Files.createDirectories(parent.toPath());
        final File temp = new File(file.getPath() + ".tmp");
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(temp)), StandardCharsets.UTF_8))) {
            out.write("v" + FORMAT);
            out.newLine();
            int rank = 0;
            for (Entry e : entries) {
                out.write(e.checkpoint + " " + rank++ + " " + e.name);
                out.newLine();
            }
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
