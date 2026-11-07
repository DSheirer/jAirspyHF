package io.github.dsheirer.airspyhf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Receive-only tests against a real device. Enable with {@code -PhardwareTest=true}.
 * Nothing is written to the device's flash memory.
 */
@EnabledIfSystemProperty(named = "airspyhf.hardware.test", matches = "true")
class HardwareTest {
    private static final long FREQUENCY = 10_000_000L;

    private static void configure(AirspyHfDevice device) throws AirspyHfException {
        long[] rates = device.sampleRates();
        device.setSampleRate(rates[0]);
        device.setFrequency(FREQUENCY);
    }

    @Test
    void readsDeviceInformation() throws AirspyHfException {
        long[] serials = AirspyHf.listDevices();
        assertTrue(serials.length > 0, "No device found");
        try (AirspyHfDevice device = AirspyHf.open(serials[0])) {
            assertEquals(serials[0], device.boardInfo().serialNumber());
            assertFalse(device.firmwareVersion().isBlank());
            assertTrue(device.sampleRates().length > 0);
            assertTrue(device.outputSize() > 0);
        }
    }

    @Test
    void appliesSettings() throws AirspyHfException {
        try (AirspyHfDevice device = AirspyHf.open()) {
            configure(device);
            device.setFrequency(FREQUENCY + 0.5);
            device.setHfAgc(false);
            float[] steps = device.attenuationSteps();
            assertTrue(steps.length > 0);
            device.setAttenuationStep(steps.length - 1);
            device.setAttenuation(steps[0]);
            device.setHfLna(true);
            device.setHfAgc(true);
            device.setHfAgcThreshold(AgcThreshold.LOW);
            device.setLibDsp(true);
            int ppb = device.calibration();
            device.setCalibration(ppb);
            assertEquals(ppb, device.calibration());
            device.isLowIf();
            assertThrows(IllegalArgumentException.class, () -> device.setAttenuationStep(256));
            appliesNewerFirmwareSettings(device);
            assertThrows(IllegalArgumentException.class, () -> device.setSampleRate(3));
        }
    }

    /**
     * Front end options and bias tees need newer firmware; older firmware must
     * fail cleanly and leave the recorded front end options unchanged.
     */
    private static void appliesNewerFirmwareSettings(AirspyHfDevice device) throws AirspyHfException {
        Set<FrontendOption> options = device.frontendOptions();
        Set<FrontendOption> other = options.isEmpty() ? EnumSet.allOf(FrontendOption.class)
            : EnumSet.noneOf(FrontendOption.class);
        try {
            device.setFrontendOptions(other);
            assertEquals(other, device.frontendOptions());
            device.setFrontendOptions(options);
        } catch (AirspyHfException e) {
            assertEquals(AirspyHfError.ERROR, e.error());
            assertEquals(options, device.frontendOptions());
        }

        int biasTees;
        try {
            biasTees = device.biasTeeCount();
        } catch (AirspyHfException e) {
            assertEquals(AirspyHfError.ERROR, e.error());
            biasTees = 0;
        }
        for (int i = 0; i < biasTees; i++) {
            assertFalse(device.biasTeeName(i).isBlank());
        }
    }

    @Test
    void streamsSamplesAndStops() throws Exception {
        try (AirspyHfDevice device = AirspyHf.open()) {
            configure(device);
            int expected = device.outputSize();
            CountDownLatch received = new CountDownLatch(50);
            AtomicReference<float[]> last = new AtomicReference<>();
            AtomicInteger counts = new AtomicInteger();
            device.startRx(buffer -> {
                counts.set(buffer.sampleCount());
                last.set(buffer.copyFloats());
                received.countDown();
                return true;
            });
            assertTrue(received.await(10, TimeUnit.SECONDS), "Samples did not arrive");
            assertTrue(device.isStreaming());
            device.stopRx();
            assertFalse(device.isStreaming());
            assertNull(device.streamingFailure());
            assertEquals(expected, counts.get());
            float[] samples = last.get();
            assertEquals(expected * 2, samples.length);
            boolean nonZero = false;
            for (float sample : samples) {
                assertTrue(Float.isFinite(sample));
                nonZero |= sample != 0;
            }
            assertTrue(nonZero, "Samples were all zero");
        }
    }

    @Test
    void canRestartAfterListenerEndedStream() throws Exception {
        try (AirspyHfDevice device = AirspyHf.open()) {
            configure(device);
            for (int run = 0; run < 3; run++) {
                CountDownLatch received = new CountDownLatch(1);
                device.startRx(buffer -> {
                    received.countDown();
                    return false;
                });
                assertTrue(received.await(10, TimeUnit.SECONDS), "No samples in run " + run);
                for (int i = 0; i < 200 && device.isStreaming(); i++) {
                    Thread.sleep(5);
                }
            }
            assertNull(device.streamingFailure());
        }
    }

    @Test
    void listenerExceptionStopsStreamInsteadOfCrashing() throws Exception {
        try (AirspyHfDevice device = AirspyHf.open()) {
            configure(device);
            CountDownLatch called = new CountDownLatch(1);
            device.startRx(buffer -> {
                called.countDown();
                throw new IllegalArgumentException("expected");
            });
            assertTrue(called.await(10, TimeUnit.SECONDS));
            for (int i = 0; i < 100 && device.streamingFailure() == null; i++) {
                Thread.sleep(10);
            }
            assertInstanceOf(IllegalArgumentException.class, device.streamingFailure());
            device.stopRx();
        }
    }

    @Test
    void samplesCannotBeUsedAfterCallbackReturns() throws Exception {
        try (AirspyHfDevice device = AirspyHf.open()) {
            configure(device);
            AtomicReference<MemorySegment> kept = new AtomicReference<>();
            CountDownLatch called = new CountDownLatch(1);
            device.startRx(buffer -> {
                kept.set(buffer.samples());
                called.countDown();
                return false;
            });
            assertTrue(called.await(10, TimeUnit.SECONDS));
            device.stopRx();
            assertThrows(RuntimeException.class, () -> kept.get().get(ValueLayout.JAVA_BYTE, 0));
        }
    }

    @Test
    @SuppressWarnings("try")
    void stoppingFromListenerIsRejected() throws Exception {
        try (AirspyHfDevice device = AirspyHf.open()) {
            configure(device);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            CountDownLatch called = new CountDownLatch(1);
            device.startRx(buffer -> {
                try {
                    device.close();
                } catch (Throwable t) {
                    thrown.set(t);
                }
                called.countDown();
                return false;
            });
            assertTrue(called.await(10, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, thrown.get());
        }
    }
}
