package io.github.dsheirer.airspyhf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.airspyhf.internal.Layouts;
import io.github.dsheirer.airspyhf.internal.Native;
import io.github.dsheirer.airspyhf.internal.NativeLibraryLoader;
import io.github.dsheirer.libusb.NativeLoader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests that need the native library for the current platform but no hardware. */
class LibraryTest {
    @Test
    void structureSizesMatchHeader() {
        assertEquals(12, Layouts.LIB_VERSION.byteSize());
        assertEquals(20, Layouts.PART_SERIAL.byteSize());
        assertEquals(40, Layouts.TRANSFER.byteSize());
        assertEquals(32, Layouts.offset(Layouts.TRANSFER, "dropped_samples"));
    }

    @Test
    void loadsLibraryAndResolvesEveryFunction() {
        LibraryVersion version = AirspyHf.libraryVersion();
        assertTrue(version.major() >= 1, version.toString());
        assertNotNull(Native.SET_HF_LNA);
    }

    @Test
    void decodesErrors() {
        assertEquals(AirspyHfError.ERROR, AirspyHfError.fromCode(-1));
        assertEquals(AirspyHfError.UNSUPPORTED, AirspyHfError.fromCode(-2));
        assertEquals(AirspyHfError.OTHER, AirspyHfError.fromCode(-12345));
        assertEquals("airspyhf_open failed: AIRSPYHF_ERROR (-1)",
            new AirspyHfException("airspyhf_open", -1).getMessage());
    }

    @Test
    void enumeratesWithoutFailing() {
        // CI machines may have no USB bus, which the library reports as an error.
        try {
            assertNotNull(AirspyHf.listDevices());
        } catch (AirspyHfException e) {
            assertEquals(AirspyHfError.ERROR, e.error(), e.getMessage());
        }
    }

    @Test
    void openingByFileDescriptorIsAndroidOnly() {
        AirspyHfException e = assertThrows(AirspyHfException.class, () -> AirspyHf.openFileDescriptor(0));
        // Without a USB bus, libusb_init fails first and the library reports ERROR.
        assertTrue(Set.of(AirspyHfError.UNSUPPORTED, AirspyHfError.ERROR).contains(e.error()), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> AirspyHf.openFileDescriptor(-1));
    }

    @Test
    void copiesInterleavedFloats() {
        try (Arena arena = Arena.ofConfined()) {
            float[] iq = {0.5f, -0.5f, 0.25f, -0.25f};
            MemorySegment memory = arena.allocateFrom(ValueLayout.JAVA_FLOAT, iq);
            SampleBuffer buffer = new SampleBuffer(memory, 2, 0);
            assertArrayEquals(iq, buffer.copyFloats());
            float[] target = new float[6];
            buffer.copyFloats(target);
            assertArrayEquals(new float[] {0.5f, -0.5f, 0.25f, -0.25f, 0, 0}, target);
        }
    }

    @Test
    void frontendOptionsRoundTrip() {
        assertEquals(Set.of(FrontendOption.OPTIMIZE_PLL_INT_BOUNDARY), FrontendOption.fromMask(2 | 8));
        assertEquals(3, FrontendOption.toMask(Set.of(FrontendOption.values())));
        assertEquals(0, FrontendOption.toMask(Set.of()));
    }

    @Test
    void namesLibraries() {
        assertEquals("libairspyhf.so", NativeLibraryLoader.libraryName("linux-aarch64"));
        assertEquals("libairspyhf.dylib", NativeLibraryLoader.libraryName("macos-x86_64"));
        assertEquals("airspyhf.dll", NativeLibraryLoader.libraryName("windows-aarch64"));
    }

    @Test
    void sharesLibusbWithJlibusb() throws Throwable {
        // Loading libairspyhf loads jlibusb's libusb, so both sit in its directory.
        assertNotNull(AirspyHf.libraryVersion());
        Path directory = NativeLoader.directory();
        String platform = NativeLoader.platform();
        assertTrue(Files.isRegularFile(directory.resolve(NativeLoader.libusbName(platform))));
        assertTrue(Files.isRegularFile(directory.resolve(NativeLibraryLoader.libraryName(platform))));
        if (platform.startsWith("linux")) {
            // One copy of libusb in the process, the one jlibusb extracted.
            List<String> mapped = Files.readAllLines(Path.of("/proc/self/maps")).stream()
                .filter(line -> line.contains("libusb-1.0"))
                .map(line -> line.substring(line.indexOf('/')))
                .distinct()
                .toList();
            assertEquals(List.of(directory.resolve("libusb-1.0.so.0").toString()), mapped);
        } else if (platform.startsWith("macos")) {
            // dyld must reuse the libusb jlibusb loaded when libairspyhf asks for
            // @loader_path/libusb-1.0.0.dylib, even through the /var symlink.
            List<String> images = dyldImages().stream().filter(name -> name.contains("libusb-1.0")).toList();
            assertEquals(1, images.size(), images.toString());
        }
    }

    /** Paths of the images dyld has loaded into this process. */
    private static List<String> dyldImages() throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup system = linker.defaultLookup();
        MethodHandle count = linker.downcallHandle(system.find("_dyld_image_count").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT));
        MethodHandle name = linker.downcallHandle(system.find("_dyld_get_image_name").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        int images = (int) count.invokeExact();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < images; i++) {
            MemorySegment pointer = (MemorySegment) name.invokeExact(i);
            if (!pointer.equals(MemorySegment.NULL)) {
                names.add(pointer.reinterpret(Long.MAX_VALUE).getString(0));
            }
        }
        return names;
    }
}
