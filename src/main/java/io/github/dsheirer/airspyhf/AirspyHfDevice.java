package io.github.dsheirer.airspyhf;

import static io.github.dsheirer.airspyhf.AirspyHf.check;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import io.github.dsheirer.airspyhf.internal.Layouts;
import io.github.dsheirer.airspyhf.internal.Native;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.Set;

/**
 * An open Airspy HF+ series device: HF+ Dual Port, HF+ Discovery or Ranger.
 * Close it when finished, which also stops streaming.
 *
 * <p>Samples are always delivered as interleaved 32-bit float I/Q. The library
 * corrects I/Q imbalance and shifts a low-IF signal to zero; see
 * {@link #setLibDsp(boolean)}.
 *
 * <p>Control methods may be called from any thread, including from a
 * {@link SampleListener}, except {@link #stopRx()} and {@link #close()}. Do not
 * close a device while other threads are still using it.
 */
public final class AirspyHfDevice implements AutoCloseable {
    private static final MethodHandle ON_TRANSFER;

    static {
        try {
            ON_TRANSFER = MethodHandles.lookup().findVirtual(AirspyHfDevice.class, "onTransfer",
                MethodType.methodType(int.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final MemorySegment handle;
    private final Object lifecycleLock = new Object();
    private boolean closed;
    private boolean streamStarted;

    // The upcall stub lives as long as this object, not as long as one stream,
    // so a native thread that outlives a stream never calls freed code.
    private final MemorySegment callback;

    // Streaming state: written under lifecycleLock before the stream starts,
    // read by the callback thread.
    private volatile SampleListener listener;
    private volatile Thread callbackThread;
    private volatile Throwable streamingFailure;

    AirspyHfDevice(MemorySegment handle) {
        this.handle = handle;
        this.callback = Native.sampleCallback(ON_TRANSFER.bindTo(this), Arena.ofAuto());
    }

    // ---- Information ----

    /** Reads the part ID and serial number from the device. */
    public BoardInfo boardInfo() throws AirspyHfException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(Layouts.PART_SERIAL);
            check("airspyhf_board_partid_serialno_read",
                Native.invoke(Native.BOARD_PARTID_SERIALNO_READ, handle(), info));
            long serialOffset = Layouts.offset(Layouts.PART_SERIAL, "serial_no");
            long high = Integer.toUnsignedLong(info.get(JAVA_INT, serialOffset));
            long low = Integer.toUnsignedLong(info.get(JAVA_INT, serialOffset + JAVA_INT.byteSize()));
            return new BoardInfo(Layouts.u32(info, Layouts.PART_SERIAL, "part_id"), high << 32 | low);
        }
    }

    /** Reads the firmware version string from the device. */
    public String firmwareVersion() throws AirspyHfException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment version = arena.allocate(Layouts.MAX_NAME_STRING_SIZE);
            check("airspyhf_version_string_read", Native.invoke(Native.VERSION_STRING_READ, handle(), version,
                (byte) Layouts.MAX_NAME_STRING_SIZE));
            return version.getString(0);
        }
    }

    /** Returns the number of I/Q samples in each buffer passed to a {@link SampleListener}. */
    public int outputSize() throws AirspyHfException {
        return check("airspyhf_get_output_size", Native.invoke(Native.GET_OUTPUT_SIZE, handle()));
    }

    // ---- Tuning ----

    /** Tunes to a frequency in Hz. */
    public void setFrequency(long hertz) throws AirspyHfException {
        check("airspyhf_set_freq", Native.invoke(Native.SET_FREQ, handle(), u32(hertz)));
    }

    /**
     * Tunes to a frequency in Hz with sub-hertz resolution. The library tunes
     * the hardware to the nearest kHz and shifts the rest in software.
     */
    public void setFrequency(double hertz) throws AirspyHfException {
        if (!(hertz >= 0)) {
            throw new IllegalArgumentException("Frequency out of range: " + hertz);
        }
        check("airspyhf_set_freq_double", Native.invoke(Native.SET_FREQ_DOUBLE, handle(), hertz));
    }

