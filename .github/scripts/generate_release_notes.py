#!/usr/bin/env python3
"""
Computes the next semantic version and builds a comprehensive, plain-language,
emoji-free changelog covering ALL changes between the previous release/push
and HEAD for OffGrid Music.

Crucially, this handles squashed pushes (e.g., multiple prompts in Google AI Studio
pushed as a single commit whose subject only mentions the final prompt) by combining:
  1. Every commit subject AND commit body bullet between the previous tag and HEAD.
  2. A full git diff analyzer inspecting newly added features/files, newly added
     Settings toggles/sections, and modified functional modules between prev_tag..HEAD.
  3. Optional Gemini summarization when GEMINI_API_KEY is configured in CI secrets,
     with automatic fallback to the deterministic analyzer.
"""

import json
import os
import re
import subprocess
import sys
import unicodedata
import urllib.error
import urllib.request
from typing import Dict, List, Optional, Set, Tuple

MAX_TOTAL_BULLETS = 16
MAX_PER_SECTION = 8

# High-signal file/component descriptions when a newly added file or heavily modified
# module is detected in the diff (handles squashed commits whose message omits earlier prompts).
EXACT_FILE_FEATURE_MAP: Dict[str, Tuple[str, str]] = {
    "SmartShuffleEngine.kt": (
        "Highlights",
        "Added Smart Shuffle (Smart Queue) to dynamically reorder your queue based on listening history and genre preferences",
    ),
    "BottomNavOrderManagerCard.kt": (
        "Highlights",
        "Added bottom navigation bar reordering in Settings so you can arrange navigation tabs in your preferred order",
    ),
    "GeminiApiKeyGuideDialog.kt": (
        "Highlights",
        "Added a step-by-step guide in Settings showing how to create and add your Gemini API key",
    ),
    "AppUpdateDialog.kt": (
        "Improvements",
        "Streamlined the in-app update dialog to focus on clear highlights, improvements, and fixes",
    ),
    "ArtworkColorExtractor.kt": (
        "Improvements",
        "Improved dynamic artwork color extraction to preserve vibrant colors and avoid overly brown themes",
    ),
    "AudioTagWriter.kt": (
        "Improvements",
        "Optimized local audio tag writing and indexed song storage so edits stay synced locally",
    ),
}

