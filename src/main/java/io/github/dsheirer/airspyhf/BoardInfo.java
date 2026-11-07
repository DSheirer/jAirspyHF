package io.github.dsheirer.airspyhf;

import java.util.Locale;

/**
 * Identity of a device, read from its hardware.
 *
 * @param partId microcontroller part ID
 * @param serialNumber serial number, as used by {@link AirspyHf#open(long)}
 */
public record BoardInfo(long partId, long serialNumber) {
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "part ID 0x%08X, serial 0x%016X", partId, serialNumber);
    }
}
