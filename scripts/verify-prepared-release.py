#!/usr/bin/env python3
"""Verify locally signed public artifacts before a GitHub release is published."""

import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile


class VerificationError(Exception):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)


def run(command: list[str], cwd: Path | None = None) -> bytes:
    result = subprocess.run(command, cwd=cwd, capture_output=True, check=False)
    require(result.returncode == 0, f"Verification command failed: {Path(command[0]).name}")
    return result.stdout


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def github_json(endpoint: str) -> object:
    return json.loads(run(["gh", "api", endpoint]))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--version", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--bundletool", type=Path, required=True)
    parser.add_argument("--verify-uploaded", action="store_true")
    args = parser.parse_args()
    try:
        require(bool(re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", args.version)), "Invalid release tag")
        require(bool(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository)), "Invalid repository")
        source = args.source.resolve()
        artifacts = args.artifacts.resolve()
        version = args.version.removeprefix("v")
        filenames = {f"cockpit-{version}.apk", f"cockpit-{version}.aab", "cockpit-sources.zip"}
        for name in filenames | {"SHA256SUMS.txt", "release-metadata.json"}:
            path = artifacts / name
            require(path.is_file() and not path.is_symlink(), f"Missing or invalid artifact: {name}")

        expected_hashes = {}
        for line in (artifacts / "SHA256SUMS.txt").read_text(encoding="ascii").splitlines():
            match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9_.-]+)", line)
            require(match is not None, "Invalid SHA256SUMS.txt entry")
            checksum, filename = match.groups()
            require(filename not in expected_hashes, "Duplicate checksum entry")
            expected_hashes[filename] = checksum
        require(set(expected_hashes) == filenames, "Checksums must cover exactly the APK, AAB and source ZIP")
        for filename, checksum in expected_hashes.items():
            require(digest(artifacts / filename) == checksum, f"Checksum mismatch: {filename}")

        metadata = json.loads((artifacts / "release-metadata.json").read_text())
        source_commit = run(["git", "rev-parse", "HEAD"], cwd=source).decode().strip()
        require(metadata.get("sourceCommit") == source_commit, "Artifacts do not match the checked-out source commit")
        require(metadata.get("applicationId") == "fr.cockpit.gps", "Wrong application identity")
        require(metadata.get("versionName") == version, "Artifact version differs from the release tag")
        require(type(metadata.get("versionCode")) is int and metadata["versionCode"] >= 3, "Invalid versionCode")
        gradle = (source / "app/build.gradle.kts").read_text()
        for field, expected in (("applicationId", "fr.cockpit.gps"), ("versionName", version)):
            match = re.search(rf'^\s*{field}\s*=\s*"([^"]+)"\s*$', gradle, re.M)
            require(match is not None and match.group(1) == expected, f"Source {field} differs from metadata")
        code_match = re.search(r"^\s*versionCode\s*=\s*(\d+)\s*$", gradle, re.M)
        require(code_match is not None and int(code_match.group(1)) == metadata["versionCode"], "Source versionCode differs from metadata")
        source_zip = run(["git", "archive", "--format=zip", "--prefix=cockpit/", "HEAD"], cwd=source)
        # Compare contents instead of compressed bytes: zlib/Git versions can
        # change the encoding of an otherwise identical source archive.
        with zipfile.ZipFile(io.BytesIO(source_zip)) as expected_zip, zipfile.ZipFile(artifacts / "cockpit-sources.zip") as actual_zip:
            expected_names = expected_zip.namelist()
            actual_names = actual_zip.namelist()
            require(len(actual_names) == len(set(actual_names)) and set(actual_names) == set(expected_names), "Source archive paths differ from the checked-out commit")
            for name in expected_names:
                require(expected_zip.read(name) == actual_zip.read(name), f"Source archive content differs: {name}")
                require(expected_zip.getinfo(name).external_attr == actual_zip.getinfo(name).external_attr, f"Source archive file mode differs: {name}")

        runs = github_json(
            f"repos/{args.repository}/actions/workflows/android-checks.yml/runs"
            f"?head_sha={source_commit}&branch=main&event=push&per_page=1"
        )["workflow_runs"]
        require(bool(runs), "No Android checks run exists for this source commit on main")
        latest = runs[0]
        require(latest["head_sha"] == source_commit and latest["status"] == "completed"
                and latest["conclusion"] == "success", "Latest Android checks for this source commit have not passed")

        apk = artifacts / f"cockpit-{version}.apk"
        bundle = artifacts / f"cockpit-{version}.aab"
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        aapt = str(Path(sdk) / "build-tools/35.0.0/aapt") if sdk else "aapt"
        badging = run([aapt, "dump", "badging", str(apk)]).decode()
        require("application-debuggable" not in badging, "Prepared APK must be a release build")
        package_match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
        expected_identity = (metadata["applicationId"], str(metadata["versionCode"]), version)
        require(package_match is not None and package_match.groups() == expected_identity, "APK identity or version mismatch")

        java_home = os.environ.get("JAVA_HOME")
        java = str(Path(java_home) / "bin/java") if java_home else "java"
        manifest = ET.fromstring(run([java, "-jar", str(args.bundletool.resolve()), "dump", "manifest", f"--bundle={bundle}", "--module=base"]))
        android = "{http://schemas.android.com/apk/res/android}"
        bundle_identity = (manifest.get("package"), manifest.get(android + "versionCode"), manifest.get(android + "versionName"))
        require(bundle_identity == expected_identity, "AAB identity or version mismatch")

        spec = importlib.util.spec_from_file_location("release_signing", source / "scripts/verify-release-signature.py")
        signing = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(signing)
        expected_certificate = signing.expected_certificate()
        try:
            require(signing.apk_certificate(apk) == expected_certificate, "APK signing certificate mismatch")
            require(signing.bundle_certificate(bundle) == expected_certificate, "AAB signing certificate mismatch")
        except signing.SigningError as error:
            raise VerificationError(str(error)) from error

        if args.verify_uploaded:
            # REST's releases/tags endpoint only finds published releases. gh
            # resolves a draft's pending tag through GraphQL first.
            draft = json.loads(run(["gh", "release", "view", args.version, "--repo", args.repository,
                                    "--json", "apiUrl"]))
            prefix = f"https://api.github.com/repos/{args.repository}/releases/"
            require(draft["apiUrl"].startswith(prefix), "Unexpected draft release API URL")
            release_id = draft["apiUrl"][len(prefix):]
            require(release_id.isdecimal(), "Invalid draft release ID")
            release = github_json(f"repos/{args.repository}/releases/{release_id}")
            require(release["draft"] is True, "Only a draft release may be verified for publication")
            require(release["target_commitish"] == source_commit, "Release targets a different source commit")
            downloads = filenames | {"SHA256SUMS.txt"}
            uploaded = {asset["name"]: asset for asset in release["assets"]}
            require(set(uploaded) == downloads, "Draft release assets differ from verified downloads")
            for name in downloads:
                asset = uploaded[name]
                path = artifacts / name
                require(asset["state"] == "uploaded" and asset["size"] == path.stat().st_size
                        and asset.get("digest") == f"sha256:{digest(path)}", f"Uploaded artifact verification failed: {name}")

        print(f"Verified {args.version}: {metadata['applicationId']}, source {source_commit}, Android checks passed, APK/AAB signatures match.")
        return 0
    except (VerificationError, OSError, ValueError, KeyError, TypeError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