# Module-level fallback descriptions used when a functional module changed meaningfully
# in prev_tag..HEAD and isn't already covered by commit messages or newly added files.
MODULE_CHANGE_DESCRIPTIONS: List[Tuple[str, str, str, Set[str]]] = [
    (
        "SmartShuffleEngine",
        "Highlights",
        "Updated Smart Shuffle queue ranking and listening preference matching",
        {"smart shuffle", "smart queue", "shuffle"},
    ),
    (
        "BottomNavOrderManagerCard",
        "Improvements",
        "Updated bottom navigation bar customization and tab locking controls",
        {"bottom bar", "bottom nav", "navigation bar", "reorder", "jam"},
    ),
    (
        "BottomNavBar",
        "Improvements",
        "Updated bottom navigation bar layout and tab visibility",
        {"bottom bar", "bottom nav", "navigation bar", "jam"},
    ),
    (
        "GeminiApiKeyGuideDialog",
        "Improvements",
        "Updated the Gemini API key setup guide in Settings",
        {"api key", "gemini"},
    ),
    (
        "ArtworkColorExtractor",
        "Improvements",
        "Improved dynamic color extraction and adaptive theme gradients from album artwork",
        {"color extraction", "dynamic color", "gradient", "theme", "brown", "palette"},
    ),
    (
        "WidgetBitmapRenderer",
        "Improvements",
        "Refined home screen widget colors and artwork rendering",
        {"widget", "color extraction", "palette"},
    ),
    (
        "AudioTagWriter",
        "Improvements",
        "Optimized local audio metadata and indexed track syncing",
        {"indexed", "tag", "metadata", "local", "storage"},
    ),
    (
        "MusicRepository",
        "Improvements",
        "Optimized local library indexing and track synchronization",
        {"indexed", "library", "sync", "local", "scan"},
    ),
    (
        "HomeFeedSections",
        "Improvements",
        "Updated Home feed sections and music recommendations",
        {"home feed", "suggestions", "new music mix", "mix", "section"},
    ),
    (
        "SearchScreen",
        "Improvements",
        "Improved Search screen responsiveness and keyboard behavior",
        {"search", "keyboard"},
    ),
    (
        "AppUpdateDialog",
        "Improvements",
        "Improved the in-app update changelog presentation",
        {"update", "changelog", "notification"},
    ),
    (
        "AppUpdateRepository",
        "Improvements",
        "Improved update detection and multi-release changelog summaries",
        {"update", "changelog", "release"},
    ),
    (
        "ReleaseInfo",
        "Improvements",
        "Improved release note formatting and multi-change changelog aggregation",
        {"update", "changelog", "release"},
    ),
    (
        "NowPlayingScreen",
        "Improvements",
        "Polished Now Playing screen visuals and playback controls",
        {"now playing", "player", "theme", "gradient"},
    ),
    (
        "MiniPlayer",
        "Improvements",
        "Refined Mini Player layout and playback controls",
        {"mini player", "miniplayer"},
    ),
    (
        "PlaybackManager",
        "Improvements",
        "Improved playback queue management and audio stability",
        {"playback", "queue", "smart shuffle", "smart queue", "audio"},
    ),
    (
        "MusicService",
        "Improvements",
        "Improved background audio service reliability and media controls",
        {"playback", "service", "notification", "background"},
    ),
    (
        "JamScreen",
        "Improvements",
        "Improved Off-Grid Jam group listening interface and controls",
        {"jam", "group listening"},
    ),
    (
        "JamFeatureController",
        "Improvements",
        "Improved Off-Grid Jam sync and session reliability",
        {"jam", "sync"},
    ),
    (
        "Lyrics",
        "Improvements",
        "Improved lyrics display, synchronization, and translation",
        {"lyrics", "translat", "transcrib"},
    ),
    (
        "Equalizer",
        "Improvements",
        "Refined equalizer presets and audio effects",
        {"equalizer", "eq", "audio effect"},
    ),
    (
        "Playlist",
        "Improvements",
        "Improved playlist organization and management",
        {"playlist"},
    ),
    (
        "AccountScreen",
        "Improvements",
        "Improved account settings and cloud sync options",
        {"account", "sign-in", "sign in", "cloud", "sync"},
    ),
    (
        "AuthScreen",
        "Improvements",
        "Improved sign-in experience for Google and Email accounts",
        {"auth", "sign-in", "sign in", "login"},
    ),
]


def run_git(*args: str) -> str:
    proc = subprocess.run(
        ["git", *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    return proc.stdout.strip() if proc.returncode == 0 else ""


def git_ref_exists(ref: str) -> bool:
    if not ref:
        return False
    proc = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", ref],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    return proc.returncode == 0


def git_rev_parse(ref: str) -> str:
    return run_git("rev-parse", ref)


def parse_semver(text: str) -> Optional[Tuple[int, int, int]]:
    m = re.match(r"^v?(\d+)\.(\d+)\.(\d+)$", text.strip())
    if not m:
        return None
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)))


def strip_emojis(text: str) -> str:
    out = []
    for ch in text:
        cp = ord(ch)
        cat = unicodedata.category(ch)
        if (
            cat == "So"
            or cp in (0xFE0E, 0xFE0F, 0x200D)
            or 0x1F000 <= cp <= 0x1FAFF
            or 0x2600 <= cp <= 0x27BF
        ):
            continue
        out.append(ch)
    return re.sub(r"\s{2,}", " ", "".join(out)).strip()


def split_camel_case(name: str) -> str:
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1 \2", s)
    return re.sub(r"\s+", " ", s).strip()


def classify_bucket(text: str) -> str:
    lower = text.lower().strip()
    if re.match(r"^(fixed|fix|resolved|prevented|corrected|patched|stopped)\b", lower) or any(
        kw in lower for kw in ("bug", "crash", "issue", "freeze", "broken", "error", "glitch")
    ):
        return "Fixes"
    if re.match(r"^(added|add|introduced|new|enabled|implemented|created|support)\b", lower):
        return "Highlights"
    return "Improvements"


