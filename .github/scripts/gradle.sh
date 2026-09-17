#!/usr/bin/env bash
#
# Run Gradle, retrying only when the failure looks like transient artifact resolution.
#
# The `net.neoforged.moddev.repositories` settings plugin injects maven.neoforged.net ahead of the
# repositories declared in settings.gradle, and that host proxies Maven Central. So every
# dependency is asked of it first - including plain Central artifacts like
# com.mysql:mysql-connector-j - and one 502 from it fails the whole build even though the artifact
# itself is perfectly available. That is not worth a red build, but a compile error is, so this
# only retries when the log actually shows a network or resolution failure.
#
# Usage: .github/scripts/gradle.sh <gradle args...>
set -uo pipefail

ATTEMPTS="${GRADLE_ATTEMPTS:-3}"

TRANSIENT='Could not GET|Could not download|Could not resolve|Bad Gateway|Service Unavailable|Gateway Time-?out|status code 50[0-9]|Connection reset|Read timed out|Connect(ion)? timed out|Premature end of|Remote host (terminated|closed)|Broken pipe'

log="$(mktemp)"
trap 'rm -f "$log"' EXIT

for attempt in $(seq 1 "$ATTEMPTS"); do
    echo "::group::gradle $* (attempt ${attempt}/${ATTEMPTS})"
    # Read PIPESTATUS rather than wrapping this in an `if`: a failed `if` condition with no `else`
    # leaves $? at 0, which would report every real build failure as a success.
    ./gradlew "$@" 2>&1 | tee "$log"
    status="${PIPESTATUS[0]}"
    echo "::endgroup::"

    if [ "$status" -eq 0 ]; then
        exit 0
    fi

    if ! grep -qiE "$TRANSIENT" "$log"; then
        echo "Gradle failed for a reason that is not transient; not retrying."
        exit "$status"
    fi

    if [ "$attempt" -lt "$ATTEMPTS" ]; then
        delay=$((attempt * 30))
        echo "Transient resolution failure. Retrying in ${delay}s."
        sleep "$delay"
    fi
done

echo "::error::gradle $* still failing after ${ATTEMPTS} attempts; see the log above."
exit 1
