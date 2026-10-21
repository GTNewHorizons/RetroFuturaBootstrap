package com.gtnewhorizons.retrofuturabootstrap.concurrency;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import java.util.jar.Manifest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runs an RFB transformer one class at a time. For transformers that keep per call state in fields and were already
 * loaded before anything could patch them.
 */
public final class SerialRfbTransformer implements RfbClassTransformer {

    private final RfbClassTransformer wrapped;

    public SerialRfbTransformer(RfbClassTransformer wrapped) {
        this.wrapped = wrapped;
    }

    public RfbClassTransformer wrapped() {
        return wrapped;
    }

    @Override
    public @NotNull String id() {
        return wrapped.id();
    }

    @Override
    public @NotNull String @Nullable [] sortAfter() {
        return wrapped.sortAfter();
    }

    @Override
    public @NotNull String @Nullable [] sortBefore() {
        return wrapped.sortBefore();
    }

    @Override
    public @NotNull String @Nullable [] additionalExclusions() {
        return wrapped.additionalExclusions();
    }

    @Override
    public void onRegistration(@NotNull ExtensibleClassLoader classLoader) {
        wrapped.onRegistration(classLoader);
    }

    @Override
    public synchronized boolean shouldTransformClass(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode) {
        return wrapped.shouldTransformClass(classLoader, context, manifest, className, classNode);
    }

    @Override
    public synchronized boolean transformClassIfNeeded(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode) {
        return wrapped.transformClassIfNeeded(classLoader, context, manifest, className, classNode);
    }

    @Override
    public synchronized void transformClass(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode) {
        wrapped.transformClass(classLoader, context, manifest, className, classNode);
    }
}
