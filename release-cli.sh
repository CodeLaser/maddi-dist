#!/usr/bin/env bash
#
# Build the two self-contained maddi CLI distributions and publish them as assets on maddi's GitHub Release.
# This is the "command-line tools" leg of the publishing strategy (see PUBLISHING.md, Package 1, item 3).
#
#   maddi        (maddi-cli:distZip) — the openjdk (Java) runner
#   maddi-kotlin (maddi-cli-kotlin:distZip) — the mixed Java+Kotlin runner; the K2 'for-ide' jars ride
#                                              along in lib-k2/, so this bundle is how Kotlin support ships
#
# Each zip gets a <zip>.sha256 beside it. The IDE plugins download maddi-kotlin-<version>.zip from release
# v<version> (KotlinFrontEndInstaller) and refuse it without a matching checksum, so the tag must be the
# version in gradle.properties -- checked below.
#
# Each bundle is self-contained: the launcher (bin/maddi[-kotlin]) has the javac --add-exports baked in
# and every runtime jar sits in lib/. No Maven resolution is involved on the consumer side.
#
# Usage:   ./release-cli.sh <tag>          e.g.  ./release-cli.sh v0.8.2
# Requires: an authenticated `gh` CLI (github.com/cli/cli) and a JDK on PATH.
#
set -euo pipefail

TAG="${1:-}"
if [[ -z "$TAG" ]]; then
    echo "usage: $0 <tag>   (e.g. v0.8.2)" >&2
    exit 2
fi

cd "$(dirname "$0")"

VERSION=$(sed -n 's/^version=//p' gradle.properties)
if [[ "$TAG" != "v$VERSION" ]]; then
    echo "tag $TAG does not match version=$VERSION in gradle.properties: the IDE plugins look for release v$VERSION" >&2
    exit 2
fi

echo "==> Building the CLI distributions (version from gradle.properties)"
./gradlew :maddi-cli:distZip :maddi-cli-kotlin:distZip

# Each zip lives in its own module's distributions dir, so these globs cannot cross-match.
OPENJDK_ZIP=$(ls maddi-cli/build/distributions/maddi-*.zip)
KOTLIN_ZIP=$(ls maddi-cli-kotlin/build/distributions/maddi-kotlin-*.zip)
echo "    openjdk runner: $OPENJDK_ZIP"
echo "    kotlin  runner: $KOTLIN_ZIP"

# "<hex>  <file name>", the sha256sum format; shasum ships with macOS and perl alike
for zip in "$OPENJDK_ZIP" "$KOTLIN_ZIP"; do
    (cd "$(dirname "$zip")" && shasum -a 256 "$(basename "$zip")" > "$(basename "$zip").sha256")
done

# The release lives on maddi, not on this repository: the IDE plugins download from
# github.com/CodeLaser/maddi/releases (KotlinFrontEndInstaller.RELEASE_BASE) and both READMEs link there.
# Since the split this script runs from maddi-dist, where a bare `gh release` would put it on maddi-dist.
# MADDI_RELEASE_REPO points it elsewhere for a trial run.
REPO="${MADDI_RELEASE_REPO:-CodeLaser/maddi}"

# Release notes: maddi's docs/project/release-notes-<version>.md at the maddi tag, where <version> is the
# tag without its leading "v". maddi sits beside this repository (release-maddi lays them out as siblings,
# and tags maddi first). The notes link relative to docs/project/; on the release page those links point
# into maddi at the tag.
MADDI_DIR="${MADDI_DIR:-../maddi}"
NOTES_PATH="docs/project/release-notes-${TAG#v}.md"
NOTES=$(mktemp)
trap 'rm -f "$NOTES"' EXIT
BLOB="https://github.com/CodeLaser/maddi/blob/$TAG"
if git -C "$MADDI_DIR" show "$TAG:$NOTES_PATH" 2>/dev/null \
        | sed -E -e "s#\]\(\.\./\.\./#]($BLOB/#g" \
                 -e "s#\]\(\.\./#]($BLOB/docs/#g" \
                 -e "s#\]\(([^)/:#][^):]*)\)#]($BLOB/docs/project/\1)#g" > "$NOTES" \
        && [[ -s "$NOTES" ]]; then
    HAVE_NOTES=1
else
    HAVE_NOTES=0
fi

# --verify-tag: the tag must already be on maddi; a missing one would otherwise be created at its default branch
if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
    echo "==> Release $TAG already exists on $REPO; uploading assets (--clobber overwrites same-named assets)"
elif [[ $HAVE_NOTES == 1 ]]; then
    echo "==> Creating release $TAG on $REPO with notes from maddi $TAG:$NOTES_PATH"
    gh release create "$TAG" -R "$REPO" --verify-tag --title "maddi $TAG" --notes-file "$NOTES"
else
    echo "==> Creating release $TAG on $REPO (no $NOTES_PATH in $MADDI_DIR at $TAG; using a one-line note)"
    gh release create "$TAG" -R "$REPO" --verify-tag --title "$TAG" --notes "maddi $TAG — command-line distributions."
fi

gh release upload "$TAG" -R "$REPO" "$OPENJDK_ZIP" "$OPENJDK_ZIP.sha256" "$KOTLIN_ZIP" "$KOTLIN_ZIP.sha256" --clobber
echo "==> Done. Both CLI zips and their checksums attached to release $TAG on $REPO."
