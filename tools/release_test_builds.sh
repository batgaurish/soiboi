#!/usr/bin/env bash
# Publishes the two test builds prepared on 2026-09-25, end to end:
#
#   v1.1.8-rc.1    main at b802a83: the stable-line fixes, version 1.1.8+19
#   v1.2.0-beta.1  scope-expansion at 94da507: Phases 0-1, 1.2.0-beta.1+20
#
# 1. fast-forwards origin/main to b802a83 (refuses anything but a fast-forward)
# 2. creates and pushes both tags (skips a tag that already exists)
# 3. builds a signed arm64 release APK from each tag
# 4. creates each GitHub pre-release with its APK (skips one that exists)
#
# Run it on the machine that has the release key (android/key.properties) and
# the Android SDK, from anywhere inside the repo:
#
#     tools/release_test_builds.sh            # everything
#     tools/release_test_builds.sh --dry-run  # checks only, changes nothing
#
# Both are pre-releases: the in-app updater ignores them, and neither becomes
# "latest". Install the RC first (versionCode 19), then the beta (20); going
# back from the beta to the RC needs an uninstall. Uploading a ~600 MB APK
# takes about 25 minutes each.
set -euo pipefail

# Bash reads a script as it runs it, and step 3 checks out the tags, whose
# trees don't have this file. Run from a copy so the checkout can't cut it off.
if [[ -z "${SOIBOI_RELEASE_COPY:-}" ]]; then
  copy="$(mktemp)"
  cp "$0" "$copy"
  SOIBOI_RELEASE_COPY=1 exec bash "$copy" "$@"
fi

REPO=batgaurish/soiboi
MAIN_SHA=b802a83
BETA_SHA=94da507
RC_TAG=v1.1.8-rc.1
BETA_TAG=v1.2.0-beta.1
OUT="${OUT:-$HOME/soiboi-test-builds}"
DRY=0
[[ "${1:-}" == "--dry-run" ]] && DRY=1

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

