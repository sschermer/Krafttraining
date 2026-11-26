#!/usr/bin/env python3
"""Collect the exact unsigned build. This runs without signing secrets."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

output = Path("app/build/outputs/apk/release")
metadata = json.loads((output / "output-metadata.json").read_text())
elements = metadata["elements"]
if len(elements) != 1 or metadata["applicationId"] != "de.trainingsapp":
    raise SystemExit("Expected one de.trainingsapp release APK")
element = elements[0]
filename = element["outputFile"]
if Path(filename).name != filename:
    raise SystemExit("Unexpected APK filename")
apk = output / filename
version = element["versionName"]
code = int(element["versionCode"])
if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
    raise SystemExit("Invalid version name")
if code != int(os.environ["BUILD_VERSION_CODE"]) or code <= 67:
    raise SystemExit("Release version code must advance beyond build 67")
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
tools = sdk / "build-tools/35.0.0"
if subprocess.run([str(tools / "apksigner"), "verify", str(apk)], capture_output=True).returncode == 0:
    raise SystemExit("Gradle must not sign release APKs")
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
release = {
    "packageName": metadata["applicationId"], "versionName": version,
    "versionCode": code, "sourceCommit": os.environ["GITHUB_SHA"],
    "unsignedSha256": digest, "buildRun": int(os.environ["GITHUB_RUN_ID"]),
}
destination = Path("build/release-input")
destination.mkdir(parents=True, exist_ok=True)
shutil.copyfile(apk, destination / "app-release-unsigned.apk")
(destination / "release.json").write_text(json.dumps(release, indent=2) + "\n")
with open(os.environ["GITHUB_OUTPUT"], "a") as result:
    result.write(f"version_name={version}\napk_sha256={digest}\n")
print(f"Prepared unsigned {version} build {code}; SHA-256 {digest}")
