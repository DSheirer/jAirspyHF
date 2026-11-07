package io.github.dsheirer.airspyhf;

import java.util.EnumSet;
import java.util.Set;

/**
 * Front end options, the {@code AIRSPYHF_FLAGS_*} values of {@code airspyhf.h}.
 * Upstream documents them only by name; the firmware decides what each does
 * on a given device.
 */
public enum FrontendOption {
    /** {@code AIRSPYHF_FLAGS_OPTIMIZE_BAND_III}. */
    OPTIMIZE_BAND_III(1),
    /** {@code AIRSPYHF_FLAGS_OPTIMIZE_PLL_INT_BOUNDARY}. */
    OPTIMIZE_PLL_INT_BOUNDARY(2);

    private final int bit;

    FrontendOption(int bit) {
        this.bit = bit;
    }

    /** Returns the option's bit in the native flags. */
    public int bit() {
        return bit;
    }

    /** Decodes native flags; bits without an option are ignored. */
    public static Set<FrontendOption> fromMask(int mask) {
        EnumSet<FrontendOption> options = EnumSet.noneOf(FrontendOption.class);
        for (FrontendOption option : values()) {
            if ((mask & option.bit) != 0) {
                options.add(option);
            }
        }
        return options;
    }

    /** Encodes options as native flags. */
    public static int toMask(Set<FrontendOption> options) {
        int mask = 0;
        for (FrontendOption option : options) {
            mask |= option.bit;
        }
        return mask;
    }
}
