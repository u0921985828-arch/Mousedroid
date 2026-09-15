#!/usr/bin/env bash
cd "$(dirname "$0")"
python3 -m pip install --quiet pynput
exec python3 server.py "$@"
