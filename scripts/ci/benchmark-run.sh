#!/usr/bin/env bash
# Runs :benchmark's tests on an already booted emulator (.github/workflows/benchmark.yml calls it
# from reactivecircus/android-emulator-runner; docs/BYGGE.md "Prestanda").
#
#   scripts/ci/benchmark-run.sh benchmarks   StartupBenchmark + RecipeListScrollBenchmark
#   scripts/ci/benchmark-run.sh profile      BaselineProfileGenerator
#
# Expects the APKs built beforehand by
#   ./gradlew :app:assembleSideloadMinified :benchmark:assembleMinified
# (for "profile" with -Ptandem.baselineProfile.generate=true) and writes to $BENCHMARK_OUT
# (default benchmark-results/):
#   instrumentation.txt   the test runner's output
#   device/               everything the tests wrote (benchmark JSON, traces, profiles)
#   baseline-prof.txt     ("profile" only) the generated baseline profile
#   logcat.txt            the device log of the run
#
# Never run this on the production server: it needs an emulator (CI only).
set -uo pipefail

MODE="${1:?usage: benchmark-run.sh benchmarks|profile}"
APP_PACKAGE=casa.moller.tandem.minified
TEST_PACKAGE=casa.moller.tandem.benchmark
RUNNER=androidx.test.runner.AndroidJUnitRunner
OUT="${BENCHMARK_OUT:-benchmark-results}"
DEVICE_DIR="/sdcard/Android/media/$TEST_PACKAGE"

case "$MODE" in
  benchmarks) classes="$TEST_PACKAGE.StartupBenchmark,$TEST_PACKAGE.RecipeListScrollBenchmark" ;;
  profile) classes="$TEST_PACKAGE.BaselineProfileGenerator" ;;
  *) echo "::error::Unknown mode $MODE"; exit 2 ;;
esac

mkdir -p "$OUT"
app_apk="$(find app/build/outputs/apk/sideload/minified -name '*.apk' | head -n 1)"
test_apk="$(find benchmark/build/outputs/apk/minified -name '*.apk' | head -n 1)"
if [ -z "$app_apk" ] || [ -z "$test_apk" ]; then
  echo "::error::APKs missing: build :app:assembleSideloadMinified and :benchmark:assembleMinified first"
  exit 1
fi

adb wait-for-device
# google_apis images allow root: Macrobenchmark then also drops the page cache before each cold
# start, and the CPU clocks can be read. Without root it still runs (and says so in its output).
adb root > /dev/null 2>&1 && sleep 3 && adb wait-for-device
echo "Device: API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), $(adb shell getprop ro.product.cpu.abi | tr -d '\r'), root: $(adb shell id -u | tr -d '\r')"
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure show_ime_with_hard_keyboard 0 || true
# Let the freshly booted emulator settle (dexopt of system apps), as the smoke test does.
sleep 30
adb shell input keyevent KEYCODE_HOME || true
adb install -r "$app_apk" || { echo "::error::Installing $app_apk failed"; exit 1; }
adb install -r "$test_apk" || { echo "::error::Installing $test_apk failed"; exit 1; }
adb shell rm -rf "$DEVICE_DIR" > /dev/null 2>&1 || true

adb logcat -c || true
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
logcat_pid=$!

echo "::group::Instrumentation ($MODE)"
# EMULATOR: Macrobenchmark refuses emulators unless told that the numbers are a trend only.
adb shell am instrument -w \
  -e class "$classes" \
  -e androidx.benchmark.suppressErrors EMULATOR,LOW-BATTERY,UNLOCKED \
  -e additionalTestOutputDir "$DEVICE_DIR" \
  "$TEST_PACKAGE/$RUNNER" 2>&1 | tr -d '\r' | tee "$OUT/instrumentation.txt"
echo "::endgroup::"

adb pull "$DEVICE_DIR" "$OUT/device" > /dev/null 2>&1 || true
adb exec-out screencap -p > "$OUT/final-screen.png" 2> /dev/null || true
kill "$logcat_pid" 2> /dev/null || true
wait "$logcat_pid" 2> /dev/null || true
echo "Files from the device:"
find "$OUT/device" -type f 2> /dev/null | sed 's/^/  /'

passed=false
grep -q '^OK (' "$OUT/instrumentation.txt" && ! grep -q -e 'FAILURES!!!' -e 'INSTRUMENTATION_FAILED' -e 'Process crashed' \
  "$OUT/instrumentation.txt" && passed=true

if [ "$MODE" = profile ]; then
  profile="$(find "$OUT/device" -type f -name '*-baseline-prof*.txt' 2> /dev/null | grep -v -- '-startup-prof' | sort | tail -n 1)"
  if [ -n "$profile" ] && [ -s "$profile" ]; then
    cp "$profile" "$OUT/baseline-prof.txt"
    echo "Baseline profile: $(wc -l < "$OUT/baseline-prof.txt") rules from $(basename "$profile")"
  else
    echo "::error::The generator wrote no baseline profile"
    passed=false
  fi
fi

if [ "$passed" = true ]; then
  echo "Benchmark run ($MODE) passed."
  exit 0
fi
echo "::error title=Benchmark run failed::$MODE (artifact benchmark-$MODE)"
exit 1
