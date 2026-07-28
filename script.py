from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import logging
import re
import shutil
import tempfile
import time
from dataclasses import asdict, dataclass, field, replace
from enum import Enum
from pathlib import Path
from typing import Iterable
from urllib.parse import parse_qsl, unquote, urlsplit, urlunsplit
from collections import defaultdict

LOGGER = logging.getLogger("m3u_validator")

EXTINF_RE = re.compile(r"^\s*#EXTINF:", re.IGNORECASE)
OPTION_RE = re.compile(
    r"^\s*#(?P<kind>EXTVLCOPT|KODIPROP):"
    r"(?P<key>[^=]+)=(?P<value>.*)$",
    re.IGNORECASE,
)

DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/131.0.0.0 Safari/537.36"
)

HEADER_ALIASES = {
    "http-referrer": "Referer",
    "http-referer": "Referer",
    "http-user-agent": "User-Agent",
    "http-origin": "Origin",
    "http-cookie": "Cookie",
}

SENSITIVE_HEADERS = {
    "authorization",
    "cookie",
    "proxy-authorization",
    "x-api-key",
}

TRANSPORT_HEADERS = {
    "accept-encoding",
    "connection",
    "content-length",
    "host",
    "icy-metadata",
    "range",
    "transfer-encoding",
}

ESSENTIAL_HEADERS = {
    "Authorization",
    "Cookie",
    "Origin",
    "Referer",
    "User-Agent",
}

TRANSIENT_FRAGMENTS = (
    "connection reset by peer",
    "connection timed out",
    "end of file",
    "error in the pull function",
    "error number -138 occurred",
    "error number -110 occurred",
    "error number -104 occurred",
    "http error 400",
    "http error 404",
    "i/o error",
    "input/output error",
    "network is unreachable",
    "operation timed out",
    "resource temporarily unavailable",
    "server returned 400 bad request",
    "server returned 404 not found",
    "server returned 408",
    "server returned 429",
    "server returned 500",
    "server returned 502",
    "server returned 503",
    "server returned 504",
    "temporary failure in name resolution",
    "the operation timed out",
    "tls error",
)

NO_VIDEO_FRAGMENTS = (
    "matches no streams",
    "matches no streams; ignoring",
    "stream map '0:v:0' matches no streams",
    "does not contain any stream",
    "output file does not contain any stream",
)

UNSUPPORTED_FRAGMENTS = (
    "protocol not found",
    "unknown protocol",
    "demuxer not found",
    "decoder not found",
    "unknown decoder",
)

LOCAL_OPTION_ERROR_FRAGMENTS = (
    "error splitting the argument list",
    "option not found",
    "unrecognized option",
    "failed to set value",
    "trailing option",
)

class Status(str, Enum):
    ALIVE = "alive"
    DEAD = "dead"
    NO_VIDEO = "no_video"
    TIMEOUT = "timeout"
    UNSUPPORTED = "unsupported"
    ERROR = "error"

@dataclass(slots=True)
class PlaylistEntry:
    index: int
    original_lines: list[str]
    url: str
    title: str
    headers: dict[str, str] = field(default_factory=dict)

    @property
    def original_block(self) -> str:
        return "".join(self.original_lines)

@dataclass(frozen=True, slots=True)
class AttemptProfile:
    name: str
    extension_picky: bool
    header_mode: str
    force_default_user_agent: bool = False

@dataclass(slots=True)
class DecodeAttempt:
    profile: str
    frame_decoded: bool
    timed_out: bool
    unsupported_option: bool
    reason: str
    stderr: str
    return_code: int | None
    duration_seconds: float

@dataclass(slots=True)
class ValidationResult:
    index: int
    title: str
    url: str
    status: Status
    duration_seconds: float
    reason: str
    return_code: int | None = None
    attempts: int = 1
    successful_profile: str | None = None
    
class ConcurrencyController:
    def __init__(
        self,
        global_limit: int,
        per_host_limit: int,
    ) -> None:
        self.global_semaphore = asyncio.Semaphore(
            global_limit
        )
        self.per_host_limit = per_host_limit
        self.host_semaphores: dict[
            str,
            asyncio.Semaphore,
        ] = {}

    def host_key(
        self,
        entry: PlaylistEntry,
    ) -> str:
        try:
            return (
                urlsplit(entry.url).netloc.lower()
                or "<unknown>"
            )
        except ValueError:
            return "<unknown>"

    def host_semaphore(
        self,
        entry: PlaylistEntry,
    ) -> asyncio.Semaphore:
        host = self.host_key(entry)

        semaphore = self.host_semaphores.get(host)

        if semaphore is None:
            semaphore = asyncio.Semaphore(
                self.per_host_limit
            )
            self.host_semaphores[host] = semaphore

        return semaphore

    async def run(
        self,
        entry: PlaylistEntry,
        operation,
    ):
        host_semaphore = self.host_semaphore(
            entry
        )

        async with self.global_semaphore:
            async with host_semaphore:
                return await operation()

