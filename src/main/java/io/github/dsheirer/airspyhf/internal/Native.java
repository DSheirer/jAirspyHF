package io.github.dsheirer.airspyhf.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * Downcall handles for the public functions of libairspyhf.
 *
 * <p>{@code native/check-bindings.sh} compares this list against {@code airspyhf.h}.
 */
public final class Native {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = NativeLibraryLoader.load();

    public static final MethodHandle LIB_VERSION = voidCall("airspyhf_lib_version", ADDRESS);
    public static final MethodHandle LIST_DEVICES = call("airspyhf_list_devices", ADDRESS, JAVA_INT);
    public static final MethodHandle OPEN = call("airspyhf_open", ADDRESS);
    public static final MethodHandle OPEN_SN = call("airspyhf_open_sn", ADDRESS, JAVA_LONG);
    public static final MethodHandle OPEN_FD = call("airspyhf_open_fd", ADDRESS, JAVA_INT);
    public static final MethodHandle CLOSE = call("airspyhf_close", ADDRESS);
    public static final MethodHandle GET_OUTPUT_SIZE = call("airspyhf_get_output_size", ADDRESS);

    public static final MethodHandle START = call("airspyhf_start", ADDRESS, ADDRESS, ADDRESS);
    public static final MethodHandle STOP = call("airspyhf_stop", ADDRESS);
    public static final MethodHandle IS_STREAMING = call("airspyhf_is_streaming", ADDRESS);
    public static final MethodHandle IS_LOW_IF = call("airspyhf_is_low_if", ADDRESS);

    public static final MethodHandle SET_FREQ = call("airspyhf_set_freq", ADDRESS, JAVA_INT);
    public static final MethodHandle SET_FREQ_DOUBLE = call("airspyhf_set_freq_double", ADDRESS, JAVA_DOUBLE);
    public static final MethodHandle SET_LIB_DSP = call("airspyhf_set_lib_dsp", ADDRESS, JAVA_BYTE);
    public static final MethodHandle GET_SAMPLERATES = call("airspyhf_get_samplerates", ADDRESS, ADDRESS, JAVA_INT);
    public static final MethodHandle SET_SAMPLERATE = call("airspyhf_set_samplerate", ADDRESS, JAVA_INT);
    public static final MethodHandle SET_ATT = call("airspyhf_set_att", ADDRESS, JAVA_FLOAT);
    public static final MethodHandle GET_ATT_STEPS = call("airspyhf_get_att_steps", ADDRESS, ADDRESS, JAVA_INT);

    public static final MethodHandle SET_BIAS_TEE = call("airspyhf_set_bias_tee", ADDRESS, JAVA_BYTE);
    public static final MethodHandle GET_BIAS_TEE_COUNT = call("airspyhf_get_bias_tee_count", ADDRESS, ADDRESS);
    public static final MethodHandle GET_BIAS_TEE_NAME =
        call("airspyhf_get_bias_tee_name", ADDRESS, JAVA_INT, ADDRESS, JAVA_BYTE);

    public static final MethodHandle GET_CALIBRATION = call("airspyhf_get_calibration", ADDRESS, ADDRESS);
    public static final MethodHandle SET_CALIBRATION = call("airspyhf_set_calibration", ADDRESS, JAVA_INT);
    public static final MethodHandle GET_VCTCXO_CALIBRATION =
        call("airspyhf_get_vctcxo_calibration", ADDRESS, ADDRESS);
    public static final MethodHandle SET_VCTCXO_CALIBRATION =
        call("airspyhf_set_vctcxo_calibration", ADDRESS, JAVA_SHORT);
    public static final MethodHandle GET_FRONTEND_OPTIONS = call("airspyhf_get_frontend_options", ADDRESS, ADDRESS);
    public static final MethodHandle SET_FRONTEND_OPTIONS = call("airspyhf_set_frontend_options", ADDRESS, JAVA_INT);
    public static final MethodHandle FLASH_CONFIGURATION = call("airspyhf_flash_configuration", ADDRESS);
    public static final MethodHandle SET_OPTIMAL_IQ_CORRECTION_POINT =
        call("airspyhf_set_optimal_iq_correction_point", ADDRESS, JAVA_FLOAT);
    public static final MethodHandle IQ_BALANCER_CONFIGURE =
        call("airspyhf_iq_balancer_configure", ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);

    public static final MethodHandle BOARD_PARTID_SERIALNO_READ =
        call("airspyhf_board_partid_serialno_read", ADDRESS, ADDRESS);
    public static final MethodHandle VERSION_STRING_READ =
        call("airspyhf_version_string_read", ADDRESS, ADDRESS, JAVA_BYTE);

    // Kept upstream for backward compatibility.
    public static final MethodHandle SET_USER_OUTPUT = call("airspyhf_set_user_output", ADDRESS, JAVA_INT, JAVA_INT);
    public static final MethodHandle SET_HF_AGC = call("airspyhf_set_hf_agc", ADDRESS, JAVA_BYTE);
    public static final MethodHandle SET_HF_AGC_THRESHOLD = call("airspyhf_set_hf_agc_threshold", ADDRESS, JAVA_BYTE);
    public static final MethodHandle SET_HF_ATT = call("airspyhf_set_hf_att", ADDRESS, JAVA_BYTE);
    public static final MethodHandle SET_HF_LNA = call("airspyhf_set_hf_lna", ADDRESS, JAVA_BYTE);

    private Native() {
    }

    private static MemorySegment symbol(String name) {
        return LOOKUP.find(name).orElseThrow(() -> new UnsatisfiedLinkError("libairspyhf has no symbol " + name));
    }

    private static MethodHandle call(String name, MemoryLayout... arguments) {
        return LINKER.downcallHandle(symbol(name), FunctionDescriptor.of(JAVA_INT, arguments));
    }

    private static MethodHandle voidCall(String name, MemoryLayout... arguments) {
        return LINKER.downcallHandle(symbol(name), FunctionDescriptor.ofVoid(arguments));
    }

    /**
     * Calls a native function returning {@code int}. This boxes its arguments,
     * which is fine for the control paths using it; samples arrive by upcall.
     */
    public static int invoke(MethodHandle function, Object... arguments) {
        try {
            return (int) function.invokeWithArguments(arguments);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Native call failed", t);
        }
    }

    /** Calls a native function that returns nothing. */
    public static void invokeVoid(MethodHandle function, Object... arguments) {
        try {
            function.invokeWithArguments(arguments);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Native call failed", t);
        }
    }

    /** Creates an upcall stub for {@code airspyhf_sample_block_cb_fn}. */
    public static MemorySegment sampleCallback(MethodHandle target, Arena arena) {
        return LINKER.upcallStub(target, FunctionDescriptor.of(JAVA_INT, ADDRESS), arena);
    }
}