    /** Returns the supported sample rates in Hz. */
    public long[] sampleRates() throws AirspyHfException {
        try (Arena arena = Arena.ofConfined()) {
            // A length of 0 makes the function store the count in the first element.
            MemorySegment countSegment = arena.allocate(JAVA_INT);
            check("airspyhf_get_samplerates", Native.invoke(Native.GET_SAMPLERATES, handle(), countSegment, 0));
            int count = countSegment.get(JAVA_INT, 0);
            if (count == 0) {
                return new long[0];
            }
            MemorySegment values = arena.allocate(JAVA_INT, count);
            check("airspyhf_get_samplerates", Native.invoke(Native.GET_SAMPLERATES, handle(), values, count));
            long[] result = new long[count];
            for (int i = 0; i < count; i++) {
                result[i] = Integer.toUnsignedLong(values.getAtIndex(JAVA_INT, i));
            }
            return result;
        }
    }

    /**
     * Selects a sample rate in Hz from {@link #sampleRates()}. The library
     * retunes to keep the current frequency.
     */
    public void setSampleRate(long hertz) throws AirspyHfException {
        // The native function treats values up to 100 as an index into the rate list.
        if (hertz <= 100) {
            throw new IllegalArgumentException("Not a sample rate: " + hertz);
        }
        check("airspyhf_set_samplerate", Native.invoke(Native.SET_SAMPLERATE, handle(), u32(hertz)));
    }

    /**
     * Returns true when the current sample rate uses a low intermediate
     * frequency, and false when it uses zero IF.
     */
    public boolean isLowIf() throws AirspyHfException {
        return check("airspyhf_is_low_if", Native.invoke(Native.IS_LOW_IF, handle())) != 0;
    }

    /**
     * Enables or disables the library's signal processing: I/Q imbalance
     * correction, the IF shift and fine tuning. It is enabled on open.
     */
    public void setLibDsp(boolean enabled) throws AirspyHfException {
        check("airspyhf_set_lib_dsp", Native.invoke(Native.SET_LIB_DSP, handle(), flag(enabled)));
    }

    // ---- Gain ----

    /** Enables or disables the automatic gain control. */
    public void setHfAgc(boolean enabled) throws AirspyHfException {
        check("airspyhf_set_hf_agc", Native.invoke(Native.SET_HF_AGC, handle(), flag(enabled)));
    }

    /** Selects the threshold of the automatic gain control. */
    public void setHfAgcThreshold(AgcThreshold threshold) throws AirspyHfException {
        check("airspyhf_set_hf_agc_threshold",
            Native.invoke(Native.SET_HF_AGC_THRESHOLD, handle(), (byte) threshold.ordinal()));
    }

    /**
     * Returns the attenuator settings the device supports, in dB, in the order
     * of their step numbers. The HF+ Dual Port and HF+ Discovery have 0 to 48 dB
     * in 6 dB steps.
     */
    public float[] attenuationSteps() throws AirspyHfException {
        try (Arena arena = Arena.ofConfined()) {
            // A length of 0 makes the function store the count, as a uint32_t.
            MemorySegment countSegment = arena.allocate(JAVA_INT);
            check("airspyhf_get_att_steps", Native.invoke(Native.GET_ATT_STEPS, handle(), countSegment, 0));
            int count = countSegment.get(JAVA_INT, 0);
            if (count == 0) {
                return new float[0];
            }
            MemorySegment steps = arena.allocate(JAVA_FLOAT, count);
            check("airspyhf_get_att_steps", Native.invoke(Native.GET_ATT_STEPS, handle(), steps, count));
            return steps.toArray(JAVA_FLOAT);
        }
    }

