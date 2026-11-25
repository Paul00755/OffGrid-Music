#!/usr/bin/env python3
"""
Resilient, idempotent GitHub Release publisher for OffGrid Music.

Prevents GitHub Releases API HTTP 500 errors (especially on combined
draft->publish + make_latest PATCH requests) by:
  1. Never prematurely force-pushing the semantic git tag before the release is created.
  2. Reusing any existing draft/published release for the tag (no duplicate drafts).
  3. Uploading only the canonical OffGrid-Music.apk with retry + stale asset cleanup.
  4. Publishing in two safe steps (draft=false first, then make_latest=true) with
     exponential backoff retries on HTTP 5xx / 429 / transient network failures.
  5. Cleaning up orphaned unpublished bot drafts from previously failed CI runs.
  6. Updating the rolling 'latest' mirror channel as a best-effort step so
     /releases/download/latest/OffGrid-Music.apk stays in sync without risking
     the primary semantic release.
"""

import http.client
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional, Tuple

RETRYABLE_HTTP_CODES = {408, 429, 500, 502, 503, 504}
DEFAULT_RETRY_DELAYS = (3, 6, 12, 20)
CANONICAL_APK_NAME = "OffGrid-Music.apk"


class GitHubApiError(RuntimeError):
    def __init__(self, status: int, url: str, body: str) -> None:
        super().__init__(f"HTTP {status} ({url}): {body}")
        self.status = status
        self.url = url
        self.body = body


def api_request(
    method: str,
    url: str,
    token: str,
    payload: Optional[Dict[str, Any]] = None,
    raw_bytes: Optional[bytes] = None,
    content_type: str = "application/json",
    timeout: int = 60,
    retry_delays: Tuple[int, ...] = DEFAULT_RETRY_DELAYS,
) -> Any:
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "OffGrid-Music-CI-Publisher",
    }
    data: Optional[bytes] = None
    if raw_bytes is not None:
        data = raw_bytes
        headers["Content-Type"] = content_type
        headers["Content-Length"] = str(len(raw_bytes))
    elif payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"

    max_attempts = len(retry_delays) + 1
    last_error: Optional[Exception] = None

    for attempt in range(1, max_attempts + 1):
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                body_bytes = resp.read()
                if not body_bytes:
                    return None
                return json.loads(body_bytes.decode("utf-8"))
        except urllib.error.HTTPError as exc:
            err_body = exc.read().decode("utf-8", errors="replace").strip()
            last_error = GitHubApiError(exc.code, url, err_body)
            if exc.code in RETRYABLE_HTTP_CODES and attempt < max_attempts:
                delay = retry_delays[attempt - 1]
                print(
                    f"[retry {attempt}/{max_attempts - 1}] {method} {url} returned HTTP {exc.code}; "
                    f"retrying in {delay}s...",
                    file=sys.stderr,
                )
                time.sleep(delay)
                continue
            raise last_error
        except (
            urllib.error.URLError,
            TimeoutError,
            ConnectionResetError,
            http.client.HTTPException,
            OSError,
        ) as exc:
            last_error = exc
            if attempt < max_attempts:
                delay = retry_delays[attempt - 1]
                print(
                    f"[retry {attempt}/{max_attempts - 1}] {method} {url} failed ({exc}); "
                    f"retrying in {delay}s...",
                    file=sys.stderr,
                )
                time.sleep(delay)
                continue
            raise RuntimeError(f"{method} {url} failed after {max_attempts} attempts: {exc}") from exc

    if last_error is not None:
        raise last_error
    return None


def list_recent_releases(repo: str, token: str) -> List[Dict[str, Any]]:
    url = f"https://api.github.com/repos/{repo}/releases?per_page=30"
    result = api_request("GET", url, token)
    return result if isinstance(result, list) else []


