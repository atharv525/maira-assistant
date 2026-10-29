#!/bin/bash
# Build helper for this sandbox (aarch64 host, proxy). On a normal PC just run ./gradlew assembleDebug
cd "$(dirname "$0")"
. ../work/tools/env.sh >/dev/null 2>&1
./gradlew --no-daemon assembleDebug "$@" > build.log 2>&1
echo "EXIT=$?" >> build.log
