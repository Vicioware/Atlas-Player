from __future__ import annotations

import argparse
import sys
import urllib.error
import urllib.request
from pathlib import Path


USER_AGENT = "Mozilla/5.0 (GitHub Actions; M3U stream validator)"


def read_playlist_urls(source: Path) -> list[str]:
    urls: list[str] = []

    for raw_line in source.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()

        if not line or line.startswith("#"):
            continue

        if not line.startswith(("http://", "https://")):
            raise ValueError(f"URL inválida en {source}: {line}")

        urls.append(line)

    if not urls:
        raise ValueError(f"No se encontraron URLs de playlists en {source}")

    return urls


def download_playlist(url: str, timeout: float) -> str:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": USER_AGENT},
        method="GET",
    )

    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw_data = response.read()

    return raw_data.decode("utf-8-sig", errors="replace")


def normalize_playlist(content: str) -> str:
    lines = content.splitlines()

    while lines and not lines[0].strip():
        lines.pop(0)

    if lines and lines[0].strip().upper() == "#EXTM3U":
        lines = lines[1:]

    body = "\n".join(lines).strip()

    return f"{body}\n" if body else ""


def build_playlist(urls: list[str], timeout: float) -> tuple[str, int]:
    blocks: list[str] = []
    failures = 0

    for position, url in enumerate(urls, start=1):
        print(f"[{position}/{len(urls)}] Descargando: {url}", file=sys.stderr)

        try:
            playlist = download_playlist(url, timeout)
            normalized = normalize_playlist(playlist)

            if not normalized:
                print(
                    f"Advertencia: la playlist está vacía o no contiene entradas: {url}",
                    file=sys.stderr,
                )
                continue

            blocks.append(normalized)

        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as exc:
            failures += 1
            print(
                f"Error al descargar {url}: {type(exc).__name__}: {exc}",
                file=sys.stderr,
            )

    return "#EXTM3U\n" + "".join(blocks), failures


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Descarga varias playlists M3U y las combina en una sola."
    )
    parser.add_argument("urls_file", type=Path)
    parser.add_argument("--output", type=Path, default=Path("playlists-combinadas.m3u"))
    parser.add_argument("--timeout", type=float, default=45.0)
    args = parser.parse_args()

    if args.timeout <= 0:
        print("--timeout debe ser mayor que cero", file=sys.stderr)
        return 2

    if not args.urls_file.is_file():
        print(f"No existe el archivo de URLs: {args.urls_file}", file=sys.stderr)
        return 2

    try:
        urls = read_playlist_urls(args.urls_file)
        combined_playlist, failures = build_playlist(urls, args.timeout)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(combined_playlist, encoding="utf-8", newline="\n")

    entries = combined_playlist.upper().count("#EXTINF")
    print(
        f"Playlist consolidada creada: {args.output} "
        f"({entries} entradas, {failures} fuentes no descargadas).",
        file=sys.stderr,
    )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())