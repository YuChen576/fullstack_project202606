#!/usr/bin/env sh
set -eu

docker run --rm \
  -v "$PWD":/workspace \
  -v gradle-cache:/home/gradle/.gradle \
  -w /workspace \
  gradle:8.10.2-jdk17 gradle "$@"
