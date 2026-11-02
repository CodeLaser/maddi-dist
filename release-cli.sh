#!/usr/bin/env bash
#
# Build the two self-contained maddi CLI distributions and the MCP server, and publish them as assets on maddi's
# GitHub Release. This is the "command-line tools" leg of the publishing strategy (see PUBLISHING.md, Package 1,
# item 3).
#
#   maddi        (maddi-cli:distZip) — the openjdk (Java) runner
#   maddi-kotlin (maddi-cli-kotlin:distZip) — the mixed Java+Kotlin runner; the K2 'for-ide' jars ride
#                                              along in lib-k2/, so this bundle is how Kotlin support ships
#   maddi-mcp    (maddi-mcp:distZip) — the MCP server for coding agents, with the IDE daemon in daemon/
#                (maddi-mcp:mcpb)    — the same as an MCP bundle, maddi-mcp-<version>.mcpb, for Claude Desktop
#                                      and the MCP Registry
#
# Each zip gets a <zip>.sha256 beside it. The IDE plugins download maddi-kotlin-<version>.zip from release
# v<version> (KotlinFrontEndInstaller) and refuse it without a matching checksum, so the tag must be the
# version in gradle.properties -- checked below.
#
# Each bundle is self-contained: the launcher (bin/maddi[-kotlin]) has the javac --add-exports baked in
# and every runtime jar sits in lib/. No Maven resolution is involved on the consumer side. The MCP server's
# bin/maddi-mcp starts the daemon from its own daemon/ directory.
#
# Then the MCP Registry learns of the new bundle: maddi-mcp/server.json, with the version and the bundle's checksum
# filled in, is published with mcp-publisher (github.com/modelcontextprotocol/registry), which must be logged in
# once with `mcp-publisher login github` by a member of CodeLaser. MADDI_MCP_REGISTRY=none skips that, as does a
# trial run unless MADDI_MCP_REGISTRY=publish; a failed publish warns and leaves the rest of the release standing.
#
# Then the Homebrew formulas in CodeLaser/homebrew-tap (maddi, maddi-kotlin, maddi-mcp) are pointed at the new
# zips and their checksums, committed and pushed. MADDI_HOMEBREW_TAP=none skips that; a trial run on another
# repository (MADDI_RELEASE_REPO) skips it unless MADDI_HOMEBREW_TAP names a tap.
#
# Usage:   ./release-cli.sh <tag>          e.g.  ./release-cli.sh v0.8.2
# Requires: an authenticated `gh` CLI (github.com/cli/cli) with push access to the tap, and a JDK on PATH.
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

echo "==> Building the CLI distributions and the MCP server (version from gradle.properties)"
./gradlew :maddi-cli:distZip :maddi-cli-kotlin:distZip :maddi-mcp:distZip :maddi-mcp:mcpb

# Each zip lives in its own module's distributions dir, so these globs cannot cross-match.
OPENJDK_ZIP=$(ls maddi-cli/build/distributions/maddi-*.zip)
KOTLIN_ZIP=$(ls maddi-cli-kotlin/build/distributions/maddi-kotlin-*.zip)
MCP_ZIP=$(ls maddi-mcp/build/distributions/maddi-mcp-*.zip)
MCPB="maddi-mcp/build/distributions/maddi-mcp-$VERSION.mcpb"
echo "    openjdk runner: $OPENJDK_ZIP"
echo "    kotlin  runner: $KOTLIN_ZIP"
echo "    MCP server:     $MCP_ZIP"
echo "    MCP bundle:     $MCPB"

# "<hex>  <file name>", the sha256sum format; shasum ships with macOS and perl alike
for zip in "$OPENJDK_ZIP" "$KOTLIN_ZIP" "$MCP_ZIP" "$MCPB"; do
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
TAP_DIR=$(mktemp -d)
REGISTRY_DIR=$(mktemp -d)
trap 'rm -rf "$NOTES" "$TAP_DIR" "$REGISTRY_DIR"' EXIT
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
    gh release create "$TAG" -R "$REPO" --verify-tag --title "$TAG" --notes "maddi $TAG — command-line distributions and MCP server."
fi

gh release upload "$TAG" -R "$REPO" "$OPENJDK_ZIP" "$OPENJDK_ZIP.sha256" "$KOTLIN_ZIP" "$KOTLIN_ZIP.sha256" \
    "$MCP_ZIP" "$MCP_ZIP.sha256" "$MCPB" "$MCPB.sha256" --clobber
echo "==> Both CLI zips, the MCP server's zip and bundle, and their checksums attached to release $TAG on $REPO."

if [[ "$REPO" == "CodeLaser/maddi" ]]; then REGISTRY="${MADDI_MCP_REGISTRY:-publish}"; else REGISTRY="${MADDI_MCP_REGISTRY:-none}"; fi
if [[ "$REGISTRY" == "none" ]]; then
    echo "==> MCP Registry not updated (MADDI_MCP_REGISTRY=none, or a trial run)"
elif ! command -v mcp-publisher >/dev/null; then
    echo "==> MCP Registry not updated: no mcp-publisher on PATH (brew install mcp-publisher)" >&2
else
    echo "==> Publishing maddi-mcp $VERSION on the MCP Registry"
    sed -e "s/@VERSION@/$VERSION/g" -e "s/@SHA256@/$(cut -d' ' -f1 "$MCPB.sha256")/" maddi-mcp/server.json \
        > "$REGISTRY_DIR/server.json"
    if ! (cd "$REGISTRY_DIR" && mcp-publisher publish); then
        cp "$REGISTRY_DIR/server.json" maddi-mcp/build/server.json
        echo "    the MCP Registry refused it: after mcp-publisher login github, run mcp-publisher publish" \
             "in maddi-mcp/build" >&2
    fi
fi

if [[ "$REPO" == "CodeLaser/maddi" ]]; then TAP="${MADDI_HOMEBREW_TAP:-CodeLaser/homebrew-tap}"; else TAP="${MADDI_HOMEBREW_TAP:-none}"; fi
if [[ "$TAP" == "none" ]]; then
    echo "==> Homebrew tap not updated (MADDI_HOMEBREW_TAP=none, or a trial run)"
    exit 0
fi
echo "==> Pointing the formulas in $TAP at release $TAG"
gh repo clone "$TAP" "$TAP_DIR" -- --quiet
# a formula's url and sha256 lines, each on a line of its own; Homebrew reads the version from the url
point_formula() {
    local formula="$TAP_DIR/Formula/$1.rb" zip="$2"
    local url="https://github.com/$REPO/releases/download/$TAG/$(basename "$zip")"
    local sha
    sha=$(cut -d' ' -f1 "$zip.sha256")
    sed -i.bak -E -e "s#^  url \".*\"\$#  url \"$url\"#" -e "s#^  sha256 \".*\"\$#  sha256 \"$sha\"#" "$formula"
    rm -f "$formula.bak"
    grep -q "^  url \"$url\"\$" "$formula" && grep -q "^  sha256 \"$sha\"\$" "$formula" \
        || { echo "could not point $formula at $url" >&2; exit 1; }
}
point_formula maddi "$OPENJDK_ZIP"
point_formula maddi-kotlin "$KOTLIN_ZIP"
point_formula maddi-mcp "$MCP_ZIP"
git -C "$TAP_DIR" add Formula
if git -C "$TAP_DIR" diff --cached --quiet; then
    echo "    the formulas already point at $TAG"
else
    git -C "$TAP_DIR" commit --quiet -m "maddi $VERSION"
    git -C "$TAP_DIR" push --quiet
    echo "    pushed: brew upgrade picks up $VERSION"
fi
echo "==> Done."