PRIMARY_PROFILE = AttemptProfile(
    name="plain",
    extension_picky=False,
    header_mode="original",
)

FALLBACK_PROFILES = (
    AttemptProfile(
        name="hls-extension-relaxed",
        extension_picky=True,
        header_mode="original",
    ),
    AttemptProfile(
        name="essential-headers",
        extension_picky=True,
        header_mode="essential",
    ),
    AttemptProfile(
        name="browser-user-agent",
        extension_picky=True,
        header_mode="essential",
        force_default_user_agent=True,
    ),
)

OPENING_URL_RE = re.compile(
    r"Opening\s+'(?P<url>https?://[^']+)'",
    re.IGNORECASE,
)

REQUEST_RE = re.compile(
    r"request:\s+GET\s+(?P<path>\S+)",
    re.IGNORECASE,
)

def is_http_404(stderr: str) -> bool:
    lower = stderr.lower()
    return any(
        marker in lower
        for marker in (
            "404 not found",
            "http error 404",
            "server returned 404",
        )
    )

def opened_urls(stderr: str) -> list[str]:
    return [
        match.group("url")
        for match in OPENING_URL_RE.finditer(stderr)
    ]

def is_segment_url(url: str) -> bool:
    path = urlsplit(url).path.lower()

    return path.endswith(
        (
            ".ts",
            ".m4s",
            ".mp4",
            ".aac",
            ".m4a",
            ".cmfv",
            ".cmfa",
        )
    )

def classify_404_stage(
    entry: PlaylistEntry,
    stderr: str,
) -> str:
    """
    Retorna:
      - manifest
      - segment
      - unknown
    """
    if not is_http_404(stderr):
        return "unknown"

    urls = opened_urls(stderr)

    if any(is_segment_url(url) for url in urls):
        return "segment"

    clean_entry_path = urlsplit(entry.url).path

    if clean_entry_path:
        return "manifest"

    return "unknown"

def normalize_return_code(return_code: int | None) -> int | None:
    if return_code is None:
        return None

    if return_code > 0x7FFFFFFF:
        return return_code - 0x100000000

    return return_code

def extract_title(extinf_line: str, fallback: str) -> str:
    _, separator, title = extinf_line.partition(",")

    if separator and title.strip():
        return title.strip()

    return fallback

def canonical_header_name(name: str) -> str:
    normalized = name.strip().lower().replace("_", "-")

    known = {
        "referer": "Referer",
        "referrer": "Referer",
        "user-agent": "User-Agent",
        "origin": "Origin",
        "cookie": "Cookie",
        "authorization": "Authorization",
        "accept": "Accept",
        "accept-language": "Accept-Language",
        "accept-encoding": "Accept-Encoding",
        "connection": "Connection",
        "host": "Host",
        "range": "Range",
    }

    return known.get(
        normalized,
        "-".join(
            piece.capitalize()
            for piece in normalized.split("-")
            if piece
        ),
    )

def sanitize_header_value(value: str) -> str:
    return (
        unquote(value)
        .replace("\r", " ")
        .replace("\n", " ")
        .strip()
    )

def merge_header(
    headers: dict[str, str],
    name: str,
    value: str,
) -> None:
    canonical_name = canonical_header_name(name)
    clean_value = sanitize_header_value(value)

    if canonical_name and clean_value:
        headers[canonical_name] = clean_value

def parse_query_headers(value: str) -> dict[str, str]:
    result: dict[str, str] = {}

    for name, header_value in parse_qsl(
        value.strip(),
        keep_blank_values=True,
        strict_parsing=False,
    ):
        merge_header(result, name, header_value)

    return result

def split_url_headers(url: str) -> tuple[str, dict[str, str]]:
    if "|" not in url:
        return url.strip(), {}

    base_url, encoded_headers = url.split("|", 1)
    return (
        base_url.strip(),
        parse_query_headers(encoded_headers),
    )

def parse_single_header(value: str) -> tuple[str, str] | None:
    if ":" not in value:
        return None

    name, header_value = value.split(":", 1)

    if not name.strip():
        return None

    return name.strip(), header_value.strip()

