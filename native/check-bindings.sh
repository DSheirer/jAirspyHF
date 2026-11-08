#!/usr/bin/env bash
# Compares the functions in airspyhf.h with the bindings in Native.java, so a
# new upstream function fails the build rather than being silently unavailable.
#
# Usage: native/check-bindings.sh <path-to-airspyhf.h>
set -euo pipefail
# sort and comm must agree on collation order whatever the caller's locale.
export LC_ALL=C

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HEADER="$1"
BINDINGS="$ROOT/src/main/java/io/github/dsheirer/airspyhf/internal/Native.java"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Declarations look like: extern ADDAPI int ADDCALL name(
grep -E '^extern ADDAPI' "$HEADER" \
    | sed -E 's/.*ADDCALL[ ]+([a-z0-9_]+)[ ]*\(.*/\1/' | sort -u > "$WORK/header"
grep -oE '"airspyhf_[a-z0-9_]+"' "$BINDINGS" | tr -d '"' | sort -u > "$WORK/bindings"

MISSING="$(comm -23 "$WORK/header" "$WORK/bindings")"
STALE="$(comm -13 "$WORK/header" "$WORK/bindings")"
if [ -n "$MISSING" ] || [ -n "$STALE" ]; then
    [ -z "$MISSING" ] || printf 'Functions in airspyhf.h without a binding:\n%s\n' "$MISSING" >&2
    [ -z "$STALE" ] || printf 'Bindings not in airspyhf.h:\n%s\n' "$STALE" >&2
    exit 1
fi
echo "Bindings match airspyhf.h ($(wc -l < "$WORK/header" | tr -d ' ') functions)"
