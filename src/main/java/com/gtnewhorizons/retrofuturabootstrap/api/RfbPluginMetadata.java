package com.gtnewhorizons.retrofuturabootstrap.api;

import com.gtnewhorizons.retrofuturabootstrap.versioning.ArtifactVersion;
import com.gtnewhorizons.retrofuturabootstrap.versioning.DefaultArtifactVersion;
import com.gtnewhorizons.retrofuturabootstrap.versioning.InvalidVersionSpecificationException;
import com.gtnewhorizons.retrofuturabootstrap.versioning.VersionRange;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

public final class RfbPluginMetadata implements Comparable<RfbPluginMetadata> {

    public static final Pattern ID_VALIDATOR = Pattern.compile("[a-z0-9-]+");
    public static final Comparator<? super RfbPluginMetadata> ID_COMPARATOR =
            Comparator.nullsFirst(Comparator.comparing(RfbPluginMetadata::id));
    public static final Comparator<? super RfbPluginMetadata> ID_AND_PIN_COMPARATOR = Comparator.nullsFirst(
            Comparator.comparing(RfbPluginMetadata::pinLast).thenComparing(RfbPluginMetadata::id));

    private final @NotNull URL classpathEntry;
    private final @NotNull URI source;
    private final @NotNull IdAndVersion idAndVersion;
    private final @NotNull String name;
    private final @NotNull IdAndVersion[] additionalVersions;
    private final @NotNull IdAndVersionRange[] versionConstraints;
    private final @NotNull String[] transformerExclusions;
    private final @NotNull String[] loadBefore;
    private final @NotNull String[] loadAfter;
    private final @NotNull String[] loadRequires;
    private final boolean pinLast;
    private final @NotNull String className;
    private @Nullable RfbPlugin instance;

    public RfbPluginMetadata(
            @NotNull URL classpathEntry,
            @NotNull URI source,
            @NotNull String id,
            @NotNull String name,
            @NotNull ArtifactVersion version,
            IdAndVersion[] additionalVersions,
            @NotNull String className,
            IdAndVersionRange[] versionConstraints,
            String[] transformerExclusions,
            String[] loadBefore,
            String[] loadAfter,
            String[] loadRequires,
            boolean pinLast) {
        this.classpathEntry = Objects.requireNonNull(classpathEntry);
        this.source = Objects.requireNonNull(source);
        Objects.requireNonNull(id);
        Objects.requireNonNull(version);
        this.idAndVersion = new IdAndVersion(id, version);
        this.name = Objects.requireNonNull(name);
        this.additionalVersions = additionalVersions == null ? new IdAndVersion[0] : additionalVersions;
        this.className = Objects.requireNonNull(className);
        this.versionConstraints = versionConstraints == null ? new IdAndVersionRange[0] : versionConstraints;
        this.transformerExclusions = transformerExclusions == null ? new String[0] : transformerExclusions;
        this.loadBefore = loadBefore == null ? new String[0] : loadBefore;
        this.loadAfter = loadAfter == null ? new String[0] : loadAfter;
        this.loadRequires = loadRequires == null ? new String[0] : loadRequires;
        this.pinLast = pinLast;
    }