def resolve_headers(
    option_lines: Iterable[str],
    url_headers: dict[str, str],
    default_headers: dict[str, str] | None = None,
) -> dict[str, str]:
    headers: dict[str, str] = {}

    if default_headers:
        for name, value in default_headers.items():
            merge_header(headers, name, value)

    vlc_headers: dict[str, str] = {}
    kodi_headers: dict[str, str] = {}

    for line in option_lines:
        match = OPTION_RE.match(line.rstrip("\r\n"))

        if not match:
            continue

        kind = match.group("kind").lower()
        key = match.group("key").strip().lower()
        value = match.group("value").strip()

        if kind == "extvlcopt":
            if key in HEADER_ALIASES:
                merge_header(
                    vlc_headers,
                    HEADER_ALIASES[key],
                    value,
                )
            elif key == "http-header":
                parsed = parse_single_header(value)

                if parsed:
                    merge_header(vlc_headers, *parsed)

        elif kind == "kodiprop":
            if key in {
                "inputstream.adaptive.stream_headers",
                "inputstream.adaptive.manifest_headers",
                "inputstream.adaptive.common_headers",
            }:
                kodi_headers.update(
                    parse_query_headers(value)
                )

    headers.update(vlc_headers)
    headers.update(kodi_headers)
    headers.update(url_headers)
    return headers

def parse_playlist(
    source: Path,
    default_headers: dict[str, str] | None = None,
) -> tuple[list[str], list[PlaylistEntry]]:
    text = source.read_text(
        encoding="utf-8-sig",
        errors="replace",
    )
    lines = text.splitlines(keepends=True)

    preamble: list[str] = []
    entries: list[PlaylistEntry] = []
    position = 0
    found_first_entry = False

    while position < len(lines):
        if not EXTINF_RE.match(lines[position]):
            if not found_first_entry:
                preamble.append(lines[position])
            elif entries:
                entries[-1].original_lines.append(
                    lines[position]
                )
            else:
                preamble.append(lines[position])

            position += 1
            continue

        found_first_entry = True
        block = [lines[position]]
        extinf_line = lines[position]
        position += 1
        raw_url: str | None = None

        while position < len(lines):
            candidate = lines[position]

            if EXTINF_RE.match(candidate):
                break

            block.append(candidate)
            position += 1

            stripped = candidate.strip()

            if stripped and not stripped.startswith("#"):
                raw_url = stripped
                break

        while (
            position < len(lines)
            and not EXTINF_RE.match(lines[position])
        ):
            block.append(lines[position])
            position += 1

        title = extract_title(
            extinf_line,
            f"Entrada {len(entries) + 1}",
        )

        if raw_url is None:
            entries.append(
                PlaylistEntry(
                    index=len(entries),
                    original_lines=block,
                    url="",
                    title=title,
                    headers={},
                )
            )
            continue

        clean_url, url_headers = split_url_headers(raw_url)
        headers = resolve_headers(
            block,
            url_headers,
            default_headers,
        )

        entries.append(
            PlaylistEntry(
                index=len(entries),
                original_lines=block,
                url=clean_url,
                title=title,
                headers=headers,
            )
        )

    return preamble, entries

def redact_url(url: str) -> str:
    try:
        parsed = urlsplit(url)

        return urlunsplit(
            (
                parsed.scheme,
                parsed.netloc,
                parsed.path,
                "",
                "",
            )
        )
    except ValueError:
        return "<URL>"

def sanitize_message(
    message: str,
    entry: PlaylistEntry,
) -> str:
    sanitized = message

    if entry.url:
        sanitized = sanitized.replace(
            entry.url,
            "<URL>",
        )

    for name, value in entry.headers.items():
        if name.lower() in SENSITIVE_HEADERS and value:
            sanitized = sanitized.replace(
                value,
                "<REDACTED>",
            )

    lines = [
        line.strip()
        for line in sanitized.splitlines()
        if line.strip()
    ]

    return "\n".join(lines[-30:])[-5_000:]

def last_diagnostic_line(stderr: str) -> str:
    ignored = (
        "exiting normally",
        "immediate exit requested",
        "received signal",
    )

    for line in reversed(stderr.splitlines()):
        stripped = line.strip()

        if not stripped:
            continue

        if any(
            fragment in stripped.lower()
            for fragment in ignored
        ):
            continue

        return stripped

    return ""

def essential_headers_only(
    headers: dict[str, str],
) -> dict[str, str]:
    return {
        canonical_header_name(name): value
        for name, value in headers.items()
        if canonical_header_name(name)
        in ESSENTIAL_HEADERS
    }

def headers_for_profile(
    entry: PlaylistEntry,
    profile: AttemptProfile,
) -> dict[str, str]:
    if profile.header_mode == "essential":
        headers = essential_headers_only(entry.headers)
    else:
        headers = dict(entry.headers)

    if profile.force_default_user_agent:
        headers["User-Agent"] = DEFAULT_USER_AGENT

    return headers

def ffmpeg_header_block(
    headers: dict[str, str],
) -> str:
    custom_headers: list[str] = []

    for name, value in headers.items():
        canonical_name = canonical_header_name(name)

        if canonical_name.lower() in TRANSPORT_HEADERS:
            continue

        if canonical_name in {"User-Agent", "Referer"}:
            continue

        clean_value = sanitize_header_value(value)

        if clean_value:
            custom_headers.append(
                f"{canonical_name}: {clean_value}"
            )

    if not custom_headers:
        return ""

    return "\r\n".join(custom_headers) + "\r\n"