    /**
     * Sets the attenuator, used when the AGC is off, to the lowest step of
     * {@link #attenuationSteps()} that is at least this many dB. A value above
     * every step selects step 0.
     */
    public void setAttenuation(float decibels) throws AirspyHfException {
        check("airspyhf_set_att", Native.invoke(Native.SET_ATT, handle(), decibels));
    }

    /**
     * Sets the attenuator, used when the AGC is off, by its step number: an
     * index into {@link #attenuationSteps()}.
     */
    public void setAttenuationStep(int step) throws AirspyHfException {
        check("airspyhf_set_hf_att", Native.invoke(Native.SET_HF_ATT, handle(), u8(step)));
    }

    /**
     * Enables or disables the LNA (preamplifier), which adds 6 dB of gain that
     * the library compensates for digitally.
     */
    public void setHfLna(boolean enabled) throws AirspyHfException {
        check("airspyhf_set_hf_lna", Native.invoke(Native.SET_HF_LNA, handle(), flag(enabled)));
    }

    // ---- Bias tee and front end ----
    //
    // Bias tee control and front end options need a device whose firmware
    // supports them, such as the HF+ Discovery or Ranger. Other devices reject
    // them (the HF+ Dual Port does, even with firmware R5.0.1), and these
    // methods throw an AirspyHfException with AirspyHfError.ERROR.

