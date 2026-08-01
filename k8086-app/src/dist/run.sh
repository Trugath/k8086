#!/usr/bin/env sh
# Launch the k8086 workstation from this distribution folder.
# Requires JDK 21+ on PATH (or JAVA_HOME set).
cd "$(dirname "$0")" || exit 1
exec ./bin/k8086 "$@"
