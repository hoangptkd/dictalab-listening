#!/usr/bin/env python3
"""Run base64-encoded PowerShell snippets over a persistent WinRM session.

The password is requested interactively and is never stored in this file.
Each input line must be UTF-8 PowerShell encoded with standard base64.
"""

from __future__ import annotations

import argparse
import base64
import getpass
import sys

import winrm


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("host")
    parser.add_argument("--user", default="Administrator")
    args = parser.parse_args()

    password = getpass.getpass("VPS password: ")
    session = winrm.Session(
        f"http://{args.host}:5985/wsman",
        auth=(args.user, password),
        transport="ntlm",
        read_timeout_sec=1800,
        operation_timeout_sec=1790,
    )
    print("WINRM_READY", flush=True)

    for encoded in sys.stdin:
        encoded = encoded.strip()
        if not encoded:
            continue
        if encoded == "QUIT":
            return 0
        script = base64.b64decode(encoded).decode("utf-8")
        result = session.run_ps(script)
        print(f"WINRM_STATUS={result.status_code}")
        sys.stdout.buffer.write(result.std_out)
        sys.stderr.buffer.write(result.std_err)
        print("\nWINRM_DONE", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