def to_plain_note(raw: str) -> Optional[str]:
    cleaned = strip_emojis(raw)
    cleaned = re.sub(r"<[^>]*>", "", cleaned)
    cleaned = cleaned.replace("**", "").replace("__", "").replace("`", "")
    cleaned = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", cleaned)
    cleaned = re.sub(r"^[-*•]\s+", "", cleaned).strip()
    cleaned = re.sub(r"\s*\(#[0-9]+\)\s*$", "", cleaned)
    cleaned = re.sub(r"\s*\([0-9a-f]{7,40}\)\s*$", "", cleaned)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()

    if not cleaned or len(cleaned) < 6:
        return None

    lower = cleaned.lower()
    if (
        lower.startswith("merge ")
        or lower.startswith('revert "merge ')
        or lower.startswith("signed-off-by:")
        or lower.startswith("co-authored-by:")
        or lower.startswith("change-id:")
        or lower.startswith("http://")
        or lower.startswith("https://")
        or re.match(r"^(ci|build|chore\(release\))\b(\([^)]*\))?!?:\s*", lower)
        or lower in ("update", "updates", "changes", "wip", "misc", "fix", "fixes")
    ):
        return None

    prefix_map = [
        (r"^fix(\([^)]*\))?!?:\s*", "Fixed "),
        (r"^bugfix(\([^)]*\))?!?:\s*", "Fixed "),
        (r"^hotfix(\([^)]*\))?!?:\s*", "Fixed "),
        (r"^feat(\([^)]*\))?!?:\s*", "Added "),
        (r"^feature(\([^)]*\))?!?:\s*", "Added "),
        (r"^perf(\([^)]*\))?!?:\s*", "Improved "),
        (r"^refactor(\([^)]*\))?!?:\s*", "Refined "),
        (r"^style(\([^)]*\))?!?:\s*", "Updated "),
        (r"^ui(\([^)]*\))?!?:\s*", "Updated "),
        (r"^ux(\([^)]*\))?!?:\s*", "Improved "),
        (r"^docs(\([^)]*\))?!?:\s*", "Updated documentation for "),
        (r"^chore(\([^)]*\))?!?:\s*", "Updated "),
    ]
    for pattern, replacement in prefix_map:
        if re.match(pattern, cleaned, flags=re.IGNORECASE):
            rest = re.sub(pattern, "", cleaned, count=1, flags=re.IGNORECASE).strip()
            if not rest:
                return None
            if replacement.endswith(" ") and rest[:1].isupper() and not rest[:2].isupper():
                rest = rest[:1].lower() + rest[1:]
            cleaned = replacement + rest
            break

    if cleaned and cleaned[0].islower():
        cleaned = cleaned[0].upper() + cleaned[1:]

    # Trim trailing redundant punctuation except period
    cleaned = cleaned.rstrip(" ;:,")
    return cleaned


def normalize_tokens(text: str) -> Set[str]:
    stop = {
        "the", "and", "for", "with", "that", "this", "from", "into", "your", "when",
        "added", "fixed", "improved", "updated", "refined", "new", "app", "screen",
        "option", "setting", "settings", "feature", "support", "now", "more", "all",
    }
    words = re.findall(r"[a-z0-9]{3,}", text.lower())
    return {w for w in words if w not in stop}


def is_similar_entry(candidate: str, existing_list: List[str]) -> bool:
    cand_lower = candidate.lower()
    cand_tokens = normalize_tokens(candidate)
    for existing in existing_list:
        ex_lower = existing.lower()
        if cand_lower == ex_lower or cand_lower in ex_lower or ex_lower in cand_lower:
            return True
        ex_tokens = normalize_tokens(existing)
        if cand_tokens and ex_tokens:
            overlap = len(cand_tokens & ex_tokens)
            smaller = min(len(cand_tokens), len(ex_tokens))
            if smaller >= 2 and (overlap / smaller) >= 0.72:
                return True
    return False


