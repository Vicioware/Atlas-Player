#!/usr/bin/env python3

from __future__ import annotations

import argparse
import csv
import io
import json
import os
import re
import sys
import tempfile
import unicodedata
import xml.etree.ElementTree as ET

from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable
from urllib.parse import urlparse

import requests
from rapidfuzz import fuzz, process

EPG_URL = (
    "https://raw.githubusercontent.com/Puticastillo/EPGCL/"
    "refs/heads/main/smithers/guia-de-programacion.xml"
)

LOGOS_URL = (
    "https://raw.githubusercontent.com/iptv-org/database/"
    "master/data/logos.csv"
)

SPANISH_CCTLDS = {
    "ar", "bo", "cl", "co", "cr", "cu", "do", "ec", "es", "gq",
    "gt", "hn", "mx", "ni", "pa", "pe", "pr", "py", "sv", "uy", "ve",
}

LATIN_MARKERS = {
    "latinamerica",
    "latinoamerica",
    "latino",
    "latam",
    "hispanoamerica",
}

QUALITY_WORDS = {
    "hd",
    "fhd",
    "uhd",
    "sd",
    "4k",
    "8k",
    "1080p",
    "1080i",
    "720p",
    "60fps",
}

# Tokens descriptivos que pueden aparecer en la playlist, pero no suelen formar
# parte del identificador del canal en bases externas.
OPTIONAL_WORDS = {
    "tv",
    "television",
    "canal",
    "channel",
    "senal",
}

# Prefijos basura observados en playlists. Se eliminan únicamente si:
# 1. son el primer token;
# 2. son una sola letra;
# 3. la coincidencia sin el prefijo es sustancialmente mejor.
#
# No se eliminan de manera incondicional, lo que protege nombres como:
# DW Español, FX, L1 MAX, A&E, E!, etc.
NOISE_PREFIXES = {
    "a", "b", "c", "g", "h", "i", "j", "k", "m", "n",
    "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
}

ATTRIBUTE_RE = re.compile(
    r'(?P<key>[A-Za-z0-9_-]+)\s*=\s*"(?P<value>(?:\\.|[^"])*)"'
)

EXTINF_RE = re.compile(
    r"^(?P<prefix>\s*#EXTINF\s*:\s*)(?P<duration>[^,\s]+)"
    r"(?P<attrs>.*?),(?P<name>.*)$",
    re.IGNORECASE,
)

@dataclass
class Entry:
    extinf_index: int
    name: str
    duration: str
    attributes: dict[str, str]
    attribute_order: list[str]
    original_extinf: str
    url: str | None = None
    tvg_id_source: str | None = None
    logo_source: str | None = None
    epg_match: dict | None = None
    logo_match: dict | None = None

@dataclass(frozen=True)
class EPGCandidate:
    title: str
    channel_id: str
    normalized: str
    compact: str

@dataclass(frozen=True)
class LogoCandidate:
    channel: str
    base_name: str
    suffix: str
    url: str
    normalized: str
    compact: str
    tags: str = ""
    in_use: bool = True

@dataclass
class Stats:
    entries: int = 0
    groups_replaced: int = 0
    tvg_ids_added: int = 0
    logos_added_from_duplicates: int = 0
    logos_added_from_database: int = 0
    unmatched_epg: list[str] = field(default_factory=list)
    unmatched_logos: list[str] = field(default_factory=list)
    ambiguous_epg: list[dict] = field(default_factory=list)
    ambiguous_logos: list[dict] = field(default_factory=list)

def strip_accents(text: str) -> str:
    normalized = unicodedata.normalize("NFKD", text)
    return "".join(
        char for char in normalized
        if not unicodedata.combining(char)
    )