def find_release_by_tag(releases: List[Dict[str, Any]], tag: str) -> Optional[Dict[str, Any]]:
    # Prefer a published release if multiple somehow exist, else reuse existing draft
    matches = [r for r in releases if isinstance(r, dict) and r.get("tag_name") == tag]
    if not matches:
        return None
    published = [r for r in matches if not r.get("draft")]
    return published[0] if published else matches[0]


def fetch_release_by_id(repo: str, token: str, release_id: int) -> Dict[str, Any]:
    url = f"https://api.github.com/repos/{repo}/releases/{release_id}"
    return api_request("GET", url, token)


def delete_asset_if_present(
    repo: str, token: str, release: Dict[str, Any], asset_name: str
) -> None:
    assets = release.get("assets") or []
    for asset in assets:
        if isinstance(asset, dict) and asset.get("name") == asset_name:
            asset_id = asset.get("id")
            if asset_id:
                del_url = f"https://api.github.com/repos/{repo}/releases/assets/{asset_id}"
                try:
                    api_request("DELETE", del_url, token)
                    print(f"Deleted existing asset '{asset_name}' (id={asset_id}) before re-upload.")
                except GitHubApiError as exc:
                    if exc.status != 404:
                        raise


def upload_apk_asset(
    repo: str, token: str, release_id: int, apk_path: str, asset_name: str = CANONICAL_APK_NAME
) -> Dict[str, Any]:
    with open(apk_path, "rb") as fh:
        apk_bytes = fh.read()

    encoded_name = urllib.parse.quote(asset_name, safe="")
    upload_url = (
        f"https://uploads.github.com/repos/{repo}/releases/{release_id}/assets?name={encoded_name}"
    )

    max_attempts = len(DEFAULT_RETRY_DELAYS) + 1
    for attempt in range(1, max_attempts + 1):
        # Always clear any pre-existing or partially uploaded asset with the same name
        latest_state = fetch_release_by_id(repo, token, release_id)
        delete_asset_if_present(repo, token, latest_state, asset_name)
        try:
            uploaded = api_request(
                "POST",
                upload_url,
                token,
                raw_bytes=apk_bytes,
                content_type="application/vnd.android.package-archive",
                timeout=120,
                retry_delays=(),  # Outer loop handles cleanup + retry so partial assets are removed
            )
            if isinstance(uploaded, dict) and uploaded.get("state") in (None, "uploaded"):
                print(
                    f"Uploaded asset '{asset_name}' ({len(apk_bytes)} bytes) to release {release_id}."
                )
                return uploaded
            raise RuntimeError(f"Asset upload returned unexpected state: {uploaded}")
        except Exception as exc:
            if attempt < max_attempts:
                delay = DEFAULT_RETRY_DELAYS[attempt - 1]
                print(
                    f"[upload retry {attempt}/{max_attempts - 1}] Asset upload failed ({exc}); "
                    f"retrying in {delay}s...",
                    file=sys.stderr,
                )
                time.sleep(delay)
                continue
            raise

    raise RuntimeError(f"Failed to upload '{asset_name}' to release {release_id}")


def delete_orphan_remote_tag_if_present(repo: str, token: str, tag: str) -> None:
    """Removes a stale git tag left behind by an earlier failed CI run so GitHub attaches
    the newly published release cleanly to the current successful commit SHA."""
    encoded_tag = urllib.parse.quote(tag, safe="")
    ref_url = f"https://api.github.com/repos/{repo}/git/refs/tags/{encoded_tag}"
    try:
        api_request("DELETE", ref_url, token, retry_delays=(2, 4))
        print(f"Cleared stale orphan git tag '{tag}' before publishing release.")
    except GitHubApiError as exc:
        if exc.status in (404, 422):
            return
        print(
            f"Warning: Could not clear pre-existing tag '{tag}' ({exc}); continuing.",
            file=sys.stderr,
        )


