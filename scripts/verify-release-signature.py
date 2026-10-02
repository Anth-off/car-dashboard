#!/usr/bin/env python3
"""Reject unsigned artifacts and any signing identity other than the published one."""

import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys


class SigningError(Exception):
    pass


def run_checked(command: list[str], accepted_exit_codes: tuple[int, ...] = (0,)) -> bytes:
    try:
        result = subprocess.run(command, capture_output=True, check=False)
    except OSError as error:
        raise SigningError(f"Cannot run signing verification tool: {command[0]}") from error
    if result.returncode not in accepted_exit_codes:
        # Tool errors can include keystore details. Only report the operation.
        raise SigningError(f"Signature verification failed: {Path(command[0]).name}")
    return result.stdout


def java_tool(name: str) -> str:
    java_home = os.environ.get("JAVA_HOME")
    return str(Path(java_home) / "bin" / name) if java_home else name


def expected_certificate() -> str:
    pin_file = Path(__file__).resolve().parents[1] / "docs/release-signing-certificate.sha256"
    try:
        expected = pin_file.read_text(encoding="ascii").strip().lower()
    except OSError as error:
        raise SigningError("The published signing certificate pin is missing.") from error
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise SigningError("The published signing certificate pin is invalid.")
    return expected


def apk_certificate(path: Path) -> str:
    apksigner = os.environ.get("APKSIGNER")
    if not apksigner:
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        apksigner = str(Path(sdk) / "build-tools/35.0.0/apksigner") if sdk else "apksigner"
    output = run_checked([apksigner, "verify", "--print-certs", str(path)]).decode("utf-8")
    fingerprints = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", output, re.M)
    if len(fingerprints) != 1:
        raise SigningError("The APK must contain exactly one verified signer.")
    return fingerprints[0].lower()


def bundle_certificate(path: Path) -> str:
    locale = ["-J-Duser.language=en", "-J-Duser.country=US"]
    # Android signing certificates are normally self-signed, which returns 4 in
    # strict mode. Reject other bits, notably 16 for unsigned archive entries.
    verification = run_checked(
        [java_tool("jarsigner"), *locale, "-verify", "-strict", str(path)],
        accepted_exit_codes=(0, 4),
    ).decode("utf-8")
    if not re.search(r"^jar verified(?:, with signer errors)?\.$", verification, re.M):
        raise SigningError("The Android App Bundle is unsigned or its signature is invalid.")
    output = run_checked([java_tool("keytool"), *locale, "-printcert", "-jarfile", str(path)]).decode("utf-8")
    fingerprints = re.findall(r"^\s*SHA256: ([0-9A-Fa-f:]+)\s*$", output, re.M)
    if len(fingerprints) != 1:
        raise SigningError("The Android App Bundle must contain exactly one signing certificate.")
    return fingerprints[0].replace(":", "").lower()


def keystore_certificate(path: Path) -> str:
    for name in ("COCKPIT_SIGNING_STORE_PASSWORD", "COCKPIT_SIGNING_KEY_ALIAS"):
        if not os.environ.get(name):
            raise SigningError(f"Missing signing environment variable: {name}")
    certificate = run_checked([
        java_tool("keytool"), "-exportcert", "-keystore", str(path),
        "-alias", os.environ["COCKPIT_SIGNING_KEY_ALIAS"],
        "-storepass:env", "COCKPIT_SIGNING_STORE_PASSWORD",
    ])
    return hashlib.sha256(certificate).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--apk", type=Path)
    source.add_argument("--bundle", type=Path)
    source.add_argument("--keystore", type=Path)
    args = parser.parse_args()
    try:
        expected = expected_certificate()
        path = args.apk or args.bundle or args.keystore
        if not path.is_file():
            raise SigningError("The signing input file does not exist.")
        if args.apk:
            actual = apk_certificate(path)
        elif args.bundle:
            actual = bundle_certificate(path)
        else:
            actual = keystore_certificate(path)
        if actual != expected:
            raise SigningError(
                "Signing certificate differs from the published Cockpit 0.2.0 certificate. "
                "Publishing would break in-place updates; publication is blocked. "
                "See docs/SIGNING.md."
            )
        print(f"Published signing identity verified: {actual}")
        return 0
    except SigningError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