def normalize_spaces(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()

def normalize_name(
    text: str,
    *,
    remove_quality: bool = True,
    remove_optional_words: bool = False,
) -> str:
    text = strip_accents(text).casefold()
    text = text.replace("&", " and ")
    text = re.sub(r"(?<=\d)[._](?=\d)", "", text)
    text = re.sub(r"[^a-z0-9]+", " ", text)
    tokens = text.split()

    if remove_quality:
        tokens = [token for token in tokens if token not in QUALITY_WORDS]

    if remove_optional_words:
        tokens = [token for token in tokens if token not in OPTIONAL_WORDS]

    return " ".join(tokens)

def compact_name(text: str, **kwargs) -> str:
    return normalize_name(text, **kwargs).replace(" ", "")

def remove_leading_noise_candidate(text: str) -> str | None:
    tokens = normalize_spaces(text).split()

    if len(tokens) < 3:
        return None

    first = strip_accents(tokens[0]).casefold()
    if first not in NOISE_PREFIXES:
        return None

    # Evita tratar como basura tokens con números o símbolos relevantes.
    if not re.fullmatch(r"[a-z]", first):
        return None

    remainder = " ".join(tokens[1:]).strip()
    return remainder or None

def search_variants(name: str) -> list[tuple[str, bool]]:
    variants: list[tuple[str, bool]] = [(name, False)]
    without_prefix = remove_leading_noise_candidate(name)

    if without_prefix and without_prefix != name:
        variants.append((without_prefix, True))

    return variants

def escape_attribute(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')

def parse_extinf(line: str, line_index: int) -> Entry | None:
    match = EXTINF_RE.match(line.rstrip("\r\n"))
    if not match:
        return None

    attributes: dict[str, str] = {}
    order: list[str] = []

    for attribute in ATTRIBUTE_RE.finditer(match.group("attrs")):
        key = attribute.group("key").lower()
        value = attribute.group("value").replace('\\"', '"').replace("\\\\", "\\")

        if key not in attributes:
            order.append(key)

        attributes[key] = value

    return Entry(
        extinf_index=line_index,
        name=normalize_spaces(match.group("name")),
        duration=match.group("duration"),
        attributes=attributes,
        attribute_order=order,
        original_extinf=line.rstrip("\r\n"),
    )

def parse_playlist(lines: list[str]) -> list[Entry]:
    entries: list[Entry] = []

    for index, line in enumerate(lines):
        entry = parse_extinf(line, index)
        if entry is None:
            continue

        # Localiza la primera URL posterior al EXTINF sin alterar líneas
        # auxiliares como #EXTVLCOPT.
        for following in lines[index + 1:]:
            stripped = following.strip()

            if stripped.upper().startswith("#EXTINF:"):
                break

            if stripped and not stripped.startswith("#"):
                entry.url = stripped
                break

        entries.append(entry)

    return entries

def render_extinf(entry: Entry) -> str:
    preferred = ["tvg-id", "tvg-name", "tvg-logo", "group-title"]
    ordered_keys: list[str] = []

    for key in preferred:
        if key in entry.attributes and key not in ordered_keys:
            ordered_keys.append(key)

    for key in entry.attribute_order:
        if key in entry.attributes and key not in ordered_keys:
            ordered_keys.append(key)

    for key in entry.attributes:
        if key not in ordered_keys:
            ordered_keys.append(key)

    attrs = "".join(
        f' {key}="{escape_attribute(entry.attributes[key])}"'
        for key in ordered_keys
    )

    return f'#EXTINF:{entry.duration}{attrs},{entry.name}'

def download_text(url: str, timeout: int = 90) -> str:
    headers = {
        "User-Agent": (
            "master-m3u-enricher/1.0 "
            "(GitHub Actions; metadata enrichment)"
        )
    }

    response = requests.get(url, timeout=timeout, headers=headers)
    response.raise_for_status()
    response.encoding = response.apparent_encoding or "utf-8"
    return response.text

def local_tag(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]

def load_epg_candidates(xml_text: str) -> list[EPGCandidate]:
    # Relaciona títulos tanto desde <programme channel="..."> como desde
    # <channel id="..."><display-name>...</display-name></channel>.
    title_to_channels: dict[tuple[str, str], Counter[str]] = defaultdict(Counter)
    root = ET.fromstring(xml_text)

    for channel in root.iter():
        if local_tag(channel.tag) != "channel":
            continue

        channel_id = normalize_spaces(channel.attrib.get("id", ""))
        if not channel_id:
            continue

        for child in channel:
            if local_tag(child.tag) != "display-name":
                continue

            title = normalize_spaces("".join(child.itertext()))
            normalized = normalize_name(title)

            if title and normalized:
                title_to_channels[(title, normalized)][channel_id] += 1

    for programme in root.iter():
        if local_tag(programme.tag) != "programme":
            continue

        channel_id = normalize_spaces(programme.attrib.get("channel", ""))
        if not channel_id:
            continue

        titles = [
            normalize_spaces("".join(child.itertext()))
            for child in programme
            if local_tag(child.tag) == "title"
        ]

        for title in titles:
            normalized = normalize_name(title)
            if title and normalized:
                title_to_channels[(title, normalized)][channel_id] += 1

    candidates: list[EPGCandidate] = []

    for (title, normalized), channel_counts in title_to_channels.items():
        channel_id = channel_counts.most_common(1)[0][0]
        candidates.append(
            EPGCandidate(
                title=title,
                channel_id=channel_id,
                normalized=normalized,
                compact=normalized.replace(" ", ""),
            )
        )

    return candidates

def split_logo_channel(channel: str) -> tuple[str, str]:
    channel = normalize_spaces(channel)
    if "." not in channel:
        return channel, ""

    base, suffix = channel.rsplit(".", 1)

    if re.fullmatch(r"[A-Za-z]{2,3}", suffix):
        return base, suffix.casefold()

    return channel, ""

def parse_bool(value: str) -> bool:
    return value.strip().casefold() in {"1", "true", "yes", "y"}

def load_logo_candidates(csv_text: str) -> list[LogoCandidate]:
    reader = csv.DictReader(io.StringIO(csv_text))
    candidates: list[LogoCandidate] = []

    for row in reader:
        channel = normalize_spaces(row.get("channel", ""))
        url = normalize_spaces(row.get("url", ""))

        if not channel or not url:
            continue

        if not urlparse(url).scheme.startswith("http"):
            continue

        base, suffix = split_logo_channel(channel)
        normalized = normalize_name(base)

        if not normalized:
            continue

        candidates.append(
            LogoCandidate(
                channel=channel,
                base_name=base,
                suffix=suffix,
                url=url,
                normalized=normalized,
                compact=normalized.replace(" ", ""),
                tags=normalize_spaces(row.get("tags", "")),
                in_use=parse_bool(row.get("in_use", "")),
            )
        )

    return candidates

def token_score(left: str, right: str) -> float:
    if not left or not right:
        return 0.0

    compact_left = left.replace(" ", "")
    compact_right = right.replace(" ", "")

    scores = [
        fuzz.ratio(left, right),
        fuzz.WRatio(left, right),
        fuzz.token_sort_ratio(left, right),
        fuzz.ratio(compact_left, compact_right),
    ]

    # Coincidencia exacta luego de eliminar HD/FHD/UHD.
    if compact_left == compact_right:
        scores.append(100.0)

    # Penaliza coincidencias que pierdan números. Así Canal 5 no se
    # confundirá deliberadamente con Canal 6.
    left_numbers = re.findall(r"\d+", left)
    right_numbers = re.findall(r"\d+", right)

    score = max(scores)

    if left_numbers != right_numbers:
        score -= 35.0

    return max(0.0, min(100.0, score))

def epg_score(stream_name: str, candidate: EPGCandidate) -> float:
    best = 0.0

    for variant, removed_noise in search_variants(stream_name):
        normalized = normalize_name(variant)
        score = token_score(normalized, candidate.normalized)

        # El prefijo se descarta únicamente cuando produce una coincidencia
        # muy fuerte; no se eliminan letras indiscriminadamente.
        if removed_noise:
            original = token_score(
                normalize_name(stream_name),
                candidate.normalized,
            )
            if score < 94 or score < original + 7:
                continue

        best = max(best, score)

    return best

def select_epg_match(
    stream_name: str,
    candidates: list[EPGCandidate],
    min_score: float,
) -> tuple[EPGCandidate | None, float, float]:
    ranked = sorted(
        ((candidate, epg_score(stream_name, candidate)) for candidate in candidates),
        key=lambda item: item[1],
        reverse=True,
    )

    if not ranked:
        return None, 0.0, 0.0

    best_candidate, best_score = ranked[0]
    second_score = ranked[1][1] if len(ranked) > 1 else 0.0

    if best_score < min_score:
        return None, best_score, second_score

    # Una coincidencia no exacta necesita separarse de la segunda opción.
    if best_score < 100 and best_score - second_score < 3:
        return None, best_score, second_score

    return best_candidate, best_score, second_score

def latin_priority(candidate: LogoCandidate) -> int:
    searchable = normalize_name(
        f"{candidate.channel} {candidate.tags}"
    ).replace(" ", "")

    score = 0

    if any(marker in searchable for marker in LATIN_MARKERS):
        score += 40

    if candidate.suffix in SPANISH_CCTLDS:
        score += 25

    if candidate.in_use:
        score += 5

    return score

def logo_score(stream_name: str, candidate: LogoCandidate) -> float:
    best = 0.0

    for variant, removed_noise in search_variants(stream_name):
        normalized = normalize_name(variant)
        score = token_score(normalized, candidate.normalized)

        if removed_noise:
            original = token_score(
                normalize_name(stream_name),
                candidate.normalized,
            )
            if score < 94 or score < original + 7:
                continue

        best = max(best, score)

    return best

def select_logo_match(
    stream_name: str,
    candidates: list[LogoCandidate],
    min_score: float,
) -> tuple[LogoCandidate | None, float, float]:
    ranked = sorted(
        (
            (
                candidate,
                logo_score(stream_name, candidate),
                latin_priority(candidate),
            )
            for candidate in candidates
        ),
        key=lambda item: (item[1], item[2]),
        reverse=True,
    )

    if not ranked:
        return None, 0.0, 0.0

    best_raw_score = ranked[0][1]

    if best_raw_score < min_score:
        return None, best_raw_score, 0.0

    # Primero conserva todos los nombres prácticamente equivalentes y luego
    # prioriza LatinAmerica/Latinoamérica y ccTLD hispanohablantes.
    equivalent = [
        item for item in ranked
        if item[1] >= max(min_score, best_raw_score - 1.5)
    ]

    equivalent.sort(
        key=lambda item: (item[2], item[1], item[0].in_use),
        reverse=True,
    )

    best_candidate, best_score, _ = equivalent[0]

    alternatives = [
        item for item in ranked
        if item[0].channel != best_candidate.channel
    ]
    second_score = alternatives[0][1] if alternatives else 0.0

    # Para coincidencias difusas, exige un margen suficiente. Las variantes
    # regionales del mismo nombre no se consideran ambiguas porque se resuelven
    # mediante latin_priority().
    if best_score < 100:
        near_other_bases = [
            item for item in alternatives
            if item[1] >= best_score - 2
            and item[0].normalized != best_candidate.normalized
        ]
        if near_other_bases:
            return None, best_score, near_other_bases[0][1]

    return best_candidate, best_score, second_score

def duplicate_key(name: str) -> str:
    # Los duplicados se determinan por nombre visible, ignorando mayúsculas,
    # acentos y diferencias de espacios. No se elimina HD aquí: "Canal" y
    # "Canal HD" no deben agruparse automáticamente como duplicados.
    return normalize_name(name, remove_quality=False)

def share_duplicate_logos(entries: list[Entry], stats: Stats) -> None:
    groups: dict[str, list[Entry]] = defaultdict(list)

    for entry in entries:
        groups[duplicate_key(entry.name)].append(entry)

    for group_entries in groups.values():
        if len(group_entries) < 2:
            continue

        logos = [
            entry.attributes.get("tvg-logo", "").strip()
            for entry in group_entries
            if entry.attributes.get("tvg-logo", "").strip()
        ]

        if not logos:
            continue

        # Si existen varios, usa el más frecuente y conserva cualquier logo
        # existente distinto, porque solo se deben completar valores faltantes.
        selected_logo = Counter(logos).most_common(1)[0][0]

        for entry in group_entries:
            if entry.attributes.get("tvg-logo", "").strip():
                continue

            entry.attributes["tvg-logo"] = selected_logo
            entry.logo_source = "duplicate"
            stats.logos_added_from_duplicates += 1

def enrich_entries(
    entries: list[Entry],
    epg_candidates: list[EPGCandidate],
    logo_candidates: list[LogoCandidate],
    min_epg_score: float,
    min_logo_score: float,
) -> Stats:
    stats = Stats(entries=len(entries))

    # group-title siempre se reemplaza. Solo los duplicados reciben el nombre
    # como grupo; los canales únicos reciben "-" para evitar inventar categorías.
    counts = Counter(duplicate_key(entry.name) for entry in entries)

    for entry in entries:
        key = duplicate_key(entry.name)
        new_group = entry.name if counts[key] > 1 else "-"

        if entry.attributes.get("group-title") != new_group:
            stats.groups_replaced += 1

        entry.attributes["group-title"] = new_group

    # Comparte primero logos existentes entre duplicados.
    share_duplicate_logos(entries, stats)

    for entry in entries:
        existing_tvg_id = entry.attributes.get("tvg-id", "").strip()

        if not existing_tvg_id:
            candidate, score, second_score = select_epg_match(
                entry.name,
                epg_candidates,
                min_epg_score,
            )

            if candidate:
                entry.attributes["tvg-id"] = candidate.channel_id
                entry.tvg_id_source = "epg"
                entry.epg_match = {
                    "stream": entry.name,
                    "title": candidate.title,
                    "channel": candidate.channel_id,
                    "score": round(score, 2),
                }
                stats.tvg_ids_added += 1
            else:
                stats.unmatched_epg.append(entry.name)

                if score >= min_epg_score:
                    stats.ambiguous_epg.append({
                        "stream": entry.name,
                        "best_score": round(score, 2),
                        "second_score": round(second_score, 2),
                    })

        existing_logo = entry.attributes.get("tvg-logo", "").strip()

        if not existing_logo:
            candidate, score, second_score = select_logo_match(
                entry.name,
                logo_candidates,
                min_logo_score,
            )

            if candidate:
                entry.attributes["tvg-logo"] = candidate.url
                entry.logo_source = "logos.csv"
                entry.logo_match = {
                    "stream": entry.name,
                    "channel": candidate.channel,
                    "url": candidate.url,
                    "score": round(score, 2),
                    "regional_priority": latin_priority(candidate),
                }
                stats.logos_added_from_database += 1
            else:
                stats.unmatched_logos.append(entry.name)

                if score >= min_logo_score:
                    stats.ambiguous_logos.append({
                        "stream": entry.name,
                        "best_score": round(score, 2),
                        "second_score": round(second_score, 2),
                    })

    # Una coincidencia externa encontrada para uno de los duplicados también se
    # comparte con el resto, sin sobrescribir logos que ya existían.
    share_duplicate_logos(entries, stats)

    return stats

def atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.NamedTemporaryFile(
        "w",
        encoding="utf-8",
        newline="",
        dir=path.parent,
        delete=False,
    ) as temporary:
        temporary.write(content)
        temporary_path = Path(temporary.name)

    os.replace(temporary_path, path)

def write_report(
    path: Path,
    stats: Stats,
    entries: list[Entry],
    min_epg_score: float,
    min_logo_score: float,
) -> None:
    report = {
        "summary": {
            "entries": stats.entries,
            "group_titles_replaced": stats.groups_replaced,
            "tvg_ids_added": stats.tvg_ids_added,
            "logos_added_from_duplicates": stats.logos_added_from_duplicates,
            "logos_added_from_database": stats.logos_added_from_database,
            "unmatched_epg_count": len(set(stats.unmatched_epg)),
            "unmatched_logo_count": len(set(stats.unmatched_logos)),
            "min_epg_score": min_epg_score,
            "min_logo_score": min_logo_score,
        },
        "unmatched_epg": sorted(set(stats.unmatched_epg)),
        "unmatched_logos": sorted(set(stats.unmatched_logos)),
        "ambiguous_epg": stats.ambiguous_epg,
        "ambiguous_logos": stats.ambiguous_logos,
        "epg_matches": [
            entry.epg_match for entry in entries if entry.epg_match
        ],
        "logo_matches": [
            entry.logo_match for entry in entries if entry.logo_match
        ],
    }

    atomic_write(
        path,
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
    )

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Enriquece tvg-id, group-title y tvg-logo en una playlist M3U."
    )
    parser.add_argument("--input", default="master.m3u")
    parser.add_argument("--report", default="enrichment-report.json")
    parser.add_argument("--epg-url", default=EPG_URL)
    parser.add_argument("--logos-url", default=LOGOS_URL)
    parser.add_argument("--min-epg-score", type=float, default=88.0)
    parser.add_argument("--min-logo-score", type=float, default=91.0)
    return parser.parse_args()

def main() -> int:
    args = parse_args()
    input_path = Path(args.input)
    report_path = Path(args.report)

    if not input_path.is_file():
        print(f"ERROR: no existe {input_path}", file=sys.stderr)
        return 1

    original = input_path.read_text(encoding="utf-8-sig")
    newline = "\r\n" if "\r\n" in original else "\n"
    had_final_newline = original.endswith(("\n", "\r"))

    lines = original.splitlines()

    if not lines or not lines[0].lstrip().upper().startswith("#EXTM3U"):
        print(
            "ERROR: el archivo no parece ser una playlist M3U válida.",
            file=sys.stderr,
        )
        return 1

    entries = parse_playlist(lines)

    if not entries:
        print("ERROR: no se encontraron entradas #EXTINF.", file=sys.stderr)
        return 1

    print(f"Descargando EPG: {args.epg_url}")
    epg_text = download_text(args.epg_url)

    print(f"Descargando logos: {args.logos_url}")
    logos_text = download_text(args.logos_url)

    epg_candidates = load_epg_candidates(epg_text)
    logo_candidates = load_logo_candidates(logos_text)

    if not epg_candidates:
        print("ERROR: la EPG no produjo candidatos utilizables.", file=sys.stderr)
        return 1

    if not logo_candidates:
        print(
            "ERROR: logos.csv no produjo candidatos utilizables.",
            file=sys.stderr,
        )
        return 1

    print(f"Candidatos EPG: {len(epg_candidates)}")
    print(f"Candidatos de logo: {len(logo_candidates)}")

    stats = enrich_entries(
        entries,
        epg_candidates,
        logo_candidates,
        args.min_epg_score,
        args.min_logo_score,
    )

    for entry in entries:
        lines[entry.extinf_index] = render_extinf(entry)

    output = newline.join(lines)
    if had_final_newline:
        output += newline

    atomic_write(input_path, output)
    write_report(
        report_path,
        stats,
        entries,
        args.min_epg_score,
        args.min_logo_score,
    )

    print(f"Entradas procesadas: {stats.entries}")
    print(f"group-title reemplazados: {stats.groups_replaced}")
    print(f"tvg-id agregados: {stats.tvg_ids_added}")
    print(
        "Logos agregados desde duplicados: "
        f"{stats.logos_added_from_duplicates}"
    )
    print(
        "Logos agregados desde logos.csv: "
        f"{stats.logos_added_from_database}"
    )
    print(f"Sin coincidencia EPG: {len(set(stats.unmatched_epg))}")
    print(f"Sin coincidencia de logo: {len(set(stats.unmatched_logos))}")
    print(f"Informe: {report_path}")

    return 0

if __name__ == "__main__":
    raise SystemExit(main())