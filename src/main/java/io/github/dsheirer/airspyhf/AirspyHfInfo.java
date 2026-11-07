package io.github.dsheirer.airspyhf;

import java.util.Arrays;
import java.util.Locale;

/** Command line tool that prints the library version and the connected devices. */
public final class AirspyHfInfo {
    private AirspyHfInfo() {
    }

    /** Runs the tool. */
    public static void main(String[] args) {
        System.out.println("libairspyhf " + AirspyHf.libraryVersion());
        long[] serials;
        try {
            serials = AirspyHf.listDevices();
        } catch (AirspyHfException e) {
            // Machines without USB access, such as containers, end up here.
            System.out.println("Unable to list devices: " + e.getMessage());
            return;
        }
        System.out.println("Devices found: " + serials.length);
        for (long serial : serials) {
            System.out.println(String.format(Locale.ROOT, "Serial 0x%016X", serial));
            try (AirspyHfDevice device = AirspyHf.open(serial)) {
                System.out.println(String.format(Locale.ROOT, "  Part ID:      0x%08X",
                    device.boardInfo().partId()));
                System.out.println("  Firmware:     " + device.firmwareVersion());
                System.out.println("  Sample rates: " + Arrays.toString(device.sampleRates()));
            } catch (AirspyHfException e) {
                System.out.println("  Unable to open: " + e.getMessage());
            }
        }
    }
}
