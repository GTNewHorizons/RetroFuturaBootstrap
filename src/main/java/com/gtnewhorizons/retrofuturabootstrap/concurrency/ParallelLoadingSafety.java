package com.gtnewhorizons.retrofuturabootstrap.concurrency;

import com.gtnewhorizons.retrofuturabootstrap.Main;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformerHandle;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.objectweb.asm.commons.Remapper;

/**
 * What has to hold before classes may load on several threads. The bytecode fixes themselves are listed in
 * {@link ConcurrentTransformersTransformer}; each belongs in the mod that owns the class.
 */
public final class ParallelLoadingSafety {

    /** RFB transformers that keep per call state in fields and are loaded too early to be patched. */
    private static final List<String> SERIAL_RFB_TRANSFORMERS =
            Collections.singletonList("me.eigenraven.lwjgl3ify.rfb.transformers.LwjglRedirectTransformer");

    private ParallelLoadingSafety() {}

    /** @return why classes cannot load in parallel, or null */
    public static String check(ClassLoader loader) {
        if (ConcurrentTransformersTransformer.blockedReason != null) {
            return ConcurrentTransformersTransformer.blockedReason;
        }
        try {
            final Method findLoaded = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            findLoaded.setAccessible(true);
            final ClassLoader system = ClassLoader.getSystemClassLoader();
            for (String target : ConcurrentTransformersTransformer.targets()) {
                if (ConcurrentTransformersTransformer.APPLIED.contains(target)) continue;
                if (findLoaded.invoke(loader, target) != null || findLoaded.invoke(system, target) != null) {
                    return target + " was loaded before its thread safety fix could be applied";
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "could not check the thread safety fixes: " + e;
        }
        return null;
    }

    /** Wraps the RFB transformers that are not thread safe so they run one class at a time. */
    public static void serializeRfbTransformers() {
        Main.mutateRfbTransformers(handles -> {
            for (int i = 0; i < handles.size(); i++) {
                final RfbClassTransformerHandle handle = handles.get(i);
                // a fixed version no longer keeps its state in the transformer itself
                if (SERIAL_RFB_TRANSFORMERS.contains(
                                handle.transformer().getClass().getName())
                        && handle.transformer() instanceof Remapper) {
                    final RfbClassTransformerHandle serial = new RfbClassTransformerHandle(
                            handle.pluginMetadata(), handle.plugin(), new SerialRfbTransformer(handle.transformer()));
                    // other plugins may have added exclusions to the live handle
                    serial.exclusions().clear();
                    serial.exclusions().addAll(handle.exclusions());
                    handles.set(i, serial);
                }
            }
        });
    }

    /** Lazy state that is cheaper to fill once on one thread than to make thread safe. */
    public static void warmUp(ClassLoader loader) {
        // CodeChickenCore and NEI read their dump_asm option from a config that fills a TreeMap on first access
        try {
            final Class<?> helper = Class.forName("codechicken.lib.asm.ASMHelper", true, loader);
            final Object config = helper.getField("config").get(null);
            if (config != null) {
                config.getClass().getMethod("getTag", String.class).invoke(config, "dump_asm");
            }
        } catch (ClassNotFoundException ignored) {
            // not installed
        } catch (ReflectiveOperationException | RuntimeException e) {
            Main.logger.debug("Could not warm up the CodeChickenCore ASM config", e);
        }
    }
}
