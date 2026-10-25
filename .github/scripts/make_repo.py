"""Genera los archivos que Mihon lee al añadir un repositorio de extensiones."""

import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

PKG = "eu.kanade.tachiyomi.extension.all.googledrive"
NAME = "Google Drive"
LANG = "all"
VERSION_ID = 1
BASE_URL = "https://drive.google.com"


def source_id(name: str, lang: str, version_id: int) -> int:
    # Igual que HttpSource.id en Mihon: primeros 8 bytes del MD5, sin bit de signo.
    digest = hashlib.md5(f"{name.lower()}/{lang}/{version_id}".encode()).digest()
    value = 0
    for b in digest[:8]:
        value = (value << 8) | b
    return value & 0x7FFFFFFFFFFFFFFF


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--icon", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    badging = subprocess.run(
        [str(Path(args.apksigner).parent / "aapt2"), "dump", "badging", args.apk],
        capture_output=True, text=True, check=True,
    ).stdout
    code = int(re.search(r"versionCode='(\d+)'", badging).group(1))
    version = re.search(r"versionName='([^']+)'", badging).group(1)

    certs = subprocess.run(
        [args.apksigner, "verify", "--print-certs", args.apk],
        capture_output=True, text=True, check=True,
    ).stdout
    fingerprint = re.search(r"SHA-256 digest: ([0-9a-f]+)", certs).group(1)

    out = Path(args.out)
    (out / "apk").mkdir(parents=True, exist_ok=True)
    (out / "icon").mkdir(parents=True, exist_ok=True)
    apk_name = f"tachiyomi-all.googledrive-v{version}.apk"
    shutil.copy(args.apk, out / "apk" / apk_name)
    shutil.copy(args.icon, out / "icon" / f"{PKG}.png")

    index = [{
        "name": f"Tachiyomi: {NAME}",
        "pkg": PKG,
        "apk": apk_name,
        "lang": LANG,
        "code": code,
        "version": version,
        "nsfw": 0,
        "sources": [{
            "name": NAME,
            "lang": LANG,
            "id": str(source_id(NAME, LANG, VERSION_ID)),
            "baseUrl": BASE_URL,
        }],
    }]
    (out / "index.min.json").write_text(json.dumps(index, separators=(",", ":"), ensure_ascii=False))
    (out / "index.json").write_text(json.dumps(index, indent=2, ensure_ascii=False))

    owner = args.repo.split("/")[0]
    repo_meta = {
        "meta": {
            "name": f"Extensiones de {owner}",
            "shortName": owner,
            "website": f"https://github.com/{args.repo}",
            "signingKeyFingerprint": fingerprint,
        },
    }
    (out / "repo.json").write_text(json.dumps(repo_meta, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
