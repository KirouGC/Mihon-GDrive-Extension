"""Genera los archivos que Mihon lee al añadir un repositorio de extensiones."""

import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

# pkg -> (nombre de la fuente, idioma, baseUrl, nombre corto para el APK y el ícono)
EXTENSIONS = {
    "eu.kanade.tachiyomi.extension.all.googledrive": ("Google Drive", "all", "https://drive.google.com", "googledrive"),
    "eu.kanade.tachiyomi.extension.all.nyaa": ("Nyaa (Literature)", "all", "https://nyaa.si", "nyaa"),
}
VERSION_ID = 1


def source_id(name: str, lang: str, version_id: int) -> int:
    # Igual que HttpSource.id en Mihon: primeros 8 bytes del MD5, sin bit de signo.
    digest = hashlib.md5(f"{name.lower()}/{lang}/{version_id}".encode()).digest()
    value = 0
    for b in digest[:8]:
        value = (value << 8) | b
    return value & 0x7FFFFFFFFFFFFFFF


def badging(aapt2: str, apk: str) -> tuple[str, int, str]:
    out = subprocess.run([aapt2, "dump", "badging", apk], capture_output=True, text=True, check=True).stdout
    pkg = re.search(r"package: name='([^']+)'", out).group(1)
    code = int(re.search(r"versionCode='(\d+)'", out).group(1))
    version = re.search(r"versionName='([^']+)'", out).group(1)
    return pkg, code, version


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, action="append")
    parser.add_argument("--icons", required=True, help="carpeta con <nombre corto>-web.png")
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--page", help="plantilla HTML de la página para agregar el repo a Mihon")
    args = parser.parse_args()

    aapt2 = str(Path(args.apksigner).parent / "aapt2")
    out = Path(args.out)
    (out / "apk").mkdir(parents=True, exist_ok=True)
    (out / "icon").mkdir(parents=True, exist_ok=True)

    index = []
    fingerprint = None
    for apk in args.apk:
        pkg, code, version = badging(aapt2, apk)
        name, lang, base_url, short = EXTENSIONS[pkg]

        certs = subprocess.run(
            [args.apksigner, "verify", "--print-certs", apk], capture_output=True, text=True, check=True,
        ).stdout
        fingerprint = re.search(r"SHA-256 digest: ([0-9a-f]+)", certs).group(1)

        apk_name = f"tachiyomi-{lang}.{short}-v{version}.apk"
        shutil.copy(apk, out / "apk" / apk_name)
        shutil.copy(Path(args.icons) / f"{short}-web.png", out / "icon" / f"{pkg}.png")
        index.append({
            "name": f"Tachiyomi: {name}",
            "pkg": pkg,
            "apk": apk_name,
            "lang": lang,
            "code": code,
            "version": version,
            "nsfw": 0,
            "sources": [{
                "name": name,
                "lang": lang,
                "id": str(source_id(name, lang, VERSION_ID)),
                "baseUrl": base_url,
            }],
        })

    index.sort(key=lambda e: e["name"])
    (out / "index.min.json").write_text(json.dumps(index, separators=(",", ":"), ensure_ascii=False))
    (out / "index.json").write_text(json.dumps(index, indent=2, ensure_ascii=False))

    owner = args.repo.split("/")[0]
    repo_meta = {
        "meta": {
            "name": "Mihon Variety Extensions",
            "shortName": owner,
            "website": f"https://github.com/{args.repo}",
            "signingKeyFingerprint": fingerprint,
        },
    }
    (out / "repo.json").write_text(json.dumps(repo_meta, indent=2, ensure_ascii=False))

    if args.page:
        index_url = f"https://raw.githubusercontent.com/{args.repo}/repo/index.min.json"
        (out / "index.html").write_text(Path(args.page).read_text().replace("__INDEX_URL__", index_url))


if __name__ == "__main__":
    main()