def build_network_options(
    headers: dict[str, str],
    rw_timeout_seconds: float,
) -> list[str]:
    options = [
        "-rw_timeout",
        str(int(rw_timeout_seconds * 1_000_000)),
    ]

    user_agent = headers.get("User-Agent")
    referer = headers.get("Referer")
    custom_headers = ffmpeg_header_block(headers)

    if user_agent:
        options.extend(
            [
                "-user_agent",
                sanitize_header_value(user_agent),
            ]
        )

    if referer:
        options.extend(
            [
                "-referer",
                sanitize_header_value(referer),
            ]
        )

    if custom_headers:
        options.extend(
            [
                "-headers",
                custom_headers,
            ]
        )

    return options

def build_ffmpeg_command(
    ffmpeg_path: str,
    entry: PlaylistEntry,
    output_file: Path,
    profile: AttemptProfile,
    rw_timeout_seconds: float,
    analyzeduration_seconds: float,
    probesize_bytes: int,
) -> list[str]:
    headers = headers_for_profile(entry, profile)

    command = [
        ffmpeg_path,
        "-hide_banner",
        "-nostdin",
        "-loglevel",
        "verbose",
    ]

    command.extend(
        build_network_options(
            headers,
            rw_timeout_seconds,
        )
    )

    if profile.extension_picky:
        # Opción privada del demuxer HLS. Debe ir antes de -i.
        command.extend(["-extension_picky", "0"])

    command.extend(
        [
            "-analyzeduration",
            str(int(analyzeduration_seconds * 1_000_000)),
            "-probesize",
            str(probesize_bytes),
            "-i",
            entry.url,
            "-map",
            "0:v:0",
            "-frames:v",
            "1",
            "-an",
            "-sn",
            "-dn",
            "-f",
            "framemd5",
            "-y",
            str(output_file),
        ]
    )

    return command

async def terminate_process(
    process: asyncio.subprocess.Process,
    grace_seconds: float = 1.0,
) -> None:
    if process.returncode is not None:
        return

    with contextlib.suppress(ProcessLookupError):
        process.terminate()

    try:
        await asyncio.wait_for(
            process.wait(),
            timeout=grace_seconds,
        )
    except TimeoutError:
        if process.returncode is None:
            with contextlib.suppress(ProcessLookupError):
                process.kill()

            await process.wait()

def framemd5_contains_frame(path: Path) -> bool:
    try:
        if not path.is_file():
            return False

        for line in path.read_text(
            encoding="utf-8",
            errors="replace",
        ).splitlines():
            stripped = line.strip()

            if not stripped:
                continue

            if stripped.startswith("#"):
                continue

            # Una línea de frame tiene seis campos CSV:
            # stream, dts, pts, duration, size, hash
            parts = [
                part.strip()
                for part in stripped.split(",", maxsplit=5)
            ]

            if len(parts) == 6 and parts[-1]:
                return True

        return False

    except OSError:
        return False

async def run_decode_attempt(
    entry: PlaylistEntry,
    ffmpeg_path: str,
    profile: AttemptProfile,
    timeout_seconds: float,
    rw_timeout_seconds: float,
    analyzeduration_seconds: float,
    probesize_bytes: int,
) -> DecodeAttempt:
    started = time.monotonic()
    process: asyncio.subprocess.Process | None = None

    with tempfile.TemporaryDirectory(
        prefix="m3u-validator-"
    ) as temporary_directory:
        output_file = (
            Path(temporary_directory)
            / "frame.framemd5"
        )

        try:
            command = build_ffmpeg_command(
                ffmpeg_path=ffmpeg_path,
                entry=entry,
                output_file=output_file,
                profile=profile,
                rw_timeout_seconds=rw_timeout_seconds,
                analyzeduration_seconds=analyzeduration_seconds,
                probesize_bytes=probesize_bytes,
            )

            process = await asyncio.create_subprocess_exec(
                *command,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.PIPE,
            )

            try:
                _, stderr_bytes = await asyncio.wait_for(
                    process.communicate(),
                    timeout=timeout_seconds,
                )
                timed_out = False

            except TimeoutError:
                timed_out = True
                await terminate_process(process)

                if process.stderr is not None:
                    stderr_bytes = await process.stderr.read()
                else:
                    stderr_bytes = b""

            stderr = sanitize_message(
                stderr_bytes.decode(
                    "utf-8",
                    errors="replace",
                ),
                entry,
            )

            frame_decoded = framemd5_contains_frame(
                output_file
            )

            lower = stderr.lower()
            unsupported_option = (
                profile.extension_picky
                and (
                    "option not found" in lower
                    or "unrecognized option" in lower
                    or "error setting option extension_picky"
                    in lower
                )
            )

            return DecodeAttempt(
                profile=profile.name,
                frame_decoded=frame_decoded,
                timed_out=timed_out,
                unsupported_option=unsupported_option,
                reason=(
                    "Se generó el hash de un fotograma"
                    if frame_decoded
                    else (
                        "Timeout sin fotograma"
                        if timed_out
                        else (
                            "FFmpeg terminó sin emitir "
                            "un fotograma"
                        )
                    )
                ),
                stderr=stderr,
                return_code=normalize_return_code(
                    process.returncode
                ),
                duration_seconds=(
                    time.monotonic() - started
                ),
            )

        except asyncio.CancelledError:
            if process is not None:
                await terminate_process(process)

            raise

        except Exception as exc:
            if process is not None:
                await terminate_process(process)

            return DecodeAttempt(
                profile=profile.name,
                frame_decoded=False,
                timed_out=False,
                unsupported_option=False,
                reason=f"{type(exc).__name__}: {exc}",
                stderr="",
                return_code=normalize_return_code(
                    process.returncode
                    if process is not None
                    else None
                ),
                duration_seconds=(
                    time.monotonic() - started
                ),
            )