def collect_commit_notes(base_ref: str) -> List[Tuple[str, str]]:
    """
    Collects ALL commit subjects AND bulleted commit body lines from base_ref..HEAD.
    Returns list of (bucket, note_text).
    """
    sep_commit = "---OFFGRID_COMMIT_SEP---"
    sep_field = "---OFFGRID_FIELD_SEP---"
    fmt = f"%s{sep_field}%b{sep_commit}"

    if base_ref:
        raw_log = run_git("log", f"{base_ref}..HEAD", f"--pretty=format:{fmt}")
    else:
        raw_log = run_git("log", "-n", "12", f"--pretty=format:{fmt}")

    if not raw_log:
        return []

    results: List[Tuple[str, str]] = []
    seen_texts: List[str] = []

    for entry in raw_log.split(sep_commit):
        entry = entry.strip()
        if not entry:
            continue
        parts = entry.split(sep_field, 1)
        subject = parts[0].strip()
        body = parts[1].strip() if len(parts) > 1 else ""

        subj_note = to_plain_note(subject)
        if subj_note and not is_similar_entry(subj_note, seen_texts):
            seen_texts.append(subj_note)
            results.append((classify_bucket(subj_note), subj_note))

        # Also inspect commit body lines for multi-change summaries/bullets
        for line in body.splitlines():
            stripped = line.strip()
            if not stripped:
                continue
            # Prefer explicit bullets or concise action sentences in commit body
            is_bullet = bool(re.match(r"^([-*•]|\d+[.)])\s+", stripped))
            if not is_bullet and len(stripped) > 140:
                continue
            cleaned_line = re.sub(r"^\d+[.)]\s+", "", stripped)
            body_note = to_plain_note(cleaned_line)
            if body_note and len(body_note) >= 12 and not is_similar_entry(body_note, seen_texts):
                seen_texts.append(body_note)
                results.append((classify_bucket(body_note), body_note))

    return results


def get_diff_stats(base_ref: str) -> Tuple[List[Tuple[str, str]], Dict[str, int]]:
    """
    Returns:
      - list of (status, filepath) from `git diff --name-status`
      - dict of filepath -> total changed lines (added + deleted)
    """
    if not base_ref:
        return [], {}

    name_status_raw = run_git("diff", "--name-status", f"{base_ref}..HEAD")
    numstat_raw = run_git("diff", "--numstat", f"{base_ref}..HEAD")

    files: List[Tuple[str, str]] = []
    for line in name_status_raw.splitlines():
        parts = line.split("\t")
        if len(parts) >= 2:
            status = parts[0].strip()
            path = parts[-1].strip()
            files.append((status, path))

    line_counts: Dict[str, int] = {}
    for line in numstat_raw.splitlines():
        parts = line.split("\t")
        if len(parts) >= 3:
            try:
                added = int(parts[0]) if parts[0].isdigit() else 0
                deleted = int(parts[1]) if parts[1].isdigit() else 0
                line_counts[parts[2].strip()] = added + deleted
            except ValueError:
                pass

    return files, line_counts


