# jAirspyHF

Java bindings for [libairspyhf](https://github.com/airspy/airspyhf), the driver
for the Airspy HF+ Dual Port, HF+ Discovery and Ranger software-defined radios,
built on the Java Foreign Function & Memory API. The jar contains libairspyhf
for every supported platform and needs one other jar,
[jlibusb](https://github.com/DSheirer/jlibusb), which supplies libusb. There is
nothing to install.

| OS | x86-64 | aarch64 |
|---------|----|----|
| Linux (glibc 2.34+, such as Ubuntu 22.04, Debian 12, RHEL 9) | ✓ | ✓ |
| macOS 11+ | ✓ | ✓ |
| Windows | ✓ | ✓ |

Requires Java 23 or later.

## Getting it

Download `jairspyhf-<version>.jar` from
[Releases](https://github.com/DSheirer/jAirspyHF/releases), and the
`jlibusb-<version>.jar` it was built with from
[jlibusb releases](https://github.com/DSheirer/jlibusb/releases). The rolling
[`nightly`](https://github.com/DSheirer/jAirspyHF/releases/tag/nightly)
pre-release tracks `main` of this project and `master` of upstream.

Check that your machine can see a device:

```
java -jar jairspyhf-<version>.jar
```

## Using it

```java
try (AirspyHfDevice device = AirspyHf.open()) {
    device.setSampleRate(device.sampleRates()[0]);
    device.setFrequency(7_100_000L);
    device.setHfAgc(true);

    device.startRx(buffer -> {
        float[] iq = buffer.copyFloats();   // interleaved I, Q
        queue.offer(iq);
        return true;                        // false stops the stream
    });
    Thread.sleep(10_000);
}
```

`AirspyHf.listDevices()` returns the serial numbers of the connected devices,
and `AirspyHf.open(long serial)` opens one of them. Samples are always 32-bit
float I/Q. By default the library corrects I/Q imbalance and removes the low IF;
`setLibDsp(false)` turns that off. `setFrequency(double)` tunes with sub-hertz
resolution.

With the AGC off, set the attenuator with `setAttenuation(dB)`, or by step from
`attenuationSteps()`. `flashConfiguration()` stores the frequency calibration,
VCTCXO calibration and front end options in the device.

`SampleListener` runs on the driver's high-priority consumer thread, so hand
slow work to another thread. `buffer.samples()` gives zero-copy access, valid
only until the listener returns; later use throws an exception rather than
reading freed memory. An exception thrown by a listener stops the stream and is
available from `streamingFailure()`.

### Native access

Java prints a warning, which future versions will turn into an error, unless
you allow native access:

```
java --enable-native-access=ALL-UNNAMED -cp app.jar:jairspyhf.jar:jlibusb.jar my.App
```

On the module path, use `--enable-native-access=io.github.dsheirer.airspyhf,io.github.dsheirer.libusb`. In
your own executable jar, add `Enable-Native-Access: ALL-UNNAMED` to the manifest.

### USB access

- **Linux:** install upstream's
  [udev rules](https://github.com/airspy/airspyhf/blob/master/tools/52-airspyhf.rules)
  to use a device without root.
- **Windows:** the device needs the WinUSB driver.

### Native library location

libairspyhf is extracted at startup into the private temporary directory that
jlibusb extracts libusb to, so the process holds one copy of libusb shared with
any other library built on jlibusb. Set `-Djairspyhf.library.path=<dir>` to load
libairspyhf from a directory instead, and `-Djlibusb.library.path=<dir>` to
supply your own build of libusb. If the temporary directory is mounted
`noexec`, point `-Djava.io.tmpdir` elsewhere.

## Building

You need JDK 23+, CMake 3.16+, a C compiler, make and git. On Windows use Visual
Studio and 7-Zip.

```
./native/build-unix.sh                                # Linux and macOS
pwsh native/build-windows.ps1 -Arch x64               # Windows; or -Arch ARM64
./gradlew build                                       # jar in build/libs
./gradlew test -PhardwareTest=true                    # also test a connected device
```

A local jar contains only your platform. The hardware tests are receive-only and
write nothing to the device's flash memory.

The native build compiles upstream's library sources with
[`native/CMakeLists.txt`](native/CMakeLists.txt) rather than upstream's CMake
files. On Windows it compiles
[pthreads4w](https://github.com/jwinarske/pthreads4w) into `airspyhf.dll`,
since libairspyhf uses POSIX threads.

## Releases

jAirspyHF versions follow upstream: jAirspyHF 1.8.1 wraps libairspyhf 1.8.1.
Upstream's last git tag is 1.6.8; later versions are version bumps on
`master`, so [`native/versions.env`](native/versions.env) pins both the
upstream commit (`AIRSPYHF_REF`) and the version it reports
(`AIRSPYHF_VERSION`). The build fails if they disagree.

| Trigger | What happens |
|---------|--------------|
| Pull request | Builds and tests all six platforms; publishes nothing |
| Merge to `main` | Replaces the `nightly` pre-release, built against upstream `master` |
| Daily schedule | The same, if this project or upstream has changed |
| Tag `vX.Y.Z` | Publishes a release built from the pinned upstream commit |

To release, tag the pinned version: `git tag v1.8.1 && git push origin v1.8.1`.
The workflow rejects a tag that does not match the pin. For a Java-only fix on
the same upstream version add a fourth number, such as `v1.8.1.1`; for a
pre-release add a suffix, such as `v1.8.1-rc1`.

To adopt a new upstream release, change `AIRSPYHF_REF` and `AIRSPYHF_VERSION`
in `native/versions.env`, which also moves the jAirspyHF version. If upstream
added functions, the build lists them; add them to `Native.java`.

## License

Apache License 2.0. The bundled native libraries have their own licenses: see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
