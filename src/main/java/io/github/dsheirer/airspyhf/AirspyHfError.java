package io.github.dsheirer.airspyhf;

/** Error codes of {@code enum airspyhf_error}. */
public enum AirspyHfError {
    /** {@code AIRSPYHF_ERROR}: the library reports every failure with this one code. */
    ERROR(-1, "AIRSPYHF_ERROR"),
    /** {@code AIRSPYHF_UNSUPPORTED}: the function is not available on this platform. */
    UNSUPPORTED(-2, "AIRSPYHF_UNSUPPORTED"),
    /** A code this version of jAirspyHF does not know. */
    OTHER(Integer.MIN_VALUE, "unknown error");

    private final int code;
    private final String nativeName;

    AirspyHfError(int code, String nativeName) {
        this.code = code;
        this.nativeName = nativeName;
    }

    /** Returns the native error code. */
    public int code() {
        return code;
    }

    /** Returns the C name of the error, such as {@code AIRSPYHF_ERROR}. */
    public String nativeName() {
        return nativeName;
    }

    /** Returns the error for a native code, or {@link #OTHER} if it is unknown. */
    public static AirspyHfError fromCode(int code) {
        for (AirspyHfError error : values()) {
            if (error.code == code) {
                return error;
            }
        }
        return OTHER;
    }
}