def extract_added_ui_features_from_diff(base_ref: str, changed_files: List[Tuple[str, str]]) -> List[Tuple[str, str]]:
    """
    Inspects added lines in key UI/Settings/Home files between base_ref..HEAD
    to surface newly added user-facing toggles, settings options, and Home feed sections
    even when multiple prompts were squashed into a single push commit.
    """
    if not base_ref:
        return []

    interesting_paths = [
        path
        for _, path in changed_files
        if path.endswith(".kt")
        and any(
            name in path
            for name in (
                "SettingsScreen.kt",
                "SettingsTabRoute.kt",
                "HomeFeedSections.kt",
                "UserPreferences.kt",
                "BottomNavOrderManagerCard.kt",
            )
        )
    ]
    if not interesting_paths:
        return []

    diff_text = run_git("diff", "-U0", f"{base_ref}..HEAD", "--", *interesting_paths)
    if not diff_text:
        return []

    extracted: List[Tuple[str, str]] = []
    seen_lower: Set[str] = set()

    # Track removed titles/labels so simple renames aren't misreported as brand-new unrelated items
    removed_titles: Set[str] = set()
    added_lines: List[str] = []
    for line in diff_text.splitlines():
        if line.startswith("---") or line.startswith("+++"):
            continue
        if line.startswith("-"):
            for m in re.finditer(r'title\s*=\s*"([^"]{5,75})"', line):
                removed_titles.add(m.group(1).strip().lower())
        elif line.startswith("+"):
            added_lines.append(line[1:])

    added_blob = "\n".join(added_lines)

    # 1. Detect newly added Settings search entries or Settings toggle titles
    for m in re.finditer(r'SettingsSearchEntry\([^)]*title\s*=\s*"([^"]{4,75})"', added_blob):
        title = strip_emojis(m.group(1)).strip()
        low = title.lower()
        if title and low not in removed_titles and low not in seen_lower:
            seen_lower.add(low)
            if "organize" in low and "feed" in low:
                extracted.append(("Improvements", f'Renamed Home Feed settings to "{title}"'))
            elif "keyboard" in low and "search" in low:
                extracted.append(
                    ("Highlights", f'Added "{title}" option for instant keyboard focus on Search')
                )
            elif "smart queue" in low or "smart shuffle" in low:
                extracted.append(
                    ("Highlights", "Added Smart Queue (Smart Shuffle) toggle to reorder playback using listening history and genres")
                )
            elif "bottom" in low and ("bar" in low or "nav" in low):
                extracted.append(
                    ("Highlights", f'Added "{title}" customization option in Settings')
                )
            elif "api key" in low:
                extracted.append(
                    ("Highlights", f'Added "{title}" helper in Settings')
                )
            else:
                extracted.append(("Highlights", f'Added "{title}" option in Settings'))

    # 2. Detect newly added Home feed section descriptors (e.g. "Suggestions" / "New Music Mix")
    if "NEW_MUSIC_MIX" in added_blob or ("Suggestions" in added_blob and "New Music Mix" in added_blob):
        key = "suggestions_new_music_mix"
        if key not in seen_lower:
            seen_lower.add(key)
            extracted.append(
                (
                    "Highlights",
                    'Added "Suggestions" (New Music Mix) section to the Home feed with 10 tracks and one-tap refresh',
                )
            )

    # 3. Detect locked Jam icon in bottom bar reorder
    if "Why is it locked" in added_blob or "jam_locked_reason_dialog" in added_blob:
        key = "jam_locked_bottom_bar"
        if key not in seen_lower:
            seen_lower.add(key)
            extracted.append(
                (
                    "Improvements",
                    'Hid and locked the Off-Grid Jam tab in bottom bar reordering until sign-in, with a "Why is it locked?" guide',
                )
            )

    return extracted