say() { printf '\n==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }
run() { if (( DRY )); then echo "(dry run) $*"; else "$@"; fi; }

# --- checks: everything that could fail halfway is checked up front --------
say "Checking"
command -v flutter >/dev/null || die "flutter not on PATH"
command -v gh >/dev/null || die "gh not installed"
gh auth status >/dev/null 2>&1 || die "gh is not signed in (gh auth login)"
[[ -f android/key.properties ]] \
  || die "android/key.properties missing: the release key is needed (never generate a new one)"
[[ -z "$(git status --porcelain -- . ':!scorecard.png')" ]] \
  || die "working tree has changes; commit or stash them first"
git remote get-url origin | grep -q "$REPO" || die "origin is not $REPO"

git fetch origin main scope-expansion --tags
git cat-file -e "$MAIN_SHA^{commit}" 2>/dev/null || die "$MAIN_SHA not found after fetch"
git cat-file -e "$BETA_SHA^{commit}" 2>/dev/null || die "$BETA_SHA not found after fetch"
git merge-base --is-ancestor "$MAIN_SHA" "$BETA_SHA" \
  || die "$MAIN_SHA is not in scope-expansion's history; something moved"
if ! git merge-base --is-ancestor origin/main "$MAIN_SHA"; then
  git merge-base --is-ancestor "$MAIN_SHA" origin/main \
    || die "origin/main has commits that are not in $MAIN_SHA; not forcing anything"
fi
grep -q '^version: 1.1.8+19$' <(git show "$MAIN_SHA:pubspec.yaml") \
  || die "$MAIN_SHA is not version 1.1.8+19"
grep -q '^version: 1.2.0-beta.1+20$' <(git show "$BETA_SHA:pubspec.yaml") \
  || die "$BETA_SHA is not version 1.2.0-beta.1+20"
echo "all checks passed"

START_REF="$(git symbolic-ref --quiet --short HEAD || git rev-parse HEAD)"
restore() { git checkout -q -- . 2>/dev/null || true; git switch -q "$START_REF" 2>/dev/null || git checkout -q "$START_REF"; }
trap restore EXIT

# --- 1. main ------------------------------------------------------------------
say "Fast-forwarding origin/main to $MAIN_SHA"
if git merge-base --is-ancestor "$MAIN_SHA" origin/main; then
  echo "origin/main already contains $MAIN_SHA"
else
  run git push origin "$MAIN_SHA:refs/heads/main"
fi

# --- 2. tags ------------------------------------------------------------------
tag() { # tag name sha message
  if git rev-parse -q --verify "refs/tags/$1" >/dev/null; then
    [[ "$(git rev-parse "$1^{commit}")" == "$(git rev-parse "$2^{commit}")" ]] \
      || die "tag $1 exists but points elsewhere"
    echo "tag $1 already exists"
  else
    run git tag -a "$1" "$2" -m "$3"
  fi
}
say "Tagging"
tag "$RC_TAG" "$MAIN_SHA" "Soiboi 1.1.8 release candidate 1 (stable fixes, for testing)"
tag "$BETA_TAG" "$BETA_SHA" "Soiboi 1.2.0 beta 1 (scope expansion Phases 0-1, for testing)"
run git push origin "$RC_TAG" "$BETA_TAG"

# --- 3. APKs ------------------------------------------------------------------
mkdir -p "$OUT"
build() { # tag
  local apk="$OUT/soiboi-android-arm64-$1.apk"
  if [[ -f "$apk" ]]; then echo "$apk already built"; return; fi
  say "Building $1"
  run git switch -q --detach "$1"
  run flutter pub get
  run tools/apply_patches.sh
  if [[ ! -d android/pip-repo ]]; then
    # Gitignored native parts of the pipeline; usually already built here.
    run env ANDROID_NDK="${ANDROID_NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}" \
      tools/build_android_pipeline.sh
  fi
  run flutter build apk --release --target-platform android-arm64
  run cp build/app/outputs/flutter-apk/app-release.apk "$apk"
  run git checkout -q -- .
}
build "$RC_TAG"
build "$BETA_TAG"

# --- 4. pre-releases ------------------------------------------------------------
release() { # tag title notes-file
  if gh release view "$1" -R "$REPO" >/dev/null 2>&1; then
    echo "release $1 already exists; uploading the APK if it is missing"
    run gh release upload "$1" "$OUT/soiboi-android-arm64-$1.apk" -R "$REPO" || true
    return
  fi
  say "Publishing pre-release $1 (the upload takes a while)"
  run gh release create "$1" "$OUT/soiboi-android-arm64-$1.apk" -R "$REPO" \
    --verify-tag --prerelease --latest=false --title "$2" --notes-file "$3"
}

RC_NOTES="$(mktemp)"; BETA_NOTES="$(mktemp)"
cat > "$RC_NOTES" <<'EOF'
Test build of the stable line. Not an official release yet.

- A download now fails, with the reason, when the Apple Music account has no active subscription. It used to show a green tick and download nothing.
- A download where every track is skipped (not streamable, no lossless version, a missing tool) now fails with the reason. If only some tracks are skipped, it finishes and the log says how many.
- Space types a space in every text field, including the link box in Downloads. It used to pause the music.
- The app reports its real version, so the update check no longer offers the release you already have.

Install this before the 1.2.0 beta: going back from the beta needs an uninstall.
EOF
cat > "$BETA_NOTES" <<'EOF'
Test build of the next version (scope expansion, Phases 0 and 1). Not an official release. Includes every fix in 1.1.8 RC 1.

- Notifications: download progress and results, with a switch and a test button in Settings.
- Failures in plain words, each with what to do about it.
- Screen readers: every button has a name, a song reads as one item, and the seek and volume sliders say their value.
- Large text: nothing overflows at 200%.
- Readable colours: text reaches 4.5:1 contrast in every theme, palette and cover colour.
- Reduce motion: follow the system, or switch it on or off in Settings.
- Keyboard: Space, arrows for seek and volume, Shift+arrows to change song, Ctrl+F or / to search, Ctrl+L for lyrics, Ctrl+D for Downloads, Esc to close, and ? for the full list. Tab moves through the sidebar, the page and the player in order, with a visible focus ring.
EOF
release "$RC_TAG" "1.1.8 RC 1" "$RC_NOTES"
release "$BETA_TAG" "1.2.0 beta 1" "$BETA_NOTES"
rm -f "$RC_NOTES" "$BETA_NOTES"

say "Done"
echo "APKs: $OUT"
echo "https://github.com/$REPO/releases/tag/$RC_TAG"
echo "https://github.com/$REPO/releases/tag/$BETA_TAG"
