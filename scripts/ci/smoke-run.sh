#!/usr/bin/env bash
# Runs the release smoke test on an already booted emulator (.github/workflows/smoke.yml calls
# it from reactivecircus/android-emulator-runner; see docs/BYGGE.md "Release-röktest").
#
# Expects the APKs built beforehand by
#   ./gradlew :app:assembleSideloadMinified :smoke:assembleMinified
# and writes everything worth keeping to $SMOKE_OUT (default smoke-results/):
#   instrumentation.txt   the test runner's output
#   logcat.txt            the full device log of the run
#   crash.txt             every FATAL EXCEPTION block of the app (obfuscated, as on a phone)
#   crash-retraced.txt    the same, retraced with this build's R8 mapping file
#   app-logcat.txt        the log lines of the app's own processes
#   workers.txt           WorkManager results of core:sync's workers (must contain a SUCCESS)
#   missing-classes.txt   ClassNotFound/NoSuchMethod/... in the app's processes (must be empty)
#   device/               screenshots and window hierarchies the test saved
#   final-screen.png, final-window.xml   the screen when the run ended
#
# Exit status: 0 only if every test passed, the app never crashed, a sync worker finished and
# nothing was missing at runtime.
#
# Never run this on the production server: it needs an emulator (CI only).
set -uo pipefail

APP_PACKAGE=casa.moller.tandem.minified
TEST_PACKAGE=casa.moller.tandem.smoke
RUNNER=androidx.test.runner.AndroidJUnitRunner
OUT="${SMOKE_OUT:-smoke-results}"
MAPPING=app/build/outputs/mapping/sideloadMinified/mapping.txt

mkdir -p "$OUT"
app_apk="$(find app/build/outputs/apk/sideload/minified -name '*.apk' | head -n 1)"
test_apk="$(find smoke/build/outputs/apk/minified -name '*.apk' | head -n 1)"
if [ -z "$app_apk" ] || [ -z "$test_apk" ]; then
  echo "::error::APKs missing: build :app:assembleSideloadMinified and :smoke:assembleMinified first"
  exit 1
fi

adb wait-for-device
echo "Device: API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
# A freshly booted CI emulator is slow (2 vCPU, software GPU) and its system apps (System UI,
# Play services) sometimes hit ANRs. Hide those dialogs and let the boot settle; the test also
# taps "Wait" on such a dialog if one still shows. A crash or ANR of Tandem is still caught
# below (logcat) and by the test.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure show_ime_with_hard_keyboard 0 || true
sleep 30
adb shell input keyevent KEYCODE_HOME || true
adb install -r "$app_apk" || { echo "::error::Installing $app_apk failed"; exit 1; }
adb install -r "$test_apk" || { echo "::error::Installing $test_apk failed"; exit 1; }

adb logcat -c || true
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
logcat_pid=$!

echo "::group::Instrumentation"
# SMOKE_TESTS (a by-hand run, smoke.yml's "tests" input): only these classes; empty = every test.
filter=()
if [ -n "${SMOKE_TESTS:-}" ]; then
  case "$SMOKE_TESTS" in
    *[!A-Za-z0-9_.,#]*) echo "::error::SMOKE_TESTS may only name test classes"; exit 1 ;;
  esac
  echo "Only: $SMOKE_TESTS"
  filter=(-e class "$SMOKE_TESTS")
fi
adb shell am instrument -w "${filter[@]}" "$TEST_PACKAGE/$RUNNER" 2>&1 | tr -d '\r' | tee "$OUT/instrumentation.txt"
echo "::endgroup::"

# Evidence first, verdict later.
adb exec-out screencap -p > "$OUT/final-screen.png" 2> /dev/null || true
adb shell uiautomator dump /sdcard/final-window.xml > /dev/null 2>&1 \
  && adb pull /sdcard/final-window.xml "$OUT/final-window.xml" > /dev/null 2>&1 || true
adb pull "/sdcard/Android/media/$TEST_PACKAGE/smoke" "$OUT/device" > /dev/null 2>&1 || true
sleep 2
kill "$logcat_pid" 2> /dev/null || true
wait "$logcat_pid" 2> /dev/null || true

# Every "FATAL EXCEPTION" block of the app's process: the AndroidRuntime lines that follow
# "Process: casa.moller.tandem.minified", without the logcat prefix.
awk -v pkg="$APP_PACKAGE" '
  / E AndroidRuntime: / {
    line = $0
    sub(/^.* E AndroidRuntime: /, "", line)
    if (line ~ /^FATAL EXCEPTION/) { pending = line; inside = 0; next }
    if (pending != "") {
      inside = (line ~ ("^Process: " pkg "(,|$)"))
      if (inside) { print ""; print pending }
      pending = ""
    }
    if (inside) print line
    next
  }
' "$OUT/logcat.txt" > "$OUT/crash.txt"

crashed=false
if [ -s "$OUT/crash.txt" ]; then
  crashed=true
  # Retrace with the R8 that produced the mapping: AGP's own r8.jar from the Gradle cache.
  r8_jar="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches" -path '*com.android.tools/r8/*' -name 'r8-*.jar' 2> /dev/null \
    | grep -v -e sources -e javadoc | sort -V | tail -n 1)"
  if [ -n "$r8_jar" ]; then
    java -cp "$r8_jar" com.android.tools.r8.retrace.Retrace "$MAPPING" "$OUT/crash.txt" > "$OUT/crash-retraced.txt" 2>&1
  else
    retrace="$(find "${ANDROID_HOME:-/usr/local/lib/android/sdk}/cmdline-tools" -path '*/bin/retrace' 2> /dev/null | head -n 1)"
    [ -n "$retrace" ] && "$retrace" "$MAPPING" "$OUT/crash.txt" > "$OUT/crash-retraced.txt" 2>&1
  fi
  echo "::error title=The app crashed::$(head -n 3 "$OUT/crash.txt" | tr '\n' ' ')"
  echo "::group::Crash (obfuscated)"; cat "$OUT/crash.txt"; echo "::endgroup::"
  echo "::group::Crash (retraced)"; cat "$OUT/crash-retraced.txt" 2> /dev/null; echo "::endgroup::"
