package com.gtnewhorizons.retrofuturabootstrap.test;

import static org.junit.jupiter.api.Assertions.*;

import com.gtnewhorizons.retrofuturabootstrap.Main;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPluginMetadata;
import com.gtnewhorizons.retrofuturabootstrap.plugin.PluginSorter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class PluginDependencyTest {

    static final URI DUMMY_SOURCE = URI.create("file:dummy");
    static final URL DUMMY_URL;

    static {
        try {
            DUMMY_URL = DUMMY_SOURCE.toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private static RfbPluginMetadata.Builder buildSimple(String id) {
        return new RfbPluginMetadata.Builder(
                DUMMY_URL,
                DUMMY_SOURCE,
                id,
                id.toUpperCase(Locale.ROOT),
                "1.0.0",
                "plugins." + id.toUpperCase(Locale.ROOT));
    }

    static final RfbPluginMetadata SIMPLE_A = buildSimple("a").build();
    static final RfbPluginMetadata SIMPLE_B = buildSimple("b").build();
    static final RfbPluginMetadata SIMPLE_C = buildSimple("c").build();

    static final RfbPluginMetadata A_LAST = buildSimple("a").loadAfter("*").build();

    static final RfbPluginMetadata A_BEFORE_B = buildSimple("a").loadBefore("b").build();
    static final RfbPluginMetadata A_AFTER_B = buildSimple("a").loadAfter("b").build();
    static final RfbPluginMetadata B_BEFORE_A = buildSimple("b").loadBefore("a").build();

    static final RfbPluginMetadata A_BEFORE_B_REQUIRED =
            buildSimple("a").loadBefore("b").loadRequires("b").build();
    static final RfbPluginMetadata A_AFTER_B_REQUIRED =
            buildSimple("a").loadAfter("b").loadRequires("b").build();

    static final RfbPluginMetadata NEWMIXINS =
            buildSimple("newmixins").additionalVersion("mixin", "0.8.6").build();
    static final RfbPluginMetadata OLDMIXINS =
            buildSimple("oldmixins").additionalVersion("mixin", "0.8.1").build();
    static final RfbPluginMetadata AOLDMIXINS =
            buildSimple("aoldmixins").additionalVersion("mixin", "0.8.1").build();
    static final RfbPluginMetadata USEMIXINS = buildSimple("usemixins")
            .loadAfter("mixin")
            .loadRequires("mixin")
            .versionConstraint("mixin", "[0.8.5,)")
            .build();

    @SafeVarargs
    private static <T> T[] arr(T... els) {
        return Objects.requireNonNull(els);
    }

    private static void assertResolution(RfbPluginMetadata[] plugins, String[] sortedIds) {
        final String[] inputIds =
                Arrays.stream(plugins).map(RfbPluginMetadata::id).toArray(String[]::new);
        Main.logger.info("Resolving {}", (Object) inputIds);
        final Optional<List<RfbPluginMetadata>> resolved = new PluginSorter(Arrays.asList(plugins)).resolve();
        if (sortedIds != null) {
            assertTrue(resolved.isPresent());
            final String[] resolvedIds =
                    resolved.get().stream().map(RfbPluginMetadata::id).toArray(String[]::new);
            assertArrayEquals(sortedIds, resolvedIds);
        } else {
            assertFalse(resolved.isPresent());
        }
    }

    @Test
    void defaultsToAlphabetical() {
        assertResolution(arr(SIMPLE_A, SIMPLE_B), arr("a", "b"));
        assertResolution(arr(SIMPLE_B, SIMPLE_A), arr("a", "b"));
        assertResolution(arr(SIMPLE_A, SIMPLE_B, SIMPLE_C), arr("a", "b", "c"));
        assertResolution(arr(SIMPLE_A, SIMPLE_C, SIMPLE_B), arr("a", "b", "c"));
        assertResolution(arr(SIMPLE_B, SIMPLE_C, SIMPLE_A), arr("a", "b", "c"));
        assertResolution(arr(SIMPLE_B, SIMPLE_A, SIMPLE_C), arr("a", "b", "c"));
        assertResolution(arr(SIMPLE_C, SIMPLE_A, SIMPLE_B), arr("a", "b", "c"));
        assertResolution(arr(SIMPLE_C, SIMPLE_B, SIMPLE_A), arr("a", "b", "c"));
    }

    @Test
    void simpleDependencies() {
        assertResolution(arr(A_BEFORE_B, SIMPLE_B), arr("a", "b"));
        assertResolution(arr(SIMPLE_B, A_BEFORE_B), arr("a", "b"));
        assertResolution(arr(A_AFTER_B, SIMPLE_B), arr("b", "a"));
        assertResolution(arr(SIMPLE_B, A_AFTER_B), arr("b", "a"));
        // Allowed-missing dependency
        assertResolution(arr(A_BEFORE_B, SIMPLE_C), arr("a", "c"));
        assertResolution(arr(SIMPLE_C, A_BEFORE_B), arr("a", "c"));
        assertResolution(arr(A_AFTER_B, SIMPLE_C), arr("a", "c"));
        assertResolution(arr(SIMPLE_C, A_AFTER_B), arr("a", "c"));
        // Required dependency failure
        assertResolution(arr(A_BEFORE_B_REQUIRED, SIMPLE_B), arr("a", "b"));
        assertResolution(arr(SIMPLE_B, A_BEFORE_B_REQUIRED), arr("a", "b"));
        assertResolution(arr(A_AFTER_B_REQUIRED, SIMPLE_B), arr("b", "a"));
        assertResolution(arr(SIMPLE_B, A_AFTER_B_REQUIRED), arr("b", "a"));
        assertResolution(arr(A_BEFORE_B_REQUIRED, SIMPLE_C), null);
        assertResolution(arr(SIMPLE_C, A_BEFORE_B_REQUIRED), null);
        assertResolution(arr(A_AFTER_B_REQUIRED, SIMPLE_C), null);
        assertResolution(arr(SIMPLE_C, A_AFTER_B_REQUIRED), null);
    }

    @Test
    void pinLast() {
        assertResolution(arr(A_LAST, SIMPLE_B, SIMPLE_C), arr("b", "c", "a"));
        assertResolution(arr(A_LAST, NEWMIXINS, USEMIXINS), arr("newmixins", "usemixins", "a"));
    }

    @Test
    void newestWins() {
        assertResolution(arr(OLDMIXINS, NEWMIXINS), arr("newmixins"));
        assertResolution(arr(AOLDMIXINS, NEWMIXINS), arr("newmixins"));
    }

    @Test
    void versionRequirement() {
        assertResolution(arr(NEWMIXINS, USEMIXINS), arr("newmixins", "usemixins"));
        assertResolution(arr(OLDMIXINS, USEMIXINS), null);
        assertResolution(arr(AOLDMIXINS, USEMIXINS), null);
        assertResolution(arr(USEMIXINS), null);
    }

    @Test
    void cycle() {
        assertResolution(arr(A_BEFORE_B, B_BEFORE_A, SIMPLE_C), null);
    }
}