def collect_diff_notes(base_ref: str, existing_notes: List[Tuple[str, str]]) -> List[Tuple[str, str]]:
    """
    Analyzes git diff base_ref..HEAD to capture ALL features and improvements across
    multiple prompts that may have been squashed into one push commit.
    """
    if not base_ref:
        return []

    changed_files, line_counts = get_diff_stats(base_ref)
    if not changed_files:
        return []

    existing_texts = [text for _, text in existing_notes]
    combined_existing_lower = " ".join(t.lower() for t in existing_texts)

    diff_notes: List[Tuple[str, str]] = []

    def add_if_new(bucket: str, text: str) -> None:
        nonlocal combined_existing_lower
        if not text:
            return
        if is_similar_entry(text, existing_texts):
            return
        existing_texts.append(text)
        combined_existing_lower += " " + text.lower()
        diff_notes.append((bucket, text))

    # 1. Extract concrete added Settings/Home UI features from diff hunks first (highest precision)
    for bucket, note in extract_added_ui_features_from_diff(base_ref, changed_files):
        add_if_new(bucket, note)

    # 2. Inspect newly added production Kotlin files (status == 'A')
    for status, path in changed_files:
        if not status.startswith("A"):
            continue
        if not path.startswith("app/src/main/java/") or not path.endswith(".kt"):
            continue
        basename = os.path.basename(path)
        if basename in EXACT_FILE_FEATURE_MAP:
            bucket, desc = EXACT_FILE_FEATURE_MAP[basename]
            add_if_new(bucket, desc)
            continue

        stem = basename[:-3]
        # Generic readable summary for newly added Screen/Dialog/Sheet/Engine/Card/Widget files
        suffix_map = [
            ("Dialog", "dialog"),
            ("BottomSheet", "sheet"),
            ("Sheet", "sheet"),
            ("Screen", "screen"),
            ("Engine", "engine"),
            ("Widget", "widget"),
            ("Card", "component"),
            ("Manager", "manager"),
        ]
        for suffix, label in suffix_map:
            if stem.endswith(suffix) and len(stem) > len(suffix) + 2:
                feature_name = split_camel_case(stem[: -len(suffix)])
                if feature_name:
                    add_if_new("Highlights", f"Added {feature_name} {label}")
                break

    # 3. Inspect modified production Kotlin modules with substantial changes
    for status, path in changed_files:
        if not path.startswith("app/src/main/java/") or not path.endswith(".kt"):
            continue
        changed_lines = line_counts.get(path, 0)
        if not status.startswith("A") and changed_lines < 8:
            continue

        basename = os.path.basename(path)
        if basename in EXACT_FILE_FEATURE_MAP and changed_lines >= 20:
            bucket, desc = EXACT_FILE_FEATURE_MAP[basename]
            # Only add if keywords aren't already represented
            matched_keywords = False
            for mod_key, _, _, kw_set in MODULE_CHANGE_DESCRIPTIONS:
                if mod_key in basename:
                    if any(kw in combined_existing_lower for kw in kw_set):
                        matched_keywords = True
                    break
            if not matched_keywords:
                add_if_new(bucket, desc)
            continue

        for mod_key, bucket, desc, kw_set in MODULE_CHANGE_DESCRIPTIONS:
            if mod_key in basename:
                if not any(kw in combined_existing_lower for kw in kw_set):
                    add_if_new(bucket, desc)
                break

    return diff_notes


def try_gemini_summary(
    base_ref: str,
    commit_notes: List[Tuple[str, str]],
    diff_notes: List[Tuple[str, str]],
    changed_files: List[Tuple[str, str]],
) -> Optional[str]:
    """
    Optionally uses Gemini (if GEMINI_API_KEY is configured in CI secrets) to polish
    the complete multi-change list into crisp, user-friendly categorized notes.
    Falls back cleanly to None on any missing key, network error, or timeout.
    """
    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        return None

    prod_files = [
        f"{st} {p}"
        for st, p in changed_files
        if p.startswith("app/src/main/") or p.startswith(".github/")
    ][:45]

    raw_items = [f"- [{b}] {t}" for b, t in (commit_notes + diff_notes)]
    if not raw_items and not prod_files:
        return None

    prompt = (
        "You are writing the in-app update changelog for OffGrid Music (an Android music player).\n"
        "Summarize ALL user-facing changes from the last release to this release.\n"
        "Strict rules:\n"
        "- Cover EVERY distinct feature, improvement, and bug fix below (do not drop earlier changes).\n"
        "- Plain language for end users; no emojis, no file paths, no commit hashes, no version/tag headers, no release boilerplate.\n"
        "- Group into only the sections that apply using Markdown level-3 headings: `### Highlights`, `### Improvements`, `### Fixes`.\n"
        "- Use concise bullet points starting with `- ` (max 14 bullets total).\n"
        "- Do not invent features not supported by the inputs.\n\n"
        f"Collected change items since {base_ref or 'previous release'}:\n"
        + "\n".join(raw_items)
        + "\n\nChanged files:\n"
        + "\n".join(prod_files)
    )

    url = (
        "https://generativelanguage.googleapis.com/v1beta/models/"
        f"gemini-2.5-flash:generateContent?key={api_key}"
    )
    payload = json.dumps(
        {
            "contents": [{"parts": [{"text": prompt}]}],
            "generationConfig": {"temperature": 0.2, "maxOutputTokens": 900},
        }
    ).encode("utf-8")

    req = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )

    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        candidates = data.get("candidates") or []
        if not candidates:
            return None
        parts = candidates[0].get("content", {}).get("parts") or []
        text = "\n".join(p.get("text", "") for p in parts).strip()
        text = strip_emojis(text)
        # Strip accidental code fences if returned
        text = re.sub(r"^```[a-zA-Z]*\s*", "", text)
        text = re.sub(r"\s*```$", "", text).strip()
        if "- " in text and len(text) >= 24:
            return text
    except Exception as exc:
        print(f"Optional Gemini changelog refinement skipped: {exc}", file=sys.stderr)
    return None


