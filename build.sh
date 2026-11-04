#!/bin/sh
set -eu
cd -- "$(dirname -- "$0")"
mkdir -p .build
go build -o .build/vrvncviewer .
