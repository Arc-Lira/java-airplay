#!/usr/bin/env sh
set -eu

WORKSPACE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -z "${JAR_PATH:-}" ]; then
  JAR_PATH=$(ls -1 "$WORKSPACE"/java-airplay-server-*.jar "$WORKSPACE"/player/app/build/libs/java-airplay-server-*.jar 2>/dev/null | tail -n 1 || true)
fi
JAR_PATH=${JAR_PATH:-"$WORKSPACE/player/app/build/libs/java-airplay-server-1.1.0.jar"}

JAVA_VERSION=$(java -version 2>&1 | sed -n '1p')
case "$JAVA_VERSION" in
  *'version "25'*) ;;
  *) echo "Java 25 is required. Current output: $JAVA_VERSION" >&2; exit 1 ;;
esac

GST_INSPECT=gst-inspect-1.0
if [ -n "${GSTREAMER_PATH:-}" ]; then
  if [ -x "$GSTREAMER_PATH/gst-inspect-1.0" ]; then
    GST_INSPECT="$GSTREAMER_PATH/gst-inspect-1.0"
  elif [ -x "$GSTREAMER_PATH/bin/gst-inspect-1.0" ]; then
    GST_INSPECT="$GSTREAMER_PATH/bin/gst-inspect-1.0"
    GSTREAMER_PATH="$GSTREAMER_PATH/bin"
  fi
fi

for plugin in appsrc clocksync h264parse avdec_h264 avdec_aac avdec_alac autovideosink autoaudiosink; do
  if ! "$GST_INSPECT" "$plugin" >/dev/null 2>&1; then
    echo "Missing GStreamer plugin: $plugin" >&2
    exit 1
  fi
done

if [ ! -f "$JAR_PATH" ]; then
  "$WORKSPACE/gradlew" :player:app:bootJar
  JAR_PATH=$(ls -1 "$WORKSPACE"/player/app/build/libs/java-airplay-server-*.jar | tail -n 1)
fi

JAVA_ARGS="--enable-native-access=ALL-UNNAMED"
if [ -n "${GSTREAMER_PATH:-}" ]; then
  JAVA_ARGS="$JAVA_ARGS -Dgstreamer.path=$GSTREAMER_PATH"
fi

exec java $JAVA_ARGS -jar "$JAR_PATH" "$@"