def is_transient_failure(stderr: str) -> bool:
    lower = stderr.lower()

    return any(
        fragment in lower
        for fragment in TRANSIENT_FRAGMENTS
    )

def classify_failure(
    attempt: DecodeAttempt,
) -> tuple[Status, str]:
    lower = attempt.stderr.lower()

    if (
        any(
            fragment in lower
            for fragment in LOCAL_OPTION_ERROR_FRAGMENTS
        )
        and not attempt.unsupported_option
    ):
        diagnostic = last_diagnostic_line(
            attempt.stderr
        )
        reason = "FFmpeg rechazó una opción o salida local"

        if diagnostic:
            reason = f"{reason}: {diagnostic}"

        return Status.ERROR, reason

    if attempt.timed_out:
        diagnostic = last_diagnostic_line(
            attempt.stderr
        )
        reason = "No se obtuvo ningún fotograma antes del timeout"

        if diagnostic:
            reason = f"{reason}: {diagnostic}"

        return Status.TIMEOUT, reason

    if any(
        fragment in lower
        for fragment in NO_VIDEO_FRAGMENTS
    ):
        return (
            Status.NO_VIDEO,
            "FFmpeg no encontró un stream de vídeo seleccionable",
        )

    if any(
        fragment in lower
        for fragment in UNSUPPORTED_FRAGMENTS
    ):
        return (
            Status.UNSUPPORTED,
            "Protocolo, demuxer o decodificador no soportado",
        )

    if (
        "401 unauthorized" in lower
        or "http error 401" in lower
        or "server returned 401" in lower
    ):
        return (
            Status.DEAD,
            "HTTP 401: credenciales inválidas",
        )

    if (
        "403 forbidden" in lower
        or "http error 403" in lower
        or "server returned 403" in lower
    ):
        return (
            Status.DEAD,
            "HTTP 403: acceso denegado",
        )

    if (
        "404 not found" in lower
        or "http error 404" in lower
        or "server returned 404" in lower
    ):
        return (
            Status.DEAD,
            "HTTP 404 persistente tras perfiles y reintentos",
        )

    if (
        "400 bad request" in lower
        or "http error 400" in lower
        or "server returned 400" in lower
    ):
        return (
            Status.DEAD,
            "HTTP 400 persistente tras perfiles y reintentos",
        )

    if "connection refused" in lower:
        return (
            Status.DEAD,
            "Conexión rechazada",
        )

    if "invalid data found" in lower:
        return (
            Status.DEAD,
            "Contenido o manifiesto inválido",
        )

    if is_transient_failure(attempt.stderr):
        diagnostic = last_diagnostic_line(
            attempt.stderr
        )
        reason = "Fallo transitorio persistente"

        if diagnostic:
            reason = f"{reason}: {diagnostic}"

        return Status.TIMEOUT, reason

    diagnostic = last_diagnostic_line(
        attempt.stderr
    )
    reason = (
        "FFmpeg terminó sin decodificar vídeo "
        f"con código {attempt.return_code}"
    )

    if diagnostic:
        reason = f"{reason}: {diagnostic}"

    return Status.DEAD, reason

def should_retry(attempt: DecodeAttempt) -> bool:
    if attempt.frame_decoded:
        return False

    if attempt.unsupported_option:
        return False

    if attempt.timed_out:
        return True

    if is_transient_failure(attempt.stderr):
        return True

    lower = attempt.stderr.lower()

    return any(
        marker in lower
        for marker in (
            "400 bad request",
            "404 not found",
            "http error 400",
            "http error 404",
            "server returned 400",
            "server returned 404",
        )
    )