fi

# An ANR of the app counts like a crash (the hidden dialog would not stop the test otherwise).
if grep -q "ANR in ${APP_PACKAGE}\b" "$OUT/logcat.txt"; then
  crashed=true
  grep -A 20 "ANR in ${APP_PACKAGE}\b" "$OUT/logcat.txt" >> "$OUT/crash.txt"
  echo "::error title=The app did not respond (ANR)::see crash.txt in smoke-results"
fi

# Phase 1: the minified app must start its WorkManager workers (core:sync's @HiltWorker classes,
# created by HiltWorkerFactory; their names are kept by core/sync/consumer-rules.pro), and nothing
# R8 removed may be missing at runtime, even where the app catches the error (a worker, Coil,
# kotlinx.serialization). Only the app's own processes count (pids from "Start proc").
app_pids=" $(grep -oE "Start proc [0-9]+:${APP_PACKAGE}/" "$OUT/logcat.txt" | grep -oE '[0-9]+' | sort -u | tr '\n' ' ')"
awk -v pids="$app_pids" 'index(pids, " " $3 " ") > 0' "$OUT/logcat.txt" > "$OUT/app-logcat.txt"
grep -E 'ClassNotFoundException|NoClassDefFoundError|NoSuchMethodError|NoSuchMethodException|NoSuchFieldError|Could not instantiate|Could not create Worker' \
  "$OUT/app-logcat.txt" > "$OUT/missing-classes.txt" || true
grep -E 'WM-WorkerWrapper.*Worker result (SUCCESS|RETRY|FAILURE) for .*casa\.moller\.tandem\.core\.sync\.work\.' \
  "$OUT/app-logcat.txt" > "$OUT/workers.txt" || true
echo "::group::WorkManager workers of the app (${app_pids# })"
cat "$OUT/workers.txt"
echo "::endgroup::"
echo "::group::Fake Tandoor requests"
grep -E ' FakeTandoor: ' "$OUT/logcat.txt" | sed 's/^.* FakeTandoor: //' || true
echo "::endgroup::"
workers_ok=false
if [ -s "$OUT/workers.txt" ] && grep -q 'Worker result SUCCESS' "$OUT/workers.txt"; then
  workers_ok=true
else
  echo "::error title=No sync worker ran::WorkManager never finished one of core:sync's workers in the minified app (workers.txt)"
fi
classes_ok=true
if [ -s "$OUT/missing-classes.txt" ]; then
  classes_ok=false
  echo "::error title=Missing class or method at runtime::$(head -n 3 "$OUT/missing-classes.txt" | tr '\n' ' ')"
  echo "::group::Missing classes or methods (app processes)"; cat "$OUT/missing-classes.txt"; echo "::endgroup::"
fi

passed=false
grep -q '^OK (' "$OUT/instrumentation.txt" && ! grep -q -e 'FAILURES!!!' -e 'INSTRUMENTATION_FAILED' -e 'Process crashed' \
  "$OUT/instrumentation.txt" && passed=true

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Release smoke test"
    echo "- Tests: $([ "$passed" = true ] && echo passed || echo '**FAILED**')"
    echo "- App crash: $([ "$crashed" = true ] && echo '**yes**' || echo none)"
    echo "- Sync workers finished: $(grep -c 'Worker result SUCCESS' "$OUT/workers.txt" || true)"
    echo "- Missing classes or methods: $([ "$classes_ok" = true ] && echo none || echo '**yes**')"
    if [ "$crashed" = true ]; then
      echo
      echo '<details><summary>Retraced crash</summary>'
      echo
      echo '```'
      head -n 80 "$OUT/crash-retraced.txt" 2> /dev/null || head -n 80 "$OUT/crash.txt"
      echo '```'
      echo '</details>'
    fi
  } >> "$GITHUB_STEP_SUMMARY"
fi

if [ "$passed" = true ] && [ "$crashed" = false ] && [ "$workers_ok" = true ] && [ "$classes_ok" = true ]; then
  echo "Release smoke test passed."
  exit 0
fi
echo "::error title=Release smoke test failed::tests passed: ${passed}, app crashed: ${crashed}, workers ran: ${workers_ok}, classes complete: ${classes_ok} (artifact smoke-results)"
exit 1