def ensure_release_published(
    repo: str,
    token: str,
    tag: str,
    commit_sha: str,
    title: str,
    body: str,
    apk_path: str,
    mark_as_latest: bool,
) -> Dict[str, Any]:
    releases = list_recent_releases(repo, token)
    existing = find_release_by_tag(releases, tag)

    # If this semantic tag has no published release yet, clear any orphan git tag from an
    # earlier failed CI run so GitHub binds the published release to the current commit_sha.
    if tag != "latest" and (existing is None or existing.get("draft")):
        delete_orphan_remote_tag_if_present(repo, token, tag)

    if existing is not None:
        release_id = int(existing["id"])
        print(f"Updating existing release for '{tag}' (id={release_id}, draft={existing.get('draft')})...")
        patch_url = f"https://api.github.com/repos/{repo}/releases/{release_id}"
        release = api_request(
            "PATCH",
            patch_url,
            token,
            payload={
                "tag_name": tag,
                "target_commitish": commit_sha,
                "name": title,
                "body": body,
                "prerelease": False,
            },
        )
    else:
        print(f"Creating draft release for '{tag}' at {commit_sha[:7]}...")
        create_url = f"https://api.github.com/repos/{repo}/releases"
        release = api_request(
            "POST",
            create_url,
            token,
            payload={
                "tag_name": tag,
                "target_commitish": commit_sha,
                "name": title,
                "body": body,
                "draft": True,
                "prerelease": False,
            },
        )
        release_id = int(release["id"])

    upload_apk_asset(repo, token, release_id, apk_path, CANONICAL_APK_NAME)

    patch_url = f"https://api.github.com/repos/{repo}/releases/{release_id}"

    # Step 1: Publish the release (draft=false) WITHOUT combining make_latest in the same PATCH,
    # avoiding GitHub's intermittent HTTP 500 on combined draft-publish + make_latest transitions.
    if release.get("draft", True):
        print(f"Publishing release '{tag}' (id={release_id})...")
        release = api_request(
            "PATCH",
            patch_url,
            token,
            payload={
                "draft": False,
                "prerelease": False,
            },
        )

    # Step 2: Set make_latest explicitly after publish settles.
    desired_latest = "true" if mark_as_latest else "false"
    time.sleep(2)
    try:
        release = api_request(
            "PATCH",
            patch_url,
            token,
            payload={"make_latest": desired_latest},
        )
    except GitHubApiError as exc:
        if mark_as_latest and exc.status in RETRYABLE_HTTP_CODES:
            # Verify whether GitHub already marked this release as latest automatically on publish
            try:
                latest_rel = api_request(
                    "GET", f"https://api.github.com/repos/{repo}/releases/latest", token
                )
                if isinstance(latest_rel, dict) and latest_rel.get("tag_name") == tag:
                    print(f"Confirmed '{tag}' is already the official Latest release.")
                    return latest_rel
            except Exception:
                pass
        if not mark_as_latest:
            print(
                f"Warning: Could not set make_latest=false on '{tag}' ({exc}); continuing.",
                file=sys.stderr,
            )
            return release
        raise

    return release


def cleanup_orphaned_bot_drafts(repo: str, token: str, current_tag: str) -> None:
    """Deletes stale unpublished draft releases left behind by earlier crashed CI bot runs."""
    try:
        releases = list_recent_releases(repo, token)
    except Exception as exc:
        print(f"Warning: Skipping orphan draft cleanup ({exc}).", file=sys.stderr)
        return

    for rel in releases:
        if not isinstance(rel, dict):
            continue
        if not rel.get("draft"):
            continue
        tag_name = (rel.get("tag_name") or "").strip()
        author_login = ((rel.get("author") or {}).get("login") or "").strip()
        if tag_name == current_tag or author_login != "github-actions[bot]":
            continue
        rel_id = rel.get("id")
        if rel_id:
            try:
                api_request("DELETE", f"https://api.github.com/repos/{repo}/releases/{rel_id}", token)
                print(f"Cleaned up orphaned CI draft release '{tag_name}' (id={rel_id}).")
            except Exception as exc:
                print(
                    f"Warning: Could not delete orphaned draft '{tag_name}' (id={rel_id}): {exc}",
                    file=sys.stderr,
                )


