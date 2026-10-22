#!/usr/bin/env bash
# Run the unit tests (launcher and Smart Home, on the JVM), then the UI tests on the TV. Options go to both UI suites:
#   ./run-tests.sh              everything
#   ./run-tests.sh -k dock      only UI tests whose name contains "dock"
#   ./run-tests.sh --reboot     also reboot the TV and check it comes back clean
#   ./run-tests.sh --mac-only   no TV: unit tests, home.sh tests and read-only checks on the real network
set -uo pipefail
cd "$(dirname "$0")"
if [ "${1:-}" != "--mac-only" ]; then . lib/tv.sh; fi   # the UI tests drive this TV
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
echo "== Unit tests"
OUT=$(cd launcher && ./gradlew test -q --console=plain 2>&1); GRADLE=$?
printf '%s\n' "$OUT" | grep -v -i -E 'deprecat|^Note|^$' || true
if [ "$GRADLE" -eq 0 ]; then
  echo "  PASS  launcher unit tests ($(ls launcher/app/build/test-results/testReleaseUnitTest/*.xml launcher/app/build/test-results/testDebugUnitTest/*.xml 2>/dev/null | xargs cat | grep -o 'tests="[0-9]*"' | grep -o '[0-9]*' | paste -sd+ - | bc) tests)"
else
  echo "  FAIL  launcher unit tests"; UNIT_FAILED=1
fi
OUT=$(cd smarthome && ./gradlew testReleaseUnitTest -q --console=plain 2>&1); GRADLE=$?
printf '%s\n' "$OUT" | grep -v -i -E 'deprecat|^Note|^$' || true
if [ "$GRADLE" -eq 0 ]; then
  echo "  PASS  smart home unit tests ($(cat smarthome/app/build/test-results/testReleaseUnitTest/*.xml | grep -o 'tests="[0-9]*"' | grep -o '[0-9]*' | paste -sd+ - | bc) tests)"
else
  echo "  FAIL  smart home unit tests"; UNIT_FAILED=1
fi
echo "  ----  home.sh"
python3 -I tests/home_cli_test.py 2>&1 | tail -1 | sed 's/^OK$/  PASS  home.sh tests/' | grep -q PASS && echo "  PASS  home.sh tests ($(python3 -I tests/home_cli_test.py 2>&1 | grep -o 'Ran [0-9]*' | grep -o '[0-9]*') tests)" || { echo "  FAIL  home.sh tests"; UNIT_FAILED=1; }
if [ "${1:-}" = "--mac-only" ]; then
  # Read-only checks against the real network: discovery, plug state, re-find, camera scan. Nothing is switched.
  echo "== Live read-only checks (Mac)"
  (cd smarthome && KASA_LIVE=1 ./gradlew testReleaseUnitTest --tests '*live*' --rerun-tasks -q -i --console=plain 2>&1 | grep -E 'LIVE|FAILED')
  [ "${UNIT_FAILED:-0}" = 0 ]; exit $?
fi
echo "== Launcher UI tests on the TV"
python3 -I tests/ui_test.py "$@"; UI=$?
echo "== Smart Home UI tests on the TV"
python3 -I tests/smarthome_test.py "$@"; SH=$?
[ "${UNIT_FAILED:-0}" = 0 ] && [ "$UI" = 0 ] && [ "$SH" = 0 ]
