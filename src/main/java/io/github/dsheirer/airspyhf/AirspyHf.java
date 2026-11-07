package io.github.dsheirer.airspyhf;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import io.github.dsheirer.airspyhf.internal.Layouts;
import io.github.dsheirer.airspyhf.internal.Native;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * Entry point of the library: finds and opens Airspy HF+ devices.
 *
 * <pre>{@code
 * try (AirspyHfDevice device = AirspyHf.open()) {
 *     device.setSampleRate(device.sampleRates()[0]);
 *     device.setFrequency(7_100_000L);
 *     device.startRx(buffer -> {
 *         process(buffer.copyFloats());
 *         return true;
 *     });
 *     Thread.sleep(1000);
 * }
 * }</pre>
 *
 * <p>The native enumeration and open functions are not thread-safe, so the
 * methods of this class run one at a time.
 */
public final class AirspyHf {
    private static final Object LOCK = new Object();

    private AirspyHf() {
    }

    /** Returns the version of the loaded native library. */
    public static LibraryVersion libraryVersion() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment version = arena.allocate(Layouts.LIB_VERSION);
            Native.invokeVoid(Native.LIB_VERSION, version);
            return new LibraryVersion(Layouts.u32(version, Layouts.LIB_VERSION, "major_version"),
                Layouts.u32(version, Layouts.LIB_VERSION, "minor_version"),
                Layouts.u32(version, Layouts.LIB_VERSION, "revision"));
        }
    }

    /**
     * Returns the serial numbers of the connected devices. A device the library
     * cannot open to read its serial number, which on some systems includes one
     * that is already in use, is not listed.
     */
    public static long[] listDevices() throws AirspyHfException {
        synchronized (LOCK) {
            int count = check("airspyhf_list_devices", Native.invoke(Native.LIST_DEVICES, MemorySegment.NULL, 0));
            if (count == 0) {
                return new long[0];
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment serials = arena.allocate(JAVA_LONG, count);
                int found = check("airspyhf_list_devices", Native.invoke(Native.LIST_DEVICES, serials, count));
                return serials.asSlice(0, Math.min(found, count) * JAVA_LONG.byteSize()).toArray(JAVA_LONG);
            }
        }
    }

    /** Opens the first available device. */
    public static AirspyHfDevice open() throws AirspyHfException {
        return open("airspyhf_open", Native.OPEN);
    }

    /** Opens the device with a serial number from {@link #listDevices()}. */
    public static AirspyHfDevice open(long serialNumber) throws AirspyHfException {
        return open("airspyhf_open_sn", Native.OPEN_SN, serialNumber);
    }

    /**
     * Opens a device from a USB file descriptor, on Android only. The
     * descriptor must stay open until the device is closed.
     *
     * @throws AirspyHfException with {@link AirspyHfError#UNSUPPORTED} on other
     *     operating systems, which includes every platform this jar has native
     *     libraries for
     */
    public static AirspyHfDevice openFileDescriptor(int fd) throws AirspyHfException {
        // The native function treats -1 as "no descriptor" and opens the first device.
        if (fd < 0) {
            throw new IllegalArgumentException("Invalid file descriptor: " + fd);
        }
        return open("airspyhf_open_fd", Native.OPEN_FD, fd);
    }

    private static AirspyHfDevice open(String function, MethodHandle handle, Object... arguments)
            throws AirspyHfException {
        synchronized (LOCK) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment pointer = arena.allocate(ADDRESS);
                Object[] all = new Object[arguments.length + 1];
                all[0] = pointer;
                System.arraycopy(arguments, 0, all, 1, arguments.length);
                check(function, Native.invoke(handle, all));
                return new AirspyHfDevice(pointer.get(ADDRESS, 0));
            }
        }
    }

    static int check(String function, int result) throws AirspyHfException {
        if (result < 0) {
            throw new AirspyHfException(function, result);
        }
        return result;
    }
}
