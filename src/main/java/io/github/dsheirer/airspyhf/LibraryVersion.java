package io.github.dsheirer.airspyhf;

/** Version of the loaded native library. */
public record LibraryVersion(long major, long minor, long revision) {
    @Override
    public String toString() {
        return major + "." + minor + "." + revision;
    }
}