def update_git_tag(tag: str, commit_sha: str) -> None:
    subprocess.run(["git", "tag", "-f", tag, commit_sha], check=False)
    subprocess.run(["git", "push", "-f", "origin", tag], check=False)


def main() -> int:
    token = (os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN") or "").strip()
    repo = os.environ.get("GITHUB_REPOSITORY", "").strip()
    commit_sha = os.environ.get("GITHUB_SHA", "").strip()
    version_tag = os.environ.get("VERSION_TAG", "").strip()
    apk_path = os.environ.get("APK_PATH", "app/build/outputs/apk/release/OffGrid-Music.apk").strip()
    versioned_notes_file = os.environ.get("VERSIONED_NOTES_FILE", "release_notes_versioned.md").strip()
    latest_notes_file = os.environ.get("LATEST_NOTES_FILE", "release_notes_latest.md").strip()

    if not token or not repo or not commit_sha or not version_tag:
        print(
            "Error: Missing required environment variables "
            "(GITHUB_TOKEN, GITHUB_REPOSITORY, GITHUB_SHA, VERSION_TAG).",
            file=sys.stderr,
        )
        return 1

    if not os.path.isfile(apk_path):
        print(f"Error: APK file not found at '{apk_path}'.", file=sys.stderr)
        return 1

    with open(versioned_notes_file, "r", encoding="utf-8") as fh:
        versioned_notes = fh.read()
    with open(latest_notes_file, "r", encoding="utf-8") as fh:
        latest_notes = fh.read()

    # 1. Publish the primary semantic versioned release (e.g. v1.0.13) and mark as Latest
    primary = ensure_release_published(
        repo=repo,
        token=token,
        tag=version_tag,
        commit_sha=commit_sha,
        title=f"OffGrid Music {version_tag}",
        body=versioned_notes,
        apk_path=apk_path,
        mark_as_latest=True,
    )
    primary_id = int(primary["id"])
    print(f"Successfully published primary release '{version_tag}' (id={primary_id}).")

    # 2. Best-effort update of the rolling 'latest' mirror channel so legacy mirror links stay fresh
    try:
        update_git_tag("latest", commit_sha)
        time.sleep(2)
        ensure_release_published(
            repo=repo,
            token=token,
            tag="latest",
            commit_sha=commit_sha,
            title=f"OffGrid Music — Latest Channel ({version_tag})",
            body=latest_notes,
            apk_path=apk_path,
            mark_as_latest=False,
        )
        print(f"Updated rolling 'latest' mirror channel to {version_tag}.")
    except Exception as exc:
        print(
            f"Warning: Rolling 'latest' mirror update encountered an issue ({exc}). "
            f"Primary release '{version_tag}' is live and /releases/latest/download/{CANONICAL_APK_NAME} works.",
            file=sys.stderr,
        )

    # 3. Verify primary semantic release still holds the official GitHub 'Latest' badge
    try:
        latest_rel = api_request(
            "GET", f"https://api.github.com/repos/{repo}/releases/latest", token
        )
        if not isinstance(latest_rel, dict) or latest_rel.get("tag_name") != version_tag:
            api_request(
                "PATCH",
                f"https://api.github.com/repos/{repo}/releases/{primary_id}",
                token,
                payload={"make_latest": "true"},
            )
    except Exception as exc:
        print(f"Warning: Final latest-badge verification skipped ({exc}).", file=sys.stderr)

    # 4. Clean up any orphaned bot draft releases from earlier failed CI runs
    cleanup_orphaned_bot_drafts(repo, token, current_tag=version_tag)
    return 0


if __name__ == "__main__":
    sys.exit(main())
