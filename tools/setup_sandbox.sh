#!/usr/bin/env bash
# One-command sandbox setup for a freshly started (or cold-resumed) Claude cloud session.  Idempotent.
#
#   tools/setup_sandbox.sh          # then: gradle :reaper:testDebugUnitTest --tests '*RtpExactTest*'
#
# What it prepares (all of it is lost whenever the container is recycled):
#   1. local.properties            -> sdk.dir for Gradle
#   2. EGL / GLES software stack   -> the headless OpenGL tests (SceneRenderTest, GlSmokeTest, ...) render the 3D
#                                     world to PNG on Mesa's CPU renderer, so you can LOOK at your own work.
#   3. ~/robolectric-deps          -> Robolectric runtime jars (Maven Central is blocked, Google's mirror works)
#                                     needed by the screenshot / Activity / Wallet tests.
#   4. python3 sanity check        -> tools/reaper_model.py is the exact RTP reference model.
# Run the GL tests with:  EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1  (build.gradle.kts already sets both).
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"

echo "sdk.dir=${ANDROID_HOME:-/opt/android-sdk}" > local.properties
echo "[setup] local.properties written"

need_pkgs=""
for p in libegl1 libegl-mesa0 libgles2 libgbm1 libgl1-mesa-dri; do
  dpkg -s "$p" >/dev/null 2>&1 || need_pkgs="$need_pkgs $p"
done
if [ -n "$need_pkgs" ]; then
  echo "[setup] installing:$need_pkgs"
  (apt-get update -qq && apt-get install -y -qq $need_pkgs) >/tmp/setup_apt.log 2>&1 \
    && echo "[setup] GL libs installed" || { echo "[setup] apt FAILED (see /tmp/setup_apt.log); GL render tests will not run"; tail -5 /tmp/setup_apt.log; }
else
  echo "[setup] GL libs already present"
fi

mkdir -p "$HOME/robolectric-deps"
base=https://maven-central.storage-download.googleapis.com/maven2/org/robolectric/android-all-instrumented
for v in 14-robolectric-10818077-i7 15-robolectric-12650502-i7; do
  f="$HOME/robolectric-deps/android-all-instrumented-$v.jar"
  if [ -s "$f" ]; then continue; fi
  for attempt in 1 2 3; do
    curl -fsSL -o "$f.part" "$base/$v/android-all-instrumented-$v.jar" && mv "$f.part" "$f" && break
    echo "[setup] robolectric jar $v attempt $attempt failed"; sleep $((attempt*3))
  done
done
ls -la "$HOME/robolectric-deps" | tail -n +2
python3 --version
echo "[setup] done"
