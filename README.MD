# RetroFuturaBootstrap

An open source, backwards compatible replacement for [LegacyLauncher/LaunchWrapper](https://github.com/Mojang/LegacyLauncher). Does not currently implement the applet wrappers for really old versions.
Made by writing a [specification](./ll-spec.md) of how LaunchWrapper operates, and then implementing that specification without referencing the original code, while ensuring old mods continue working.

Targets Java 8, but is compatible with latest Java releases (last tested: 21), because the main use is enabling [lwjgl3ify](https://github.com/GTNewHorizons/lwjgl3ify)-like mods.
If there is a good use case, I'm open to lowering the Java requirement further.

## Plugins

RetroFuturaBootstrap supports early loading plugins to transform mod loader classes, or even other plugin classes, for the purpose of maintaining compatibility patches for code that is difficult to modify otherwise.
For example, patching bugs or new Java version support into the Forge Mod Loader, redirecting graphics API usage to an emulation layer or bugfixes for Forge "coremods".
You can add compatibility plugins into your jar by following these steps:
 - Implement the `com.gtnewhorizons.retrofuturabootstrap.api.CompatibilityTransformerPlugin` interface on your plugin class
 - Define `com.gtnewhorizons.retrofuturabootstrap.api.SimpleClassTransformer`s and add metadata about the transformer order and exclusions to your main plugin class
 - Pick a unique plugin ID you're very unlikely to change, made up of lower-case letters, digits and dashes for consistency (`[a-z0-9-]+`).
 - Add a UTF-8 properties file declaring information about your plugin to `META-INF/rfb-plugin/my-plugin-id.properties` in your jar (or runtime classpath in a development environments):
```properties
# RetroFuturaBootstrap plugin property file
# All properties can be empty or missing, unless specified otherwise. Lists are semicolon-separated, and whitespace around each value is stripped, unless specified otherwise.
# Human-friendly name (required). Only used for logging, all logic uses the plugin ID (taken from the file name).
name=My RFB plugin
# A Maven-compatible version number, used for validating dependencies and detecting duplicates (required)
version=0.0.1-alpha.1
# Alternative plugin IDs and versions that this plugin provides, e.g. generic names like "mixin" or the ID of a previous version.
# If versions conflicting with other plugins are found, the newest version is used and the other plugins won't load with a warning. Use versionConstraints to mark any incompatibilities.
additionalVersions=mixin@0.8.5; my-old-id@0.0.10
# The class implementing com.gtnewhorizons.retrofuturabootstrap.api.CompatibilityTransformerPlugin (required)
class=io.github.mymod.rfb.RfbPlugin
# Additional packages on top of the plugin's parent package to exclude from self-transformation by the plugin
transformerExclusions=io.github.mymod.util; io.github.mymod.common
# Version constraints on other plugins that would be loaded with this plugin. Failing these checks will provide an error to the user, with a config override to ignore conflicts.
# Dependency constraint parsing follows https://maven.apache.org/ref/3.9.6/maven-artifact/apidocs/org/apache/maven/artifact/versioning/VersionRange.html#createFromVersionSpec(java.lang.String)
versionConstraints=rfb@[1.0.0,); some-plugin@[0.0.1,0.0.3],[0.2.0,); mixin@[0.8.2,)
# Plugin load order properties don't restrict the actual registered transformers from registering in different positions in the transformer chain!
# Plugin IDs that this plugin should load before (to e.g. register an early transformer to modify that plugin's code)
loadBefore=a-plugin-i-modify; lwjgl3ify
# Plugin IDs that this plugin should load after. Add '*' to default to load the plugin as late as possible instead of as early as possible.
loadAfter=mixin; *
# Plugin IDs that this plugin requires to be present to load
loadRequires=mixin
```
 - If you need to split up or ship multiple plugins in a JAR, just include multiple properties files in `META-INF/rfb-plugin/`.

Standard plugin IDs include:
 - `rfb` - metadata about RetroFuturaBootstrap itself
 - `mixin` - the [Mixin](https://github.com/fabricmc/Mixin/) library for convenient bytecode manipulation