    public RfbPluginMetadata(@NotNull URL classpathEntry, @NotNull URI source, @NotNull String id, Properties props) {
        this.classpathEntry = classpathEntry;
        this.source = Objects.requireNonNull(source);
        Objects.requireNonNull(id);

        if (!ID_VALIDATOR.matcher(id).matches()) {
            throw new RuntimeException("Plugin ID does not match the required pattern '[a-z0-9-]+': " + id);
        }

        try {
            this.name = Objects.requireNonNull(props.getProperty("name"), "name is not present");
            final String versionString = Objects.requireNonNull(props.getProperty("version"), "version is not present");
            final ArtifactVersion mainVersion = new DefaultArtifactVersion(versionString);
            this.idAndVersion = new IdAndVersion(id, mainVersion);
            //
            final String additionalVersionsString =
                    props.getProperty("additionalVersions", "").trim();
            if (additionalVersionsString.isEmpty()) {
                this.additionalVersions = new IdAndVersion[0];
            } else {
                final String[] pairs = additionalVersionsString.split(";");
                this.additionalVersions = Arrays.stream(pairs)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(pair -> {
                            final String[] comps = pair.split("@", 2);
                            final String additionalId = comps[0];
                            if (!ID_VALIDATOR.matcher(additionalId).matches()) {
                                throw new RuntimeException(
                                        "Additional plugin ID does not match the required pattern '[a-z0-9-]+': " + id);
                            }
                            if (additionalId.equals(id)) {
                                throw new RuntimeException(
                                        "Additional plugin ID is the same as the main plugin ID: " + id);
                            }
                            final ArtifactVersion additionalVersion =
                                    (comps.length > 1) ? new DefaultArtifactVersion(comps[1]) : mainVersion;
                            return new IdAndVersion(additionalId, additionalVersion);
                        })
                        .toArray(IdAndVersion[]::new);
                // Validate no duplicates in additional versions
                // Yes, this is O(n^2), there shouldn't be many plugin versions provided by 1 plugin.
                for (int i = 1; i < additionalVersions.length; i++) {
                    for (int j = 0; j < i; j++) {
                        if (additionalVersions[i].id().equals(additionalVersions[j].id())) {
                            throw new RuntimeException("Additional plugin ID duplicated: " + additionalVersions[i]
                                    + " and " + additionalVersions[j]);
                        }
                    }
                }
            }
            //
            this.className = Objects.requireNonNull(props.getProperty("className"), "className is not present");
            //
            final String versionConstraintsString =
                    props.getProperty("versionConstraints", "").trim();
            if (versionConstraintsString.isEmpty()) {
                this.versionConstraints = new IdAndVersionRange[0];
            } else {
                final String[] pairs = versionConstraintsString.split(";");
                this.versionConstraints = Arrays.stream(pairs)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(pair -> {
                            final String[] comps = pair.split("@", 2);
                            final String name = comps[0].trim();
                            final VersionRange versionRange;
                            if (comps.length < 2) {
                                throw new RuntimeException("Version not specified in versionConstraints for " + name);
                            }
                            try {
                                versionRange = VersionRange.createFromVersionSpec(comps[1].trim());
                            } catch (InvalidVersionSpecificationException e) {
                                throw new RuntimeException(e);
                            }
                            return new IdAndVersionRange(name, versionRange);
                        })
                        .toArray(IdAndVersionRange[]::new);
            }
            //
            final AtomicBoolean pinLast = new AtomicBoolean(false);
            this.transformerExclusions = Arrays.stream(
                            props.getProperty("transformerExclusions", "").split(";"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
            this.loadBefore = Arrays.stream(props.getProperty("loadBefore", "").split(";"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
            this.loadAfter = Arrays.stream(props.getProperty("loadAfter", "").split(";"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .filter(s -> {
                        if ("*".equals(s)) {
                            pinLast.set(true);
                            return false;
                        } else {
                            return true;
                        }
                    })
                    .toArray(String[]::new);
            this.loadRequires = Arrays.stream(
                            props.getProperty("loadRequires", "").split(";"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
            this.pinLast = pinLast.get();
        } catch (Throwable t) {
            throw new RuntimeException("Error when parsing plugin metadata for plugin " + id, t);
        }
    }

    public URL classpathEntry() {
        return classpathEntry;
    }

    public URI source() {
        return source;
    }

    public IdAndVersion idAndVersion() {
        return idAndVersion;
    }

    public String id() {
        return idAndVersion.id();
    }

    public String name() {
        return name;
    }

    public ArtifactVersion version() {
        return idAndVersion.version();
    }

    /**
     * @param id The ID to look up a version for.
     * @return The version provided by this plugin for the particular ID provided, or none if not found.
     */
    public @Nullable ArtifactVersion version(String id) {
        if (this.idAndVersion.id().equals(id)) {
            return this.idAndVersion.version();
        }
        for (IdAndVersion iav : additionalVersions) {
            if (iav.id.equals(id)) {
                return iav.version();
            }
        }
        return null;
    }

    public IdAndVersion[] additionalVersions() {
        return additionalVersions;
    }

    public String className() {
        return className;
    }

    public RfbPlugin instance() {
        return instance;
    }

    public void instance(RfbPlugin value) {
        if (instance == null) {
            instance = value;
        } else {
            throw new IllegalStateException(idAndVersion + " already initialized");
        }
    }

    public IdAndVersionRange[] versionConstraints() {
        return versionConstraints;
    }

    public String[] transformerExclusions() {
        return transformerExclusions;
    }

    public String[] loadBefore() {
        return loadBefore;
    }

    public String[] loadAfter() {
        return loadAfter;
    }

    public String[] loadRequires() {
        return loadRequires;
    }

    /** if the original loadAfter property contained a "*" */
    public boolean pinLast() {
        return pinLast;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        RfbPluginMetadata that = (RfbPluginMetadata) obj;
        return Objects.equals(this.name, that.name)
                && Objects.equals(this.idAndVersion, that.idAndVersion)
                && Arrays.equals(this.additionalVersions, that.additionalVersions)
                && Objects.equals(this.className, that.className)
                && Objects.equals(this.instance, that.instance)
                && Arrays.equals(this.versionConstraints, that.versionConstraints)
                && Arrays.equals(this.transformerExclusions, that.transformerExclusions)
                && Arrays.equals(this.loadBefore, that.loadBefore)
                && Arrays.equals(this.loadAfter, that.loadAfter)
                && Arrays.equals(this.loadRequires, that.loadRequires);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                idAndVersion,
                name,
                Arrays.hashCode(additionalVersions),
                className,
                Arrays.hashCode(versionConstraints),
                Arrays.hashCode(transformerExclusions),
                Arrays.hashCode(loadBefore),
                Arrays.hashCode(loadAfter),
                Arrays.hashCode(loadRequires));
    }

    @Override
    public String toString() {
        return "RfbPluginMetadata["
                + "classpathEntry="
                + classpathEntry
                + ", "
                + "source="
                + source
                + ", "
                + "idAndVersion="
                + idAndVersion
                + ", "
                + "name="
                + name
                + ", "
                + "additionalVersions="
                + Arrays.toString(additionalVersions)
                + ", "
                + "className="
                + className
                + ", "
                + "versionConstraints="
                + Arrays.toString(versionConstraints)
                + ", "
                + "transformerExclusions="
                + Arrays.toString(transformerExclusions)
                + ", "
                + "loadBefore="
                + Arrays.toString(loadBefore)
                + ", "
                + "loadAfter="
                + Arrays.toString(loadAfter)
                + ", "
                + "loadRequires="
                + Arrays.toString(loadRequires)
                + ']';
    }

    @Override
    public int compareTo(@NotNull RfbPluginMetadata o) {
        return ID_COMPARATOR.compare(this, o);
    }

    public static final class IdAndVersion {

        private final String id;
        private final ArtifactVersion version;

        public IdAndVersion(String id, ArtifactVersion version) {
            this.id = id;
            this.version = version;
        }

        public String id() {
            return id;
        }

        public ArtifactVersion version() {
            return version;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            IdAndVersion that = (IdAndVersion) obj;
            return Objects.equals(this.id, that.id) && Objects.equals(this.version, that.version);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, version);
        }

        @Override
        public String toString() {
            return id + '@' + version;
        }
    }

    public static final class IdAndVersionRange {

        private final String id;
        private final VersionRange version;

        public IdAndVersionRange(String id, VersionRange version) {
            this.id = id;
            this.version = version;
        }

        public String id() {
            return id;
        }

        public VersionRange version() {
            return version;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            IdAndVersionRange that = (IdAndVersionRange) obj;
            return Objects.equals(this.id, that.id) && Objects.equals(this.version, that.version);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, version);
        }

        @Override
        public String toString() {
            return id + '@' + version;
        }
    }

    @VisibleForTesting
    public static class Builder {

        @NotNull
        final URL classpathEntry;

        @NotNull
        final URI source;

        @NotNull
        final String id;

        @NotNull
        final String name;

        @NotNull
        final ArtifactVersion version;

        IdAndVersion[] additionalVersions = new IdAndVersion[0];

        @NotNull
        final String className;

        IdAndVersionRange[] versionConstraints = new IdAndVersionRange[0];
        String[] transformerExclusions = new String[0];
        String[] loadBefore = new String[0];
        String[] loadAfter = new String[0];
        String[] loadRequires = new String[0];
        boolean pinLast;

        public Builder(
                @NotNull URL classpathEntry,
                @NotNull URI source,
                @NotNull String id,
                @NotNull String name,
                @NotNull String version,
                @NotNull String className) {
            this.classpathEntry = classpathEntry;
            this.source = source;
            this.id = id;
            this.name = name;
            this.version = new DefaultArtifactVersion(version);
            this.className = className;
        }

        public Builder(
                @NotNull URL classpathEntry,
                @NotNull URI source,
                @NotNull String id,
                @NotNull String name,
                @NotNull String version,
                @NotNull Class<?> klass) {
            this(classpathEntry, source, id, name, version, klass.getName());
        }

        private static <T> T[] append(final T[] array, final T element) {
            final T[] more = Arrays.copyOf(array, array.length + 1);
            more[array.length] = element;
            return more;
        }

        public Builder additionalVersion(String id, String version) {
            additionalVersions = append(additionalVersions, new IdAndVersion(id, new DefaultArtifactVersion(version)));
            return this;
        }

        public Builder versionConstraint(String id, String versionConstraint) {
            try {
                versionConstraints = append(
                        versionConstraints,
                        new IdAndVersionRange(id, VersionRange.createFromVersionSpec(versionConstraint)));
            } catch (InvalidVersionSpecificationException e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        public Builder transformerExclusion(String id) {
            transformerExclusions = append(transformerExclusions, id);
            return this;
        }

        public Builder loadBefore(String id) {
            loadBefore = append(loadBefore, id);
            return this;
        }

        public Builder loadAfter(String id) {
            if (id.equals("*")) {
                pinLast = true;
            } else {
                loadAfter = append(loadAfter, id);
            }
            return this;
        }

        public Builder loadRequires(String id) {
            loadRequires = append(loadRequires, id);
            return this;
        }

        public RfbPluginMetadata build() {
            return new RfbPluginMetadata(
                    classpathEntry,
                    source,
                    id,
                    name,
                    version,
                    additionalVersions,
                    className,
                    versionConstraints,
                    transformerExclusions,
                    loadBefore,
                    loadAfter,
                    loadRequires,
                    pinLast);
        }
    }
}
