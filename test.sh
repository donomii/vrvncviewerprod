#!/bin/sh
set -eu
cd -- "$(dirname -- "$0")"
go test ./...
./build.sh
exec .build/vrvncviewer -test
