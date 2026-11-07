package io.github.dsheirer.airspyhf;

/**
 * Receives samples while a device is streaming.
 *
 * <p>Runs on the library's high-priority consumer thread, so it should return
 * quickly: copy the samples and hand them to another thread for slow work. It
 * must not call {@link AirspyHfDevice#stopRx()} or {@link AirspyHfDevice#close()};
 * return false to stop instead. If it throws, streaming stops and the exception
 * is available from {@link AirspyHfDevice#streamingFailure()}.
 */
@FunctionalInterface
public interface SampleListener {
    /**
     * Handles a block of samples.
     *
     * @return true to keep streaming, false to stop
     */
    boolean onSamples(SampleBuffer buffer);
}
