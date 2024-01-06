package net.minecraft.launchwrapper.injector;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import net.minecraft.launchwrapper.IClassTransformer;

public class VanillaTweakInjector implements IClassTransformer {
    /** empty */
    public VanillaTweakInjector() {}

    /**
     * <ol>
     *     <li>Only transform net.minecraft.client.Minecraft</li>
     *     <li>Find the main method</li>
     *     <li>Find the first static File-typed field, the working directory FieldNode</li>
     *     <li>Inject INVOKESTATIC net/minecraft/launchwrapper/injector/VanillaTweakInjector by insert()ing into main's instructions list</li>
     *     <li>Inject PUTSTATIC into the found field by insert()ing into main's instructions list</li>
     * </ol>
     */
    @Override
    public byte[] transform(final String name, final String transformedName, final byte[] bytes) {
        // snip
        throw new UnsupportedOperationException("NYI, TODO");
    }

    /**
     * <ol>
     *     <li>Turn off ImageIO disk caching</li>
     *     <li>Invoke loadIconsOnFrames</li>
     * </ol>
     * @return Launch.minecraftHome
     */
    public static File inject() {
        // snip
        throw new UnsupportedOperationException("NYI, TODO");
    }

    /**
     * Call lwjgl's Display.setIcon with icons loaded from "icons/icon_16x16.png" and "icons/icon_32x32.png" in the assets directory.
     * Also sets it for all AWT Frames, presumably for old mc versions.
     * Logs any exceptions without crashing.
     */
    public static void loadIconsOnFrames() {
        // snip
        throw new UnsupportedOperationException("NYI, TODO");
    }

    /**
     * A helper using BufferedImage to read iconFile, get its ARGB and put it into a bytebuffer in RGBA order
     */
    private static ByteBuffer loadIcon(final File iconFile) throws IOException {
        // snip
        throw new UnsupportedOperationException("NYI, TODO");
    }
}