async def validate_entry_once(
    entry: PlaylistEntry,
    ffmpeg_path: str,
    profile: AttemptProfile,
    timeout_seconds: float,
    rw_timeout_seconds: float,
    analyzeduration_seconds: float,
    probesize_bytes: int,
) -> DecodeAttempt:
    return await run_decode_attempt(
        entry=entry,
        ffmpeg_path=ffmpeg_path,
        profile=profile,
        timeout_seconds=timeout_seconds,
        rw_timeout_seconds=rw_timeout_seconds,
        analyzeduration_seconds=analyzeduration_seconds,
        probesize_bytes=probesize_bytes,
    )
    
    
async def validate_all(
    entries: list[PlaylistEntry],
    ffmpeg_path: str,
    concurrency: int,
    per_host: int,
    timeout_seconds: float,
    retry_timeout_seconds: float,
    rw_timeout_seconds: float,
    analyzeduration_seconds: float,
    probesize_bytes: int,
    retries: int,
    retry_delay_seconds: float,
) -> list[ValidationResult]:
    controller = ConcurrencyController(
        global_limit=concurrency,
        per_host_limit=per_host,
    )

    started_at = {
        entry.index: time.monotonic()
        for entry in entries
    }
    attempts_count = {
        entry.index: 0
        for entry in entries
    }
    final_results: dict[int, ValidationResult] = {}
    last_attempts: dict[int, DecodeAttempt] = {}

    pending: list[PlaylistEntry] = list(entries)

    async def execute(
        entry: PlaylistEntry,
        profile: AttemptProfile,
        timeout: float,
    ) -> tuple[PlaylistEntry, DecodeAttempt]:
        async def operation() -> DecodeAttempt:
            return await validate_entry_once(
                entry=entry,
                ffmpeg_path=ffmpeg_path,
                profile=profile,
                timeout_seconds=timeout,
                rw_timeout_seconds=rw_timeout_seconds,
                analyzeduration_seconds=analyzeduration_seconds,
                probesize_bytes=probesize_bytes,
            )

        attempt = await controller.run(
            entry,
            operation,
        )
        return entry, attempt

    # Ronda primaria: exactamente el perfil plain para todos.
    primary_tasks = [
        asyncio.create_task(
            execute(
                entry,
                PRIMARY_PROFILE,
                timeout_seconds,
            )
        )
        for entry in pending
    ]

    primary_pairs = await asyncio.gather(
        *primary_tasks
    )

    pending = []

    for entry, attempt in primary_pairs:
        attempts_count[entry.index] += 1
        last_attempts[entry.index] = attempt

        if attempt.frame_decoded:
            final_results[entry.index] = ValidationResult(
                index=entry.index,
                title=entry.title,
                url=entry.url,
                status=Status.ALIVE,
                duration_seconds=(
                    time.monotonic()
                    - started_at[entry.index]
                ),
                reason=(
                    "Se decodificó un fotograma "
                    "con el perfil plain"
                ),
                return_code=attempt.return_code,
                attempts=attempts_count[entry.index],
                successful_profile=attempt.profile,
            )
        else:
            pending.append(entry)

    # Rondas de reintento normales. Se obtiene una nueva ventana HLS.
    for retry_number in range(retries):
        if not pending:
            break

        delay = retry_delay_seconds * (
            2 ** retry_number
        )

        LOGGER.info(
            "Esperando %.1f s antes de la ronda "
            "de reintento %d para %d streams",
            delay,
            retry_number + 1,
            len(pending),
        )

        # La espera ocurre sin ocupar semáforos.
        await asyncio.sleep(delay)

        retry_tasks = [
            asyncio.create_task(
                execute(
                    entry,
                    PRIMARY_PROFILE,
                    retry_timeout_seconds,
                )
            )
            for entry in pending
        ]

        retry_pairs = await asyncio.gather(
            *retry_tasks
        )

        next_pending: list[PlaylistEntry] = []

        for entry, attempt in retry_pairs:
            attempts_count[entry.index] += 1
            last_attempts[entry.index] = attempt

            if attempt.frame_decoded:
                final_results[entry.index] = (
                    ValidationResult(
                        index=entry.index,
                        title=entry.title,
                        url=entry.url,
                        status=Status.ALIVE,
                        duration_seconds=(
                            time.monotonic()
                            - started_at[entry.index]
                        ),
                        reason=(
                            "Se decodificó un fotograma "
                            f"en la ronda {retry_number + 1}"
                        ),
                        return_code=attempt.return_code,
                        attempts=attempts_count[
                            entry.index
                        ],
                        successful_profile=(
                            attempt.profile
                        ),
                    )
                )
            else:
                next_pending.append(entry)

        pending = next_pending

    # Solo quienes continúan fallando prueban perfiles especiales.
    for profile in FALLBACK_PROFILES:
        if not pending:
            break

        await asyncio.sleep(retry_delay_seconds)

        fallback_tasks = [
            asyncio.create_task(
                execute(
                    entry,
                    profile,
                    retry_timeout_seconds,
                )
            )
            for entry in pending
        ]

        fallback_pairs = await asyncio.gather(
            *fallback_tasks
        )

        next_pending = []

        for entry, attempt in fallback_pairs:
            attempts_count[entry.index] += 1
            last_attempts[entry.index] = attempt

            if attempt.frame_decoded:
                final_results[entry.index] = (
                    ValidationResult(
                        index=entry.index,
                        title=entry.title,
                        url=entry.url,
                        status=Status.ALIVE,
                        duration_seconds=(
                            time.monotonic()
                            - started_at[entry.index]
                        ),
                        reason=(
                            "Se decodificó un fotograma "
                            f"con el perfil {profile.name}"
                        ),
                        return_code=attempt.return_code,
                        attempts=attempts_count[
                            entry.index
                        ],
                        successful_profile=profile.name,
                    )
                )
            else:
                next_pending.append(entry)

        pending = next_pending

    # Clasificación final de los que nunca produjeron imagen.
    for entry in pending:
        last_attempt = last_attempts[entry.index]
        status, reason = classify_failure(
            last_attempt
        )

        if is_http_404(last_attempt.stderr):
            stage = classify_404_stage(
                entry,
                last_attempt.stderr,
            )

            if stage == "segment":
                reason = (
                    "HTTP 404 en un segmento HLS tras "
                    "actualizar el manifiesto y reintentar"
                )
            elif stage == "manifest":
                reason = (
                    "HTTP 404 en el manifiesto principal "
                    "tras varias rondas"
                )

        final_results[entry.index] = ValidationResult(
            index=entry.index,
            title=entry.title,
            url=entry.url,
            status=status,
            duration_seconds=(
                time.monotonic()
                - started_at[entry.index]
            ),
            reason=reason,
            return_code=last_attempt.return_code,
            attempts=attempts_count[entry.index],
            successful_profile=None,
        )

    results = [
        final_results[entry.index]
        for entry in entries
    ]

    # Logging final estrictamente en orden.
    total = len(results)

    for position, result in enumerate(
        results,
        start=1,
    ):
        level = (
            logging.INFO
            if result.status is Status.ALIVE
            else logging.WARNING
        )

        LOGGER.log(
            level,
            "[%d/%d] %-11s %6.1fs | %s | %s",
            position,
            total,
            result.status.value.upper(),
            result.duration_seconds,
            result.title,
            result.reason,
        )

    return results
    
    
