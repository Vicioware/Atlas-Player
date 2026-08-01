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
from urllib.parse import urlparse

import requests
from rapidfuzz import fuzz

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
    "hd", "fhd", "uhd", "sd", "4k", "8k", "1080p", "1080i",
    "720p", "60fps",
}

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
    name: str
    duration: str
    attributes: dict[str, str]
    attribute_order: list[str]
    block_lines: list[str]
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
    entries_before_deduplication: int = 0
    entries: int = 0
    duplicate_urls_removed: int = 0
    groups_replaced: int = 0
    tvg_ids_added: int = 0
    logos_added_from_duplicates: int = 0
    logos_added_from_database: int = 0
    unmatched_epg: list[str] = field(default_factory=list)
    unmatched_logos: list[str] = field(default_factory=list)
    ambiguous_epg: list[dict] = field(default_factory=list)
    ambiguous_logos: list[dict] = field(default_factory=list)

def strip_accents(text: str) -> str:
    text = unicodedata.normalize("NFKD", text)
    return "".join(char for char in text if not unicodedata.combining(char))

def normalize_spaces(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()

def normalize_name(text: str, *, remove_quality: bool = True) -> str:
    text = strip_accents(text).casefold()
    text = text.replace("&", " and ")
    text = re.sub(r"(?<=\d)[._](?=\d)", "", text)
    text = re.sub(r"[^a-z0-9]+", " ", text)
    tokens = text.split()

    if remove_quality:
        tokens = [token for token in tokens if token not in QUALITY_WORDS]

    return " ".join(tokens)

def natural_sort_key(text: str) -> tuple:
    normalized = normalize_name(text, remove_quality=False)
    return tuple(
        int(part) if part.isdigit() else part
        for part in re.split(r"(\d+)", normalized)
    )

def remove_leading_noise_candidate(text: str) -> str | None:
    tokens = normalize_spaces(text).split()

    if len(tokens) < 3:
        return None

    first = strip_accents(tokens[0]).casefold()

    if first not in NOISE_PREFIXES or not re.fullmatch(r"[a-z]", first):
        return None

    return " ".join(tokens[1:]).strip() or None

def search_variants(name: str) -> list[tuple[str, bool]]:
    variants = [(name, False)]
    without_prefix = remove_leading_noise_candidate(name)

    if without_prefix and without_prefix != name:
        variants.append((without_prefix, True))

    return variants

def escape_attribute(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')

def parse_extinf(line: str) -> tuple[str, str, dict[str, str], list[str]] | None:
    match = EXTINF_RE.match(line.rstrip("\r\n"))

    if not match:
        return None

    attributes: dict[str, str] = {}
    order: list[str] = []

    for attribute in ATTRIBUTE_RE.finditer(match.group("attrs")):
        key = attribute.group("key").lower()
        value = (
            attribute.group("value")
            .replace('\\"', '"')
            .replace("\\\\", "\\")
        )

        if key not in attributes:
            order.append(key)

        attributes[key] = value

    return (
        normalize_spaces(match.group("name")),
        match.group("duration"),
        attributes,
        order,
    )

def parse_playlist(lines: list[str]) -> tuple[list[str], list[Entry]]:
    first_extinf = next(
        (
            index for index, line in enumerate(lines)
            if line.lstrip().upper().startswith("#EXTINF:")
        ),
        len(lines),
    )

    header = lines[:first_extinf]
    entries: list[Entry] = []
    index = first_extinf

    while index < len(lines):
        line = lines[index]

        if not line.lstrip().upper().startswith("#EXTINF:"):
            index += 1
            continue

        end = index + 1

        while (
            end < len(lines)
            and not lines[end].lstrip().upper().startswith("#EXTINF:")
        ):
            end += 1

        parsed = parse_extinf(line)

        if parsed is None:
            index = end
            continue

        name, duration, attributes, order = parsed
        block = lines[index:end]
        stream_url = None

        for following in block[1:]:
            stripped = following.strip()

            if stripped and not stripped.startswith("#"):
                stream_url = stripped
                break

        entries.append(
            Entry(
                name=name,
                duration=duration,
                attributes=attributes,
                attribute_order=order,
                block_lines=block,
                url=stream_url,
            )
        )

        index = end

    return header, entries

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

def render_entry(entry: Entry) -> list[str]:
    block = list(entry.block_lines)

    if block:
        block[0] = render_extinf(entry)
        return block

    result = [render_extinf(entry)]

    if entry.url:
        result.append(entry.url)

    return result

def canonical_url(url: str | None) -> str | None:
    if not url:
        return None

    # Se eliminan únicamente espacios externos. No se modifica la consulta,
    # porque dos URLs con parámetros diferentes pueden ser streams distintos.
    return url.strip()

def merge_duplicate_metadata(target: Entry, duplicate: Entry) -> None:
    for key, value in duplicate.attributes.items():
        if value.strip() and not target.attributes.get(key, "").strip():
            target.attributes[key] = value

            if key not in target.attribute_order:
                target.attribute_order.append(key)

    if (
        len(duplicate.block_lines) > len(target.block_lines)
        and canonical_url(duplicate.url) == canonical_url(target.url)
    ):
        # Conserva el EXTINF principal, pero aprovecha opciones auxiliares
        # presentes en la copia más completa.
        target_options = [
            line for line in target.block_lines[1:]
            if line.strip().startswith("#")
        ]
        duplicate_options = [
            line for line in duplicate.block_lines[1:]
            if line.strip().startswith("#")
        ]

        merged_options = list(target_options)

        for line in duplicate_options:
            if line not in merged_options:
                merged_options.append(line)

        target.block_lines = [
            target.block_lines[0],
            *merged_options,
            target.url or duplicate.url or "",
        ]

        target.block_lines = [
            line for line in target.block_lines if line != ""
        ]

def deduplicate_by_url(entries: list[Entry]) -> tuple[list[Entry], int]:
    unique: list[Entry] = []
    by_url: dict[str, Entry] = {}
    removed = 0

    for entry in entries:
        key = canonical_url(entry.url)

        # Una entrada sin URL no puede deduplicarse con seguridad.
        if not key:
            unique.append(entry)
            continue

        existing = by_url.get(key)

        if existing is None:
            by_url[key] = entry
            unique.append(entry)
            continue

        merge_duplicate_metadata(existing, entry)
        removed += 1

    return unique, removed

def download_text(url: str, timeout: int = 90) -> str:
    response = requests.get(
        url,
        timeout=timeout,
        headers={"User-Agent": "master-m3u-enricher/2.0"},
    )
    response.raise_for_status()
    response.encoding = response.apparent_encoding or "utf-8"
    return response.text

def local_tag(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]

def load_epg_candidates(xml_text: str) -> list[EPGCandidate]:
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

        for child in programme:
            if local_tag(child.tag) != "title":
                continue

            title = normalize_spaces("".join(child.itertext()))
            normalized = normalize_name(title)

            if title and normalized:
                title_to_channels[(title, normalized)][channel_id] += 1

    candidates = []

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
    candidates = []

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

    score = max(
        fuzz.ratio(left, right),
        fuzz.WRatio(left, right),
        fuzz.token_sort_ratio(left, right),
        fuzz.ratio(compact_left, compact_right),
    )

    if compact_left == compact_right:
        score = 100.0

    if re.findall(r"\d+", left) != re.findall(r"\d+", right):
        score -= 35.0

    return max(0.0, min(100.0, score))

def candidate_score(
    stream_name: str,
    candidate_name: str,
) -> float:
    best = 0.0

    for variant, removed_noise in search_variants(stream_name):
        normalized = normalize_name(variant)
        score = token_score(normalized, candidate_name)

        if removed_noise:
            original = token_score(
                normalize_name(stream_name),
                candidate_name,
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
        (
            (candidate, candidate_score(stream_name, candidate.normalized))
            for candidate in candidates
        ),
        key=lambda item: item[1],
        reverse=True,
    )

    if not ranked:
        return None, 0.0, 0.0

    candidate, score = ranked[0]
    second = ranked[1][1] if len(ranked) > 1 else 0.0

    if score < min_score:
        return None, score, second

    if score < 100 and score - second < 3:
        return None, score, second

    return candidate, score, second

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

def select_logo_match(
    stream_name: str,
    candidates: list[LogoCandidate],
    min_score: float,
) -> tuple[LogoCandidate | None, float, float]:
    ranked = sorted(
        (
            (
                candidate,
                candidate_score(stream_name, candidate.normalized),
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

    equivalent = [
        item for item in ranked
        if item[1] >= max(min_score, best_raw_score - 1.5)
    ]

    equivalent.sort(
        key=lambda item: (item[2], item[1], item[0].in_use),
        reverse=True,
    )

    candidate, score, _ = equivalent[0]

    alternatives = [
        item for item in ranked
        if item[0].channel != candidate.channel
    ]

    second = alternatives[0][1] if alternatives else 0.0

    if score < 100:
        near_other_bases = [
            item for item in alternatives
            if item[1] >= score - 2
            and item[0].normalized != candidate.normalized
        ]

        if near_other_bases:
            return None, score, near_other_bases[0][1]

    return candidate, score, second

def duplicate_name_key(name: str) -> str:
    return normalize_name(name, remove_quality=False)

def share_duplicate_logos(entries: list[Entry], stats: Stats) -> None:
    groups: dict[str, list[Entry]] = defaultdict(list)

    for entry in entries:
        groups[duplicate_name_key(entry.name)].append(entry)

    for group in groups.values():
        if len(group) < 2:
            continue

        logos = [
            entry.attributes.get("tvg-logo", "").strip()
            for entry in group
            if entry.attributes.get("tvg-logo", "").strip()
        ]

        if not logos:
            continue

        selected = Counter(logos).most_common(1)[0][0]

        for entry in group:
            if entry.attributes.get("tvg-logo", "").strip():
                continue

            entry.attributes["tvg-logo"] = selected
            entry.logo_source = "duplicate"
            stats.logos_added_from_duplicates += 1

def enrich_entries(
    entries: list[Entry],
    epg_candidates: list[EPGCandidate],
    logo_candidates: list[LogoCandidate],
    min_epg_score: float,
    min_logo_score: float,
    stats: Stats,
) -> None:
    counts = Counter(duplicate_name_key(entry.name) for entry in entries)

    for entry in entries:
        key = duplicate_name_key(entry.name)
        new_group = entry.name if counts[key] > 1 else "-"

        if entry.attributes.get("group-title") != new_group:
            stats.groups_replaced += 1

        entry.attributes["group-title"] = new_group

    share_duplicate_logos(entries, stats)

    for entry in entries:
        if not entry.attributes.get("tvg-id", "").strip():
            candidate, score, second = select_epg_match(
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
                        "second_score": round(second, 2),
                    })

        if not entry.attributes.get("tvg-logo", "").strip():
            candidate, score, second = select_logo_match(
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
                        "second_score": round(second, 2),
                    })

    share_duplicate_logos(entries, stats)

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
            "entries_before_deduplication":
                stats.entries_before_deduplication,
            "duplicate_urls_removed": stats.duplicate_urls_removed,
            "entries_after_deduplication": stats.entries,
            "group_titles_replaced": stats.groups_replaced,
            "tvg_ids_added": stats.tvg_ids_added,
            "logos_added_from_duplicates":
                stats.logos_added_from_duplicates,
            "logos_added_from_database":
                stats.logos_added_from_database,
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
        description=(
            "Enriquece, deduplica y ordena una playlist M3U."
        )
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
    lines = original.splitlines()

    if not lines or not lines[0].lstrip().upper().startswith("#EXTM3U"):
        print(
            "ERROR: el archivo no parece una playlist M3U válida.",
            file=sys.stderr,
        )
        return 1

    header, entries = parse_playlist(lines)

    if not entries:
        print("ERROR: no se encontraron entradas #EXTINF.", file=sys.stderr)
        return 1

    stats = Stats(entries_before_deduplication=len(entries))

    entries, removed = deduplicate_by_url(entries)
    stats.duplicate_urls_removed = removed
    stats.entries = len(entries)

    print(f"Descargando EPG: {args.epg_url}")
    epg_text = download_text(args.epg_url)

    print(f"Descargando logos: {args.logos_url}")
    logos_text = download_text(args.logos_url)

    epg_candidates = load_epg_candidates(epg_text)
    logo_candidates = load_logo_candidates(logos_text)

    if not epg_candidates:
        print("ERROR: la EPG no produjo candidatos.", file=sys.stderr)
        return 1

    if not logo_candidates:
        print("ERROR: logos.csv no produjo candidatos.", file=sys.stderr)
        return 1

    enrich_entries(
        entries,
        epg_candidates,
        logo_candidates,
        args.min_epg_score,
        args.min_logo_score,
        stats,
    )

    # Orden alfabético natural, sin distinguir mayúsculas ni acentos.
    # Ejemplo: Canal 2 aparece antes de Canal 10.
    entries.sort(
        key=lambda entry: (
            natural_sort_key(entry.name),
            canonical_url(entry.url) or "",
        )
    )

    output_lines = list(header)

    # Evita líneas vacías acumuladas entre la cabecera y la primera entrada.
    while output_lines and output_lines[-1] == "":
        output_lines.pop()

    for entry in entries:
        output_lines.extend(render_entry(entry))

    output = newline.join(output_lines) + newline
    atomic_write(input_path, output)

    write_report(
        report_path,
        stats,
        entries,
        args.min_epg_score,
        args.min_logo_score,
    )

    print(
        "Entradas antes de deduplicar: "
        f"{stats.entries_before_deduplication}"
    )
    print(f"URLs duplicadas eliminadas: {stats.duplicate_urls_removed}")
    print(f"Entradas finales: {stats.entries}")
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
    print(f"Informe: {report_path}")

    return 0

if __name__ == "__main__":
    raise SystemExit(main())