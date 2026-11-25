#!/usr/bin/env python3
"""
Broadcasts an FCM HTTP v1 topic notification for a verified stable GitHub Release
of OffGrid Music, with release-level deduplication and zero external pip dependencies.
"""

import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

FCM_TOPIC = "offgrid_updates"
RECEIPT_ASSET_NAME = "fcm-notification-receipt.json"


def b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("utf-8").rstrip("=")


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


def sanitize_release_notes(raw_notes: str, max_lines: int = 16, max_chars: int = 1500) -> str:
    fallback = "• Fixed minor bugs and improved playback stability and responsiveness."
    if not raw_notes or not raw_notes.strip():
        return fallback

    prefix_rules = [
        (r"^fix(\([^)]*\))?!?:\s*", "Fixed "),
        (r"^bugfix(\([^)]*\))?!?:\s*", "Fixed "),
        (r"^hotfix(\([^)]*\))?!?:\s*", "Fixed "),
        (r"^feat(\([^)]*\))?!?:\s*", "Added "),
        (r"^perf(\([^)]*\))?!?:\s*", "Improved "),
        (r"^refactor(\([^)]*\))?!?:\s*", "Refined "),
        (r"^style(\([^)]*\))?!?:\s*", "Updated "),
        (r"^chore(\([^)]*\))?!?:\s*", "Updated "),
    ]

    known_section_headings = {
        "highlights",
        "new features",
        "features",
        "improvements",
        "enhancements",
        "fixes",
        "bug fixes",
        "what's fixed",
        "playback",
        "audio",
        "library",
        "playlists",
        "lyrics",
        "jam",
        "performance",
        "stability",
    }

    cleaned_lines = []
    seen = set()
    bullet_count = 0
    pending_heading = None

    for line in raw_notes.splitlines():
        trimmed = line.strip()
        if not trimmed or trimmed.startswith("---") or trimmed.startswith("|"):
            continue

        is_heading_line = trimmed.startswith("#")
        line_clean = re.sub(r"<[^>]*>", "", trimmed)
        line_clean = re.sub(r"^#+\s*", "", line_clean)
        line_clean = line_clean.replace("**", "").replace("__", "").replace("`", "")
        line_clean = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", line_clean)
        line_clean = strip_emojis(line_clean)
        line_clean = re.sub(r"^[-*•]\s+\[[xX ]\]\s*", "", line_clean)
        line_clean = re.sub(r"^[-*•]\s+", "", line_clean).strip()

        lower = line_clean.lower()
        if (
            not line_clean
            or lower in (
                "what's new",
                "whats new",
                "changelog",
                "release notes",
                "direct apk downloads",
                "direct download",
                "build & package details",
                "build summary",
                "upgrade & downgrade compatibility",
                "seamless upgrade & downgrade support (retains all data)",
            )
            or lower.startswith("offgrid music")
            or lower.startswith("download offgrid-music")
            or lower.startswith("download latest apk")
            or lower.startswith("sha-256")
            or lower.startswith("in-place updates")
            or lower.startswith("switch versions anytime")
            or lower.startswith("install older or newer")
            or lower.startswith("looking for an older")
            or lower.startswith("merge ")
        ):
            continue

        if is_heading_line or lower.rstrip(":") in known_section_headings:
            heading_clean = line_clean.rstrip(":").strip()
            if heading_clean and heading_clean.lower() in known_section_headings:
                if heading_clean[0].islower():
                    heading_clean = heading_clean[0].upper() + heading_clean[1:]
                pending_heading = heading_clean
                continue

        for pattern, replacement in prefix_rules:
            if re.match(pattern, line_clean, flags=re.IGNORECASE):
                rest = re.sub(pattern, "", line_clean, count=1, flags=re.IGNORECASE).strip()
                if rest and rest[:1].isupper() and not rest[:2].isupper():
                    rest = rest[:1].lower() + rest[1:]
                line_clean = (replacement + rest).strip()
                break

        if line_clean and line_clean[0].islower():
            line_clean = line_clean[0].upper() + line_clean[1:]

        key = line_clean.lower()
        if key in seen:
            continue
        seen.add(key)

        if pending_heading:
            cleaned_lines.append(f"### {pending_heading}")
            pending_heading = None

        cleaned_lines.append(f"• {line_clean}")
        bullet_count += 1
        if bullet_count >= max_lines:
            break

    result = "\n".join(cleaned_lines).strip()
    if not result:
        return fallback
    if len(result) > max_chars:
        result = result[: max_chars - 1].rstrip() + "…"
    return result


