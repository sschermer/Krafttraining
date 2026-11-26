#!/usr/bin/env python3
"""Review a newly generated Gradle checksum baseline against canonical origins.

This is a trust bootstrap, not proof that a publisher is uncompromised. Normal CI
does NOT regenerate metadata or run this network audit: Gradle enforces the
reviewed SHA-256 values. --stamp records origins only if EVERY artifact matches.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import date
import hashlib
import os
from pathlib import Path
import re
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import urlopen
import xml.etree.ElementTree as ET

NS = "https://schema.gradle.org/dependency-verification"
ET.register_namespace("", NS)
ET.register_namespace("xsi", "http://www.w3.org/2001/XMLSchema-instance")
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--metadata", default="gradle/verification-metadata.xml")
parser.add_argument("--cache-root", default=str(Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"))
parser.add_argument("--stamp", action="store_true")
args = parser.parse_args()
tree = ET.parse(args.metadata)
root = tree.getroot()
ns = {"v": NS}
if root.findtext("v:configuration/v:verify-metadata", namespaces=ns) != "true":
    raise SystemExit("Metadata verification must stay enabled")
if root.findall(".//v:trusted-artifacts/v:trust", ns):
    raise SystemExit("Review broad trusted-artifact exceptions manually")
items = []
for component in root.findall("v:components/v:component", ns):
    group, name, version = (component.attrib[k] for k in ("group", "name", "version"))
    if not all(re.fullmatch(r"[A-Za-z0-9_.+~-]+", v) for v in (group, name, version)):
        raise SystemExit("Unexpected Maven coordinates")
    google = bool(re.fullmatch(r"(?:com\.android|androidx|com\.google\.testing\.platform)(?:\..*)?", group))
    origin = "Google Maven" if google else "Maven Central"
    base = "https://dl.google.com/dl/android/maven2/" if google else "https://repo.maven.apache.org/maven2/"
    for artifact in component.findall("v:artifact", ns):
        filename = artifact.attrib["name"]
        checksums = artifact.findall("v:sha256", ns)
        if Path(filename).name != filename or len(checksums) != 1 or list(checksums[0]):
            raise SystemExit("Expected one reviewed SHA-256 per ordinary artifact")
        expected = checksums[0].attrib["value"]
        url = base + group.replace(".", "/") + "/" + name + "/" + version + "/" + filename
        cache = Path(args.cache_root) / group / name / version
        items.append((cache, filename, expected, url, origin, checksums[0]))

def file_digest(path, algorithm):
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()

def check(item):
    cache, filename, expected, url, origin, element = item
    try:
        matches = [p for p in cache.glob("*/" + filename) if file_digest(p, "sha256") == expected]
        if not matches:
            raise ValueError("No cached artifact matches the proposed SHA-256")
        for algorithm in ("sha256", "sha1"):
            sidecar = url + "." + algorithm
            for attempt in range(3):
                try:
                    with urlopen(sidecar, timeout=25) as response:
                        final = urlsplit(response.geturl())
                        if final.scheme != "https" or final.hostname != urlsplit(sidecar).hostname:
                            raise ValueError("Publisher checksum redirected outside its HTTPS origin")
                        published = response.read(4096).decode("ascii").strip().split()[0].lower()
                    break
                except HTTPError as error:
                    if error.code == 404:
                        published = None
                        break
                    if attempt == 2:
                        raise
                    time.sleep(0.3 * (attempt + 1))
                except OSError:
                    if attempt == 2:
                        raise
                    time.sleep(0.3 * (attempt + 1))
            if published is None:
                continue
            if not re.fullmatch(r"[0-9a-f]{64}" if algorithm == "sha256" else r"[0-9a-f]{40}", published):
                raise ValueError("Malformed publisher checksum")
            if file_digest(matches[0], algorithm) != published:
                raise ValueError("Publisher checksum differs from local bytes")
            return item, f"{origin} HTTPS {algorithm} sidecar ({date.today().isoformat()})", None
        raise ValueError("No publisher SHA-256/SHA-1 sidecar; requires manual review")
    except Exception as error:
        return item, None, str(error)

with ThreadPoolExecutor(max_workers=8) as executor:
    results = list(executor.map(check, items))
errors = [(item[3], error) for item, _, error in results if error]
for url, error in errors:
    print(f"FAILED {url}: {error}", file=sys.stderr)
if errors:
    raise SystemExit(f"{len(errors)}/{len(items)} origin checks failed; metadata unchanged")
if args.stamp:
    for item, origin, _ in results:
        item[5].set("origin", origin)
    ET.indent(tree, space="   ")
    tree.write(args.metadata, encoding="utf-8", xml_declaration=True)
print(f"Verified {len(items)} cached artifacts against canonical HTTPS publisher sidecars.")
print("Future integrity uses SHA-256; publisher sidecars bootstrap trust, not independent authenticity.")
