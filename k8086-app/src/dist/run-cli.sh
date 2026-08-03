#!/usr/bin/env sh
# Launch the single-instance emulator CLI from this distribution folder.
# Requires JDK 21+ on PATH (or JAVA_HOME set).
# Example: ./run-cli.sh disks/fd.img --headless --quiet --cga-expect 'A:>'
cd "$(dirname "$0")" || exit 1
# Prefer JAVA_HOME when set (same idea as the Gradle start scripts).
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="java"
fi
exec "$JAVA" -cp "lib/*" com.trugath.k8086.MainKt "$@"
