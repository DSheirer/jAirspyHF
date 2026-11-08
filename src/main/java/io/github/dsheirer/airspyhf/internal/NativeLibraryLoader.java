package io.github.dsheirer.airspyhf.internal;

import io.github.dsheirer.libusb.NativeLoader;
import java.lang.foreign.SymbolLookup;
import java.util.List;

/**
 * Loads libairspyhf through jlibusb, which supplies the libusb it depends on.
 *
 * <p>By default libairspyhf is extracted from the jar, next to jlibusb's copy
 * of libusb. Set the {@value #PATH_PROPERTY} system property to a directory to
 * load libairspyhf from there instead; set {@code jlibusb.library.path} to
 * replace libusb.
 */
public final class NativeLibraryLoader {
    /** System property naming a directory that overrides the bundled libairspyhf. */
    public static final String PATH_PROPERTY = "jairspyhf.library.path";

    private static final String RESOURCE_ROOT = "/jairspyhf/native/";

    private NativeLibraryLoader() {
    }

    /** File name of libairspyhf on a platform, such as {@code linux-x86_64}. */
    public static String libraryName(String platform) {
        if (platform.startsWith("linux")) {
            return "libairspyhf.so";
        }
        if (platform.startsWith("macos")) {
            return "libairspyhf.dylib";
        }
        return "airspyhf.dll";
    }

    /** Loads libusb and libairspyhf and returns a lookup for the symbols of libairspyhf. */
    static SymbolLookup load() {
        String name = libraryName(NativeLoader.platform());
        return NativeLoader.load(NativeLibraryLoader.class, RESOURCE_ROOT, List.of(name), PATH_PROPERTY);
    }
}