def format_categorized_markdown(items: List[Tuple[str, str]]) -> str:
    buckets: Dict[str, List[str]] = {
        "Highlights": [],
        "Improvements": [],
        "Fixes": [],
    }
    seen_all: List[str] = []

    for bucket, text in items:
        target = bucket if bucket in buckets else "Improvements"
        if is_similar_entry(text, seen_all):
            continue
        if len(seen_all) >= MAX_TOTAL_BULLETS:
            break
        if len(buckets[target]) >= MAX_PER_SECTION:
            continue
        seen_all.append(text)
        buckets[target].append(text)

    sections: List[str] = []
    for heading in ("Highlights", "Improvements", "Fixes"):
        entries = buckets[heading]
        if entries:
            lines = [f"### {heading}"] + [f"- {entry}" for entry in entries]
            sections.append("\n".join(lines))

    if not sections:
        return "### Improvements\n- Fixed minor bugs and improved playback stability and interface responsiveness."

    return "\n\n".join(sections)


def fetch_latest_published_release_tag() -> Tuple[Optional[Tuple[int, int, int]], str]:
    """
    Queries GitHub Releases (when GH_TOKEN/GITHUB_TOKEN is set in CI) to find the latest
    actually-published non-draft, non-prerelease semantic release.
    Using published releases as the version baseline guarantees:
      1. Failed CI pipelines (even if an older workflow pushed an orphan tag or draft)
         NEVER increment the version number.
      2. The next successful run diffs against the last PUBLISHED release so zero
         user-facing changes are omitted from the changelog.
    """
    token = (os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or "").strip()
    repo = os.environ.get("GITHUB_REPOSITORY", "").strip()
    if not token or not repo:
        return None, ""

    url = f"https://api.github.com/repos/{repo}/releases?per_page=30"
    req = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "OffGrid-Music-ReleaseNotes",
        },
        method="GET",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            releases = json.loads(resp.read().decode("utf-8"))
        if not isinstance(releases, list):
            return None, ""
        published_with_apk: List[Tuple[Tuple[int, int, int], str]] = []
        published_any: List[Tuple[Tuple[int, int, int], str]] = []
        for rel in releases:
            if not isinstance(rel, dict):
                continue
            if rel.get("draft") or rel.get("prerelease"):
                continue
            tag = (rel.get("tag_name") or "").strip()
            parsed = parse_semver(tag)
            if not parsed:
                continue
            published_any.append((parsed, tag))
            assets = rel.get("assets") or []
            has_apk = any(
                isinstance(a, dict) and str(a.get("name", "")).lower().endswith(".apk")
                for a in assets
            )
            if has_apk:
                published_with_apk.append((parsed, tag))

        chosen = published_with_apk if published_with_apk else published_any
        if chosen:
            chosen.sort(key=lambda item: item[0])
            return chosen[-1]
    except Exception as exc:
        print(f"Note: Could not query published GitHub releases ({exc}); using git tags.", file=sys.stderr)
    return None, ""