def normalized_preamble(
    preamble: list[str],
) -> str:
    content = "".join(preamble)

    if not content.lstrip("\ufeff").startswith(
        "#EXTM3U"
    ):
        return "#EXTM3U\n"

    if content and not content.endswith(
        ("\n", "\r")
    ):
        content += "\n"

    return content

def write_m3u(
    destination: Path,
    preamble: list[str],
    entries: list[PlaylistEntry],
) -> None:
    destination.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    with destination.open(
        "w",
        encoding="utf-8",
        newline="",
    ) as output:
        output.write(
            normalized_preamble(preamble)
        )

        for entry in entries:
            output.write(
                entry.original_block
            )

            if not entry.original_block.endswith(
                ("\n", "\r")
            ):
                output.write("\n")

def write_report(
    destination: Path,
    results: list[ValidationResult],
) -> None:
    destination.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    serializable = [
        {
            **asdict(result),
            "status": result.status.value,
            "safe_url": redact_url(result.url),
            "url": "<REDACTED>",
        }
        for result in results
    ]

    destination.write_text(
        json.dumps(
            serializable,
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

def configure_logging(
    verbose: bool,
) -> None:
    logging.basicConfig(
        level=(
            logging.DEBUG
            if verbose
            else logging.INFO
        ),
        format=(
            "%(asctime)s | "
            "%(levelname)-7s | "
            "%(message)s"
        ),
        datefmt="%H:%M:%S",
    )

def parse_default_headers(
    values: list[str],
) -> dict[str, str]:
    headers: dict[str, str] = {}

    for value in values:
        parsed = parse_single_header(value)

        if not parsed:
            raise ValueError(
                f"Cabecera inválida: {value!r}. "
                "Use 'Nombre: valor'."
            )

        merge_header(headers, *parsed)

    return headers

def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Valida entradas M3U mediante "
            "decodificación real de vídeo."
        )
    )

    parser.add_argument(
        "input",
        type=Path,
    )
    parser.add_argument(
        "--alive",
        type=Path,
        default=Path("funcionales.m3u"),
    )
    parser.add_argument(
        "--dead",
        type=Path,
        default=Path("descartados.m3u"),
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=Path("reporte.json"),
    )
    parser.add_argument(
        "--concurrency",
        type=int,
        default=4,
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=60.0,
    )
    parser.add_argument(
        "--retry-timeout",
        type=float,
        default=90.0,
    )
    parser.add_argument(
        "--rw-timeout",
        type=float,
        default=20.0,
    )
    parser.add_argument(
        "--analyzeduration",
        type=float,
        default=20.0,
    )
    parser.add_argument(
        "--probesize",
        type=int,
        default=20_000_000,
    )
    parser.add_argument(
        "--retries",
        type=int,
        default=1,
        help="Reintentos por perfil",
    )
    parser.add_argument(
        "--retry-delay",
        type=float,
        default=5.0,
    )
    parser.add_argument(
        "--header",
        action="append",
        default=[],
    )
    parser.add_argument(
        "--ffmpeg",
        default="ffmpeg",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
    )
    parser.add_argument(
        "--per-host",
        type=int,
        default=1,
        help=(
            "Máximo de validaciones simultáneas "
            "contra el mismo host"
        ),
    )

    return parser

