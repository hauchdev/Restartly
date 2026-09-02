#!/usr/bin/env bash
#
# release.sh — prepare a new Restartly release.
#
# Bumps the project version, adds the matching CHANGELOG entry and prints the
# remaining manual steps (commit, PR, tag). The actual GitHub Release is
# created by .github/workflows/release.yml when the version tag is pushed.
#
# Usage:
#   ./scripts/release.sh 1.1.0
#
set -euo pipefail

cd "$(dirname "$0")/.."

if [ $# -ne 1 ]; then
    echo "usage: $0 <new-version>" >&2
    echo "  e.g. $0 1.1.0" >&2
    exit 1
fi

NEW_VERSION="$1"

if ! echo "$NEW_VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "error: '$NEW_VERSION' is not a valid semantic version (expected X.Y.Z)" >&2
    exit 1
fi

# Guard against running in a dirty working tree.
if [ -n "$(git status --porcelain)" ]; then
    echo "error: working tree is not clean; commit or stash your changes first" >&2
    exit 1
fi

# ---- gradle.properties ----------------------------------------------------
PROPS_FILE="gradle.properties"
CURRENT_VERSION="$(sed -n 's/^version=\(.*\)$/\1/p' "$PROPS_FILE")"
if [ -z "$CURRENT_VERSION" ]; then
    echo "error: could not find 'version=' in $PROPS_FILE" >&2
    exit 1
fi

echo "Bumping version: $CURRENT_VERSION -> $NEW_VERSION"

# Only replace the standalone top-level version key (the file also contains
# keys like minecraft_version / forge_version that must not change).
sed -i "0,/^version=${CURRENT_VERSION//./\\.}$/s//version=${NEW_VERSION}/" "$PROPS_FILE"

# ---- CHANGELOG.md ---------------------------------------------------------
CHANGELOG_FILE="CHANGELOG.md"
if [ ! -f "$CHANGELOG_FILE" ]; then
    echo "error: $CHANGELOG_FILE not found; skipping changelog entry" >&2
    exit 1
fi

TODAY="$(date +%F)"
# Insert the new version section right after the "## [Unreleased]" heading.
# When the file does not follow the Keep a Changelog convention yet, add the
# Unreleased heading first so future releases keep stacking correctly.
if ! grep -q '^## \[Unreleased\]' "$CHANGELOG_FILE"; then
    # Add the heading above the first "## [X.Y.Z]" release entry. Blank lines
    # that separated the old preamble are preserved as a single separator.
    awk '
        /^## \[/ && !done {
            print "## [Unreleased]"
            print ""
            done = 1
        }
        { print }
    ' "$CHANGELOG_FILE" > "$CHANGELOG_FILE.tmp"
    mv "$CHANGELOG_FILE.tmp" "$CHANGELOG_FILE"
fi

awk -v version="$NEW_VERSION" -v today="$TODAY" '
    BEGIN { seen = 0; buffered = "" }
    /^## \[Unreleased\]/ {
        print
        seen = 1
        next
    }
    seen && /^## \[/ {
        # First real release heading: emit the new version section here,
        # then anything that was still under [Unreleased] (comments, etc.).
        # The buffered lines already carry a trailing blank line, so keep the
        # spacing between the new section and that content tidy.
        print ""
        print "## [" version "] - " today
        print ""
        print "### Added"
        print "- "
        if (buffered != "") {
            printf "%s", buffered
        }
        seen = 0
    }
    seen { buffered = buffered $0 "\n"; next }
    { print }
' "$CHANGELOG_FILE" > "$CHANGELOG_FILE.tmp"
    mv "$CHANGELOG_FILE.tmp" "$CHANGELOG_FILE"

echo
echo "Done. Changes prepared:"
echo "  - $PROPS_FILE    version=$NEW_VERSION"
echo "  - $CHANGELOG_FILE   new section for $NEW_VERSION"
echo
echo "Next steps:"
echo "  1. Edit the new section in $CHANGELOG_FILE and describe what changed."
echo "  2. git add $PROPS_FILE $CHANGELOG_FILE"
echo "  3. Commit and push (through a pull request when the branch is protected)."
echo "  4. Once merged to the default branch, create the release tag:"
echo "       git tag v$NEW_VERSION"
echo "       git push origin v$NEW_VERSION"
echo "     The release workflow then builds the jars and publishes the"
echo "     GitHub Release with the changelog notes."
