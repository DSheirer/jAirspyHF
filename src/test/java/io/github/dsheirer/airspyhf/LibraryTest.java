package io.github.dsheirer.airspyhf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.airspyhf.internal.Layouts;
import io.github.dsheirer.airspyhf.internal.Native;
import io.github.dsheirer.airspyhf.internal.NativeLibraryLoader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
    void mapsPlatforms() {
        assertEquals("linux-x86_64", NativeLibraryLoader.platform("Linux", "amd64"));
        assertEquals("linux-aarch64", NativeLibraryLoader.platform("Linux", "aarch64"));
        assertEquals("macos-x86_64", NativeLibraryLoader.platform("Mac OS X", "x86_64"));
        assertEquals("macos-aarch64", NativeLibraryLoader.platform("Mac OS X", "aarch64"));
        assertEquals("windows-x86_64", NativeLibraryLoader.platform("Windows 11", "amd64"));
        assertEquals("windows-aarch64", NativeLibraryLoader.platform("Windows 11", "aarch64"));
        assertThrows(UnsatisfiedLinkError.class, () -> NativeLibraryLoader.platform("Solaris", "amd64"));
        assertThrows(UnsatisfiedLinkError.class, () -> NativeLibraryLoader.platform("Linux", "riscv64"));
    }
}
