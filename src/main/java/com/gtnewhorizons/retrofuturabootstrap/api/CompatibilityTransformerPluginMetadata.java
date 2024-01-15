package com.gtnewhorizons.retrofuturabootstrap.api;

import com.gtnewhorizons.retrofuturabootstrap.versioning.ArtifactVersion;
import com.gtnewhorizons.retrofuturabootstrap.versioning.DefaultArtifactVersion;
import com.gtnewhorizons.retrofuturabootstrap.versioning.InvalidVersionSpecificationException;
import com.gtnewhorizons.retrofuturabootstrap.versioning.VersionRange;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class CompatibilityTransformerPluginMetadata {

    public static final Pattern ID_VALIDATOR = Pattern.compile("[a-z0-9-]+");

    private final @NotNull String id;
    private final @NotNull String name;
    private final @NotNull ArtifactVersion version;
    private final @NotNull IdAndVersion[] additionalVersions;
    private final @NotNull IdAndVersionRange[] versionConstraints;
    private final @NotNull String[] transformerExclusions;
    private final @NotNull String[] loadBefore;
    private final @NotNull String[] loadAfter;
    private final @NotNull String[] loadRequires;
    private final @NotNull String className;
    private @Nullable CompatibilityTransformerPlugin instance;

    public CompatibilityTransformerPluginMetadata(
            @NotNull String id,
            @NotNull String name,
            @NotNull ArtifactVersion version,
            IdAndVersion[] additionalVersions,
            @NotNull String className,
            IdAndVersionRange[] versionConstraints,
            String[] transformerExclusions,
            String[] loadBefore,
            String[] loadAfter,
            String[] loadRequires) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.additionalVersions = additionalVersions;
        this.className = className;
        this.versionConstraints = versionConstraints;
        this.transformerExclusions = transformerExclusions;
        this.loadBefore = loadBefore;
        this.loadAfter = loadAfter;
        this.loadRequires = loadRequires;
    }

    public CompatibilityTransformerPluginMetadata(@NotNull String id, Properties props) {
        this.id = Objects.requireNonNull(id);

        if (!ID_VALIDATOR.matcher(id).matches()) {
            throw new RuntimeException("Plugin ID does not match the required pattern '[a-z0-9-]+': " + id);
        }

        try {
            this.name = Objects.requireNonNull(props.getProperty("name"), "name is not present");
            final String versionString = Objects.requireNonNull(props.getProperty("version"), "version is not present");
            this.version = new DefaultArtifactVersion(versionString);
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
                            final String name = comps[0];
                            if (!ID_VALIDATOR.matcher(name).matches()) {
                                throw new RuntimeException(
                                        "Additional plugin ID does not match the required pattern '[a-z0-9-]+': " + id);
                            }
                            final ArtifactVersion version =
                                    (comps.length > 1) ? new DefaultArtifactVersion(comps[1]) : this.version;
                            return new IdAndVersion(name, version);
                        })
                        .toArray(IdAndVersion[]::new);
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
                    .toArray(String[]::new);
            this.loadRequires = Arrays.stream(
                            props.getProperty("loadRequires", "").split(";"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
        } catch (Throwable t) {
            throw new RuntimeException("Error when parsing plugin metadata for plugin " + id, t);
        }
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public ArtifactVersion version() {
        return version;
    }

    public IdAndVersion[] additionalVersions() {
        return additionalVersions;
    }

    public String className() {
        return className;
    }

    public CompatibilityTransformerPlugin instance() {
        return instance;
    }

    public void instance(CompatibilityTransformerPlugin value) {
        if (instance == null) {
            instance = value;
        } else {
            throw new IllegalStateException(id + " already initialized");
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

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        CompatibilityTransformerPluginMetadata that = (CompatibilityTransformerPluginMetadata) obj;
        return Objects.equals(this.name, that.name)
                && Objects.equals(this.version, that.version)
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
                id,
                name,
                version,
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
        return "CompatibilityTransformerPluginMetadata["
                + "name="
                + name
                + ", "
                + "version="
                + version
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

    public static final class IdAndVersion {

        private final String name;
        private final ArtifactVersion version;

        public IdAndVersion(String name, ArtifactVersion version) {
            this.name = name;
            this.version = version;
        }

        public String name() {
            return name;
        }

        public ArtifactVersion version() {
            return version;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            IdAndVersion that = (IdAndVersion) obj;
            return Objects.equals(this.name, that.name) && Objects.equals(this.version, that.version);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, version);
        }

        @Override
        public String toString() {
            return name + '@' + version;
        }
    }

    public static final class IdAndVersionRange {

        private final String name;
        private final VersionRange version;

        public IdAndVersionRange(String name, VersionRange version) {
            this.name = name;
            this.version = version;
        }

        public String name() {
            return name;
        }

        public VersionRange version() {
            return version;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            IdAndVersionRange that = (IdAndVersionRange) obj;
            return Objects.equals(this.name, that.name) && Objects.equals(this.version, that.version);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, version);
        }

        @Override
        public String toString() {
            return name + '@' + version;
        }
    }
}