def validate_arguments(
    args: argparse.Namespace,
) -> list[str]:
    errors: list[str] = []

    for name, value in (
        ("--timeout", args.timeout),
        ("--retry-timeout", args.retry_timeout),
        ("--rw-timeout", args.rw_timeout),
        ("--analyzeduration", args.analyzeduration),
        ("--retry-delay", args.retry_delay),
    ):
        if value <= 0:
            errors.append(
                f"{name} debe ser mayor que cero"
            )

    if args.concurrency < 1:
        errors.append(
            "--concurrency debe ser mayor que cero"
        )

    if args.probesize < 32:
        errors.append(
            "--probesize debe ser al menos 32"
        )

    if args.retries < 0:
        errors.append(
            "--retries no puede ser negativo"
        )
        
    if args.per_host < 1:
        errors.append(
            "--per-host debe ser mayor que cero"
        )

    return errors

async def async_main(
    args: argparse.Namespace,
) -> int:
    argument_errors = validate_arguments(
        args
    )

    if argument_errors:
        for error in argument_errors:
            LOGGER.error("%s", error)

        return 2

    ffmpeg_path = shutil.which(
        args.ffmpeg
    )

    if ffmpeg_path is None:
        LOGGER.error(
            "No se encontró FFmpeg: %s",
            args.ffmpeg,
        )
        return 2

    if not args.input.is_file():
        LOGGER.error(
            "La playlist no existe: %s",
            args.input,
        )
        return 2

    try:
        default_headers = parse_default_headers(
            args.header
        )
    except ValueError as exc:
        LOGGER.error("%s", exc)
        return 2

    preamble, entries = parse_playlist(
        args.input,
        default_headers,
    )

    if not entries:
        LOGGER.error(
            "No se encontraron entradas EXTINF"
        )
        return 2

    LOGGER.info(
        "Analizando %d entradas con concurrencia %d",
        len(entries),
        args.concurrency,
    )

    results = await validate_all(
        entries=entries,
        ffmpeg_path=ffmpeg_path,
        concurrency=args.concurrency,
        per_host=args.per_host,
        timeout_seconds=args.timeout,
        retry_timeout_seconds=args.retry_timeout,
        rw_timeout_seconds=args.rw_timeout,
        analyzeduration_seconds=args.analyzeduration,
        probesize_bytes=args.probesize,
        retries=args.retries,
        retry_delay_seconds=args.retry_delay,
    )

    result_by_index = {
        result.index: result
        for result in results
    }

    alive_entries = [
        entry
        for entry in entries
        if result_by_index[entry.index].status
        is Status.ALIVE
    ]

    dead_entries = [
        entry
        for entry in entries
        if result_by_index[entry.index].status
        is not Status.ALIVE
    ]

    write_m3u(
        args.alive,
        preamble,
        alive_entries,
    )
    write_m3u(
        args.dead,
        preamble,
        dead_entries,
    )
    write_report(
        args.report,
        results,
    )

    LOGGER.info(
        "Finalizado: %d funcionales, %d descartados",
        len(alive_entries),
        len(dead_entries),
    )

    return 0

def main() -> int:
    parser = build_argument_parser()
    args = parser.parse_args()
    configure_logging(args.verbose)

    try:
        return asyncio.run(
            async_main(args)
        )
    except KeyboardInterrupt:
        LOGGER.warning(
            "Ejecución cancelada"
        )
        return 130
    except Exception:
        LOGGER.exception(
            "Error fatal no controlado"
        )
        return 1

if __name__ == "__main__":
    raise SystemExit(main())