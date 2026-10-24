#!/usr/bin/env python3
"""Run durable Payment tracing, Tempo/Grafana, and recovery checks in an isolated Jib stack."""

import os
from pathlib import Path
import sys


if __name__ == "__main__":
    runner = Path(__file__).with_name("verify-jib.py")
    os.execv(sys.executable, [sys.executable, str(runner), "--tracing", *sys.argv[1:]])
