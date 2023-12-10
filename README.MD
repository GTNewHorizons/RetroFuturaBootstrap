# RetroFuturaBootstrap

An open source, backwards compatible replacement for [LegacyLauncher/LaunchWrapper](https://github.com/Mojang/LegacyLauncher).
Made by writing a [specification](./ll-spec.md) of how LaunchWrapper operates, and then implementing that specification without referencing the original code, while ensuring old mods continue working.

Targets Java 17, because the main use is enabling [lwjgl3ify](https://github.com/GTNewHorizons/lwjgl3ify)-like mods.
