package io.github.dsheirer.airspyhf;

/**
 * Thrown when a libairspyhf function reports an error. The library uses a
 * single code for most failures, so the function name is the main clue.
 */
public class AirspyHfException extends Exception {
    private static final long serialVersionUID = 1L;

    private final int code;

    AirspyHfException(String function, int code) {
        super(function + " failed: " + AirspyHfError.fromCode(code).nativeName() + " (" + code + ")");
        this.code = code;
    }

    /** Returns the {@code airspyhf_error} value the native function returned. */
    public int code() {
        return code;
    }

    /** Returns the error as an enum, or {@link AirspyHfError#OTHER} for an unknown code. */
    public AirspyHfError error() {
        return AirspyHfError.fromCode(code);
    }
}
