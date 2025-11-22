#!/usr/bin/env bash
sudo podman run --rm  -v "$PWD:/app" -w /app --name boostbox_tests -it --network none -u ubuntu boostbox_tests "/app/scripts/ci.sh"