def sign_jwt_rs256(private_key_pem: str, signing_input: bytes) -> bytes:
    """Signs signing_input using OpenSSL SHA-256 RSA without requiring third-party Python packages."""
    with tempfile.NamedTemporaryFile(mode="w", suffix=".pem", delete=False) as key_file:
        key_file.write(private_key_pem)
        key_path = key_file.name

    try:
        os.chmod(key_path, 0o600)
        proc = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", key_path],
            input=signing_input,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if proc.returncode != 0:
            err_msg = proc.stderr.decode("utf-8", errors="replace").strip()
            raise RuntimeError(f"OpenSSL signing failed (exit {proc.returncode}): {err_msg}")
        return proc.stdout
    finally:
        try:
            os.remove(key_path)
        except OSError:
            pass


def generate_access_token(sa: dict) -> str:
    client_email = sa.get("client_email", "").strip()
    private_key = sa.get("private_key", "").strip()
    token_uri = sa.get("token_uri", "https://oauth2.googleapis.com/token").strip()

    if not client_email or not private_key:
        raise ValueError("Service account JSON is missing 'client_email' or 'private_key'.")

    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    payload = {
        "iss": client_email,
        "sub": client_email,
        "aud": token_uri,
        "iat": now,
        "exp": now + 3600,
        "scope": "https://www.googleapis.com/auth/firebase.messaging",
    }

    header_b64 = b64url_encode(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    payload_b64 = b64url_encode(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    signing_input = f"{header_b64}.{payload_b64}".encode("utf-8")

    signature = sign_jwt_rs256(private_key, signing_input)
    jwt_assertion = f"{header_b64}.{payload_b64}.{b64url_encode(signature)}"

    form_data = urllib.parse.urlencode(
        {
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": jwt_assertion,
        }
    ).encode("utf-8")

    req = urllib.request.Request(
        token_uri,
        data=form_data,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        resp_body = json.loads(resp.read().decode("utf-8"))
        token = resp_body.get("access_token", "").strip()
        if not token:
            raise RuntimeError("OAuth2 token response did not include an access_token.")
        return token


def fetch_github_release(tag: str) -> dict:
    delays = (3, 6, 12)
    last_err = ""
    for attempt in range(1, len(delays) + 2):
        proc = subprocess.run(
            [
                "gh",
                "release",
                "view",
                tag,
                "--json",
                "tagName,name,body,url,isDraft,isPrerelease,publishedAt,assets",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if proc.returncode == 0:
            return json.loads(proc.stdout.decode("utf-8"))
        last_err = proc.stderr.decode("utf-8", errors="replace").strip()
        if attempt <= len(delays):
            delay = delays[attempt - 1]
            print(
                f"[retry {attempt}/{len(delays)}] gh release view '{tag}' failed ({last_err}); "
                f"retrying in {delay}s...",
                file=sys.stderr,
            )
            time.sleep(delay)
    raise RuntimeError(f"Failed to query GitHub release '{tag}': {last_err}")


def upload_dedup_receipt(tag: str, message_name: str) -> None:
    receipt = {
        "tag": tag,
        "topic": FCM_TOPIC,
        "fcm_message": message_name,
        "announced_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    with tempfile.NamedTemporaryFile(
        mode="w", suffix=".json", prefix="fcm-receipt-", delete=False
    ) as tmp:
        json.dump(receipt, tmp, indent=2)
        tmp_path = tmp.name

    try:
        receipt_target = os.path.join(os.path.dirname(tmp_path), RECEIPT_ASSET_NAME)
        os.replace(tmp_path, receipt_target)
        subprocess.run(
            ["gh", "release", "upload", tag, receipt_target, "--clobber"],
            check=False,
        )
        try:
            os.remove(receipt_target)
        except OSError:
            pass
    except Exception as exc:
        print(f"Warning: Could not upload deduplication receipt asset: {exc}")


def main() -> int:
    tag = os.environ.get("TAG", "").strip()
    repo = os.environ.get("GITHUB_REPOSITORY", "Paul00755/OffGrid-Music").strip()
    sa_raw = os.environ.get("FIREBASE_SERVICE_ACCOUNT_JSON", "").strip()

    if not tag:
        print("Error: TAG environment variable is empty.")
        return 1

    if tag.lower() == "latest":
        print("Skipping FCM notification for rolling 'latest' alias tag.")
        return 0

    if not sa_raw:
        print("Info: FIREBASE_SERVICE_ACCOUNT_JSON secret is not configured in GitHub Secrets.")
        print("Skipping FCM broadcast; in-app GitHub release detection remains active.")
        return 0

    try:
        sa = json.loads(sa_raw)
    except json.JSONDecodeError as exc:
        print(f"Error: FIREBASE_SERVICE_ACCOUNT_JSON is not valid JSON ({exc}).")
        return 1

    project_id = sa.get("project_id", "").strip()
    if not project_id:
        print("Error: 'project_id' is missing from FIREBASE_SERVICE_ACCOUNT_JSON.")
        return 1

    release = fetch_github_release(tag)
    if release.get("isDraft") or release.get("isPrerelease"):
        print(f"Skipping FCM broadcast for '{tag}' because draft={release.get('isDraft')} prerelease={release.get('isPrerelease')}.")
        return 0

    assets = release.get("assets") or []
    asset_names = [a.get("name", "") for a in assets if isinstance(a, dict)]

    if RECEIPT_ASSET_NAME in asset_names:
        print(f"Release '{tag}' already has '{RECEIPT_ASSET_NAME}'. Skipping duplicate FCM broadcast.")
        return 0

    apk_assets = [a for a in assets if isinstance(a, dict) and a.get("name", "").lower().endswith(".apk")]
    if not apk_assets:
        print(f"Error: Release '{tag}' does not have an attached .apk asset yet. Aborting notification.")
        return 1

    # Always point subscribers to the canonical latest OffGrid-Music.apk direct download URL
    apk_download_url = f"https://github.com/{repo}/releases/latest/download/OffGrid-Music.apk"
    release_name = (release.get("name") or "").strip() or f"OffGrid Music {tag}"
    release_url = (release.get("url") or "").strip() or f"https://github.com/{repo}/releases/tag/{tag}"
    raw_body = (release.get("body") or "").strip()
    clean_notes = sanitize_release_notes(raw_body)

    print(f"Generating OAuth2 token for Firebase project '{project_id}'...")
    access_token = generate_access_token(sa)

    clean_version = tag.split("-build.")[0].strip()
    if clean_version and not clean_version.lower().startswith("v"):
        clean_version = f"v{clean_version}"

    fcm_endpoint = f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
    # Data-only high-priority payload ensures OffGridFirebaseMessagingService.onMessageReceived()
    # ALWAYS executes (foreground, background, or killed), so the app compares versions locally
    # and renders the clean "Update available" / "vX.Y.Z" notification with tap-to-update dialog.
    fcm_payload = {
        "message": {
            "topic": FCM_TOPIC,
            "data": {
                "type": "app_update",
                "version": clean_version or tag,
                "release_tag": tag,
                "title": "Update available",
                "body": clean_version or tag,
                "release_name": release_name,
                "release_notes": clean_notes,
                "release_url": release_url,
                "url": release_url,
                "apk_url": apk_download_url,
            },
            "android": {
                "priority": "high",
                "ttl": "604800s",
            },
        }
    }

    req = urllib.request.Request(
        fcm_endpoint,
        data=json.dumps(fcm_payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {access_token}",
            "Content-Type": "application/json; charset=utf-8",
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            status = resp.status
            resp_data = json.loads(resp.read().decode("utf-8"))
            message_name = resp_data.get("name", "")
            print(f"FCM HTTP v1 broadcast succeeded (HTTP {status}): {message_name}")
            upload_dedup_receipt(tag, message_name)
            return 0
    except urllib.error.HTTPError as http_err:
        err_body = http_err.read().decode("utf-8", errors="replace")
        print(f"Error: FCM HTTP v1 request failed with HTTP {http_err.code}: {err_body}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
