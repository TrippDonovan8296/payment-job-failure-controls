#!/bin/sh
set -eu

classes=$(mktemp -d "${TMPDIR:-/tmp}/fintech-job-alert.XXXXXX")
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" $(find src/main/java -name '*.java')
java -cp "$classes" dev.infrai.fintech.SettlementFailureRunner
