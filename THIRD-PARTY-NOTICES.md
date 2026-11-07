# Third-party notices

jAirspyHF is licensed under the Apache License 2.0. The jar also contains native
libraries from the projects below, which keep their own licenses. Full license
texts are in `META-INF/licenses/` inside the jar.

## libairspyhf

Source: <https://github.com/airspy/airspyhf>

The library sources are licensed under the BSD 3-Clause License. See
`META-INF/licenses/libairspyhf-LICENSE`. Upstream's GPL-2.0 license covers its
build files and command line tools, which jAirspyHF does not use or ship.
jAirspyHF is an independent project and is not endorsed by Airspy or its
contributors.

## libusb

Source: <https://github.com/libusb/libusb>

Licensed under the GNU Lesser General Public License version 2.1 or later. See
`META-INF/licenses/libusb-COPYING`. The exact version in each jar is recorded in
`jairspyhf/native/<platform>/build-info.properties`, and
`native/versions.env` in this repository records the release it was built from.

libusb is shipped as a separate shared library so it can be replaced: point the
`jairspyhf.library.path` system property at a directory containing your own
build along with libairspyhf.

## pthreads4w (Windows only)

Source: <https://github.com/jwinarske/pthreads4w>

Licensed under the Apache License 2.0 and compiled into `airspyhf.dll`. See
`META-INF/licenses/pthreads4w-LICENSE` and `META-INF/licenses/pthreads4w-NOTICE`.