    /**
     * Returns the number of bias tees the device has.
     *
     * @throws AirspyHfException if the firmware does not support bias tees
     */
    public int biasTeeCount() throws AirspyHfException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment count = arena.allocate(JAVA_INT);
            check("airspyhf_get_bias_tee_count", Native.invoke(Native.GET_BIAS_TEE_COUNT, handle(), count));
            return count.get(JAVA_INT, 0);
        }
    }

    /**
     * Returns the name of a bias tee, by index from 0 to {@link #biasTeeCount()} - 1.
     *
     * @throws AirspyHfException if the firmware does not support bias tees
     */
    public String biasTeeName(int index) throws AirspyHfException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocate(Layouts.MAX_NAME_STRING_SIZE);
            check("airspyhf_get_bias_tee_name", Native.invoke(Native.GET_BIAS_TEE_NAME, handle(), index, name,
                (byte) Layouts.MAX_NAME_STRING_SIZE));
            return name.getString(0);
        }
    }

    /**
     * Sends a bias tee setting to the device. This puts DC power on an antenna
     * connector, which can damage equipment not built for it.
     *
     * <p>The value goes to the firmware unchanged. Upstream does not document
     * it beyond its type, a signed byte; on a device with one bias tee, 1 turns
     * it on and 0 off.
     *
     * @throws AirspyHfException if the firmware does not support bias tees
     */
    public void setBiasTee(int value) throws AirspyHfException {
        if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
            throw new IllegalArgumentException("Bias tee value out of 8-bit range: " + value);
        }
        check("airspyhf_set_bias_tee", Native.invoke(Native.SET_BIAS_TEE, handle(), (byte) value));
    }

    /**
     * Returns the front end options last set. The library keeps these itself,
     * so this does not query the device and succeeds on any firmware.
     */
    public Set<FrontendOption> frontendOptions() throws AirspyHfException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment flags = arena.allocate(JAVA_INT);
            check("airspyhf_get_frontend_options", Native.invoke(Native.GET_FRONTEND_OPTIONS, handle(), flags));
            return FrontendOption.fromMask(flags.get(JAVA_INT, 0));
        }
    }

    /**
     * Replaces the front end options. Use {@link #flashConfiguration()} to keep
     * them.
     *
     * @throws AirspyHfException if the firmware does not support front end
     *     options; the previous options stay in effect
     */
    public void setFrontendOptions(Set<FrontendOption> options) throws AirspyHfException {
        Set<FrontendOption> previous = frontendOptions();
        int result = Native.invoke(Native.SET_FRONTEND_OPTIONS, handle(), FrontendOption.toMask(options));
        if (result != 0) {
            // The library records the new options before sending them, and
            // flashConfiguration() would store that record. Setting the previous
            // options again restores it, whether or not the device accepts them.
            Native.invoke(Native.SET_FRONTEND_OPTIONS, handle(), FrontendOption.toMask(previous));
        }
        check("airspyhf_set_frontend_options", result);
    }

    // ---- Calibration ----

    /** Returns the frequency correction in parts per billion. */
    public int calibration() throws AirspyHfException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ppb = arena.allocate(JAVA_INT);
            check("airspyhf_get_calibration", Native.invoke(Native.GET_CALIBRATION, handle(), ppb));
            return ppb.get(JAVA_INT, 0);
        }
    }

    /**
     * Sets the frequency correction in parts per billion and retunes. Use
     * {@link #flashConfiguration()} to keep it.
     */
    public void setCalibration(int ppb) throws AirspyHfException {
        check("airspyhf_set_calibration", Native.invoke(Native.SET_CALIBRATION, handle(), ppb));
    }

    /** Returns the VCTCXO control value, on devices that have a VCTCXO. */
    public int vctcxoCalibration() throws AirspyHfException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_SHORT);
            check("airspyhf_get_vctcxo_calibration", Native.invoke(Native.GET_VCTCXO_CALIBRATION, handle(), value));
            return Short.toUnsignedInt(value.get(JAVA_SHORT, 0));
        }
    }

    /**
     * Sets the VCTCXO control value, from 0 to 65535, on devices that have a
     * VCTCXO. Use {@link #flashConfiguration()} to keep it.
     */
    public void setVctcxoCalibration(int value) throws AirspyHfException {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("VCTCXO value out of 16-bit range: " + value);
        }
        check("airspyhf_set_vctcxo_calibration",
            Native.invoke(Native.SET_VCTCXO_CALIBRATION, handle(), (short) value));
    }

    /**
     * Writes the frequency calibration, VCTCXO calibration and front end options
     * to the device's flash memory, where they are loaded from on open. The
     * device must not be streaming.
     */
    public void flashConfiguration() throws AirspyHfException {
        check("airspyhf_flash_configuration", Native.invoke(Native.FLASH_CONFIGURATION, handle()));
    }

    /**
     * Sets the point of the spectrum, as a fraction of the sample rate from
     * -0.5 to 0.5, where the I/Q balancer optimizes the image rejection.
     */
    public void setOptimalIqCorrectionPoint(float w) throws AirspyHfException {
        check("airspyhf_set_optimal_iq_correction_point",
            Native.invoke(Native.SET_OPTIMAL_IQ_CORRECTION_POINT, handle(), w));
    }

    /** Tunes the I/Q balancer's algorithm. See upstream's {@code iqbalancer.c} for the meaning of each value. */
    public void configureIqBalancer(int buffersToSkip, int fftIntegration, int fftOverlap,
            int correlationIntegration) throws AirspyHfException {
        check("airspyhf_iq_balancer_configure", Native.invoke(Native.IQ_BALANCER_CONFIGURE, handle(),
            buffersToSkip, fftIntegration, fftOverlap, correlationIntegration));
    }

    // ---- Outputs ----

    /** Drives one of the user GPIO outputs high or low, on devices that have them. */
    public void setUserOutput(UserOutput output, boolean high) throws AirspyHfException {
        check("airspyhf_set_user_output",
            Native.invoke(Native.SET_USER_OUTPUT, handle(), output.ordinal(), high ? 1 : 0));
    }

    // ---- Streaming ----

    /**
     * Starts streaming samples to a listener.
     *
     * @throws IllegalStateException if the device is already streaming
     */
    public void startRx(SampleListener sampleListener) throws AirspyHfException {
        Objects.requireNonNull(sampleListener, "sampleListener");
        synchronized (lifecycleLock) {
            MemorySegment device = handle();
            if (isStreaming()) {
                throw new IllegalStateException("Device is already streaming");
            }
            // A listener may have ended the previous stream without stopRx.
            stopRx();

            streamingFailure = null;
            listener = sampleListener;
            try {
                check("airspyhf_start", Native.invoke(Native.START, device, callback, MemorySegment.NULL));
                streamStarted = true;
            } catch (AirspyHfException | RuntimeException e) {
                listener = null;
                // A start that failed part way leaves the device marked as
                // streaming; stopping clears that, and joins only threads that started.
                Native.invoke(Native.STOP, device);
                throw e;
            }
        }
    }

    /**
     * Stops streaming and waits for the listener to return. Does nothing if the
     * device is not streaming.
     *
     * @throws IllegalStateException if called from a {@link SampleListener}
     */
    public void stopRx() throws AirspyHfException {
        rejectCallbackThread("stopRx");
        synchronized (lifecycleLock) {
            if (streamStarted) {
                try {
                    check("airspyhf_stop", Native.invoke(Native.STOP, handle()));
                } finally {
                    streamStarted = false;
                    listener = null;
                    callbackThread = null;
                }
            }
        }
    }

    /** Returns true while samples are being delivered. */
    public boolean isStreaming() throws AirspyHfException {
        return check("airspyhf_is_streaming", Native.invoke(Native.IS_STREAMING, handle())) != 0;
    }

    /** Returns the exception that stopped streaming, or null if there was none. */
    public Throwable streamingFailure() {
        return streamingFailure;
    }

    /**
     * Called by the library's consumer thread through the upcall stub. An
     * exception that escaped from here would terminate the JVM, so all of them
     * are caught.
     */
    @SuppressWarnings("unused")
    private int onTransfer(MemorySegment transferPointer) {
        SampleListener current = listener;
        if (current == null) {
            return 1;
        }
        callbackThread = Thread.currentThread();
        // Closing this arena ends the samples segment's lifetime, so that a
        // listener that keeps it gets an exception and not freed native memory.
        try (Arena callbackArena = Arena.ofConfined()) {
            MemorySegment t = transferPointer.reinterpret(Layouts.TRANSFER.byteSize());
            int count = t.get(JAVA_INT, Layouts.offset(Layouts.TRANSFER, "sample_count"));
            MemorySegment samples = t.get(ADDRESS, Layouts.offset(Layouts.TRANSFER, "samples"))
                .reinterpret(count * Layouts.COMPLEX_FLOAT_SIZE, callbackArena, null);
            long dropped = t.get(JAVA_LONG, Layouts.offset(Layouts.TRANSFER, "dropped_samples"));
            return current.onSamples(new SampleBuffer(samples, count, dropped)) ? 0 : 1;
        } catch (Throwable failure) {
            streamingFailure = failure;
            return 1;
        }
    }

    private void rejectCallbackThread(String method) {
        if (Thread.currentThread() == callbackThread) {
            throw new IllegalStateException(method + " cannot be called from a SampleListener");
        }
    }

    // ---- Lifecycle ----

    /**
     * Stops streaming and releases the device. Closing again does nothing.
     *
     * @throws IllegalStateException if called from a {@link SampleListener}
     */
    @Override
    public void close() throws AirspyHfException {
        rejectCallbackThread("close");
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            try {
                stopRx();
            } finally {
                closed = true;
                check("airspyhf_close", Native.invoke(Native.CLOSE, handle));
            }
        }
    }

    private MemorySegment handle() {
        if (closed) {
            throw new IllegalStateException("Device is closed");
        }
        return handle;
    }

    private static byte flag(boolean value) {
        return (byte) (value ? 1 : 0);
    }

    private static byte u8(int value) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException("Value out of 8-bit range: " + value);
        }
        return (byte) value;
    }

    private static int u32(long value) {
        if (value < 0 || value > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("Value out of 32-bit range: " + value);
        }
        return (int) value;
    }
}
