package io.github.dsheirer.airspyhf;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Samples delivered to a {@link SampleListener}: interleaved 32-bit float I/Q
 * pairs, with full scale near -1 and 1.
 *
 * <p>{@link #samples()} points at memory owned by the native library. It may be
 * used only on the callback's thread and only until the callback returns; after
 * that, access throws {@link IllegalStateException}. Use one of the copying
 * methods, or {@link MemorySegment#copy}, to keep the data.
 *
 * @param samples sample memory, in native byte order, {@code 8 * sampleCount} bytes long
 * @param sampleCount number of I/Q pairs
 * @param droppedSamples I/Q pairs the library dropped, since the previous buffer,
 *     because the listener did not keep up
 */
public record SampleBuffer(MemorySegment samples, int sampleCount, long droppedSamples) {

    /** Copies the samples to a new array of {@code 2 * sampleCount} floats: I, Q, I, Q, ... */
    public float[] copyFloats() {
        return samples.toArray(ValueLayout.JAVA_FLOAT);
    }

    /**
     * Copies the samples into an array, which must hold at least
     * {@code 2 * sampleCount} floats.
     */
    public void copyFloats(float[] destination) {
        MemorySegment.copy(samples, ValueLayout.JAVA_FLOAT, 0, destination, 0, sampleCount * 2);
    }
}