def determine_version_and_base() -> Tuple[str, str, str, str]:
    """
    Returns (next_version_str, version_tag, prev_tag, diff_base_ref).
    """
    # 1. Read floor version from version.properties (defaults to 1.0.4)
    floor_ver = (1, 0, 4)
    if os.path.exists("version.properties"):
        with open("version.properties", "r", encoding="utf-8") as fh:
            for line in fh:
                if line.strip().startswith("VERSION_NAME="):
                    parsed = parse_semver(line.split("=", 1)[1])
                    if parsed:
                        floor_ver = parsed

    # 2. Scan existing clean semantic tags (vMAJOR.MINOR.PATCH)
    raw_tags = run_git("tag", "--list").splitlines()
    semver_tags: List[Tuple[Tuple[int, int, int], str]] = []
    for tag in raw_tags:
        parsed = parse_semver(tag)
        if parsed:
            semver_tags.append((parsed, tag.strip()))

    semver_tags.sort(key=lambda item: item[0])
    highest_ver = floor_ver
    prev_tag = ""

    # Authoritative baseline in CI: highest actually-published GitHub Release (with APK).
    # This ensures failed pipelines (which never publish a release) never bump the version number.
    published_ver, published_tag = fetch_latest_published_release_tag()
    if published_ver is not None and published_ver >= floor_ver:
        highest_ver = published_ver
        prev_tag = published_tag
    elif semver_tags and semver_tags[-1][0] >= floor_ver:
        highest_ver, prev_tag = semver_tags[-1]

    head_sha = git_rev_parse("HEAD")
    diff_base_ref = ""
    if prev_tag and git_ref_exists(prev_tag) and git_rev_parse(prev_tag) != head_sha:
        diff_base_ref = prev_tag
    elif len(semver_tags) >= 2 and git_ref_exists(semver_tags[-2][1]):
        diff_base_ref = semver_tags[-2][1]
    elif git_ref_exists("latest") and git_rev_parse("latest") != head_sha:
        diff_base_ref = "latest"
    elif git_ref_exists("HEAD~1"):
        diff_base_ref = "HEAD~1"

    # 3. Determine next semantic version
    explicit = os.environ.get("INPUT_EXPLICIT_VERSION", "").strip().lstrip("vV")
    bump_type = os.environ.get("INPUT_BUMP_TYPE", "").strip().lower()
    range_commits_msg = (
        run_git("log", f"{diff_base_ref}..HEAD", "--pretty=%B")
        if diff_base_ref
        else run_git("log", "-1", "--pretty=%B")
    )
    msg_lower = range_commits_msg.lower()

    explicit_parsed = parse_semver(explicit) if explicit else None
    if explicit_parsed:
        next_ver = explicit_parsed
    else:
        major, minor, patch = highest_ver
        if bump_type == "major" or "[major]" in msg_lower or "breaking change" in msg_lower:
            next_ver = (major + 1, 0, 0)
        elif bump_type == "minor" or "[minor]" in msg_lower:
            next_ver = (major, minor + 1, 0)
        else:
            next_ver = (major, minor, patch + 1)

    next_version_str = f"{next_ver[0]}.{next_ver[1]}.{next_ver[2]}"
    version_tag = f"v{next_version_str}"
    return next_version_str, version_tag, prev_tag, diff_base_ref


def main() -> int:
    next_version_str, version_tag, prev_tag, diff_base_ref = determine_version_and_base()

    commit_notes = collect_commit_notes(diff_base_ref)
    diff_notes = collect_diff_notes(diff_base_ref, commit_notes)
    changed_files, _ = get_diff_stats(diff_base_ref)

    all_items = commit_notes + diff_notes
    gemini_markdown = try_gemini_summary(diff_base_ref, commit_notes, diff_notes, changed_files)
    final_markdown = gemini_markdown if gemini_markdown else format_categorized_markdown(all_items)

    output_path = os.environ.get("RELEASE_NOTES_OUTPUT", "plain_release_changes.md")
    with open(output_path, "w", encoding="utf-8") as fh:
        fh.write(final_markdown.strip() + "\n")

    github_env = os.environ.get("GITHUB_ENV", "").strip()
    if github_env:
        with open(github_env, "a", encoding="utf-8") as env_file:
            env_file.write(f"NEXT_VERSION={next_version_str}\n")
            env_file.write(f"VERSION_TAG={version_tag}\n")
            env_file.write(f"PREVIOUS_TAG={prev_tag}\n")

    print(
        f"Computed semantic version: {next_version_str} "
        f"(tag: {version_tag}, previous: {prev_tag or 'none'}, diff_base: {diff_base_ref or 'none'})"
    )
    print("Generated release changes:")
    print(final_markdown)
    return 0


if __name__ == "__main__":
    sys.exit(main())
