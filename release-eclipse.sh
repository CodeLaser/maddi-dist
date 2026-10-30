#!/usr/bin/env bash
#
# Build the Eclipse plugin's update site and publish it, the Eclipse leg of the IDE plugins (PUBLISHING.md,
# Package 2):
#
#   1. the p2 repository, on GitHub Pages: <site>/eclipse/<version>/, plus a composite at <site>/eclipse/ that lists
#      every version. The composite URL is the one users add in Eclipse and the one the Eclipse Marketplace listing
#      names; it never changes, and a new release only adds a child.
#   2. the same repository as a zip, maddi-eclipse-<version>.zip, on maddi's GitHub release beside the CLI zips, for
#      offline installs (Install New Software > Add > Archive).
#   3. the IDE user guide (docs/ide-user-guide.md) of this release, as the site's front page <site>/index.html: the
#      link the marketplace listings and the plugins name. Rendered by GitHub's Markdown API.
#
# Usage:     ./release-eclipse.sh <tag>          e.g.  ./release-eclipse.sh v0.9.6
# Requires:  an authenticated `gh`, git push rights on the site repository, a JDK on PATH, and Maven 3.9 (Maven
#            3.10 breaks Tycho 5.0.3; MVN=/path/to/mvn points at another one). The release <tag> must already exist
#            on maddi (release-cli.sh creates it).
# Optional:  MADDI_ECLIPSE_SITE_REPO (default CodeLaser/maddi-dist) is the repository whose gh-pages branch serves the
#            site; MADDI_RELEASE_REPO (default CodeLaser/maddi) the one holding the release.
#            DRY_RUN=1 builds and prepares the gh-pages commit in a temporary directory, but pushes and uploads
#            nothing.
#
# First time only: enable GitHub Pages for the site repository, serving the gh-pages branch from its root
# (Settings > Pages). The site is then https://<owner>.github.io/<repo>/eclipse/.
#
set -euo pipefail

TAG="${1:-}"
if [[ -z "$TAG" ]]; then
    echo "usage: $0 <tag>   (e.g. v0.9.6)" >&2
    exit 2
fi
cd "$(dirname "$0")"
ROOT=$(pwd)

VERSION=$(sed -n 's/^version=//p' gradle.properties)
if [[ "$TAG" != "v$VERSION" ]]; then
    echo "tag $TAG does not match version=$VERSION in gradle.properties" >&2
    exit 2
fi
POM_VERSION=$(sed -n 's:^    <version>\(.*\)</version>:\1:p' maddi-eclipse/pom.xml | head -1)
if [[ "$POM_VERSION" != "$VERSION-SNAPSHOT" ]]; then
    echo "maddi-eclipse/pom.xml is at $POM_VERSION, expected $VERSION-SNAPSHOT: move the Eclipse reactor's poms," \
         "MANIFEST.MFs and feature.xml to $VERSION first" >&2
    exit 2
fi
CLIENT_VERSION=$(sed -n 's:.*<maddi.client.version>\(.*\)</maddi.client.version>.*:\1:p' \
    maddi-eclipse/io.codelaser.maddi.eclipse/pom.xml)
if [[ "$CLIENT_VERSION" != "$VERSION" ]]; then
    echo "the Eclipse bundle embeds maddi-ide-client $CLIENT_VERSION, expected $VERSION (maddi.client.version)" >&2
    exit 2
fi

MVN="${MVN:-mvn}"
MAVEN_VERSION=$("$MVN" -v | sed -n 's/^Apache Maven \([0-9.]*\).*/\1/p')
case "$MAVEN_VERSION" in
    3.9.*) ;;
    *) echo "Maven $MAVEN_VERSION: Tycho 5.0.3 needs Maven 3.9 (3.10 fails with 'Invalid Collect Request')." \
            "Point MVN at a 3.9 installation." >&2
       exit 2 ;;
esac

SITE_REPO="${MADDI_ECLIPSE_SITE_REPO:-CodeLaser/maddi-dist}"
RELEASE_REPO="${MADDI_RELEASE_REPO:-CodeLaser/maddi}"
if [[ "${DRY_RUN:-0}" != 1 ]] && ! gh release view "$TAG" -R "$RELEASE_REPO" >/dev/null 2>&1; then
    echo "no release $TAG on $RELEASE_REPO: run release-cli.sh $TAG first (the IntelliJ plugin downloads its" \
         "Kotlin front end from that release, and this script attaches the site zip to it)" >&2
    exit 2
fi

echo "==> Building the daemon distribution and the client the bundle embeds"
./gradlew :maddi-ide-client:jar :maddi-ide-daemon:installDist :maddi-ide-daemon:checkThirdPartyNotices

echo "==> Building the Eclipse reactor (bundle, tests, feature, p2 repository)"
(cd maddi-eclipse && "$MVN" -q clean verify)
REPOSITORY="$ROOT/maddi-eclipse/io.codelaser.maddi.eclipse.repository/target/repository"
SITE_ZIP_BUILT=$(ls maddi-eclipse/io.codelaser.maddi.eclipse.repository/target/io.codelaser.maddi.eclipse.repository-*.zip)
SITE_ZIP="$ROOT/maddi-eclipse/io.codelaser.maddi.eclipse.repository/target/maddi-eclipse-$VERSION.zip"
cp "$SITE_ZIP_BUILT" "$SITE_ZIP"
(cd "$(dirname "$SITE_ZIP")" && shasum -a 256 "$(basename "$SITE_ZIP")" > "$(basename "$SITE_ZIP").sha256")

echo "==> Preparing the gh-pages commit of $SITE_REPO"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
if git ls-remote --exit-code --heads "https://github.com/$SITE_REPO.git" gh-pages >/dev/null 2>&1; then
    git clone --quiet --depth 1 --branch gh-pages "https://github.com/$SITE_REPO.git" "$WORK/site"
else
    # first publication: a branch of its own, holding the site and nothing of the repository's code
    git init --quiet "$WORK/site"
    git -C "$WORK/site" remote add origin "https://github.com/$SITE_REPO.git"
    git -C "$WORK/site" checkout --quiet --orphan gh-pages
    touch "$WORK/site/.nojekyll"
fi
SITE="$WORK/site/eclipse"
if [[ -d "$SITE/$VERSION" ]]; then
    echo "the site already has eclipse/$VERSION: Eclipse caches a repository by its URL, so a version is never" \
         "republished. Bump the version instead." >&2
    exit 2
fi
mkdir -p "$SITE/$VERSION"
cp -R "$REPOSITORY"/. "$SITE/$VERSION/"

# The composite lists every version directory, oldest first; p2 offers the newest of what they hold.
CHILDREN=$(cd "$SITE" && ls -d */ | sed 's:/$::' | sort -V)
COUNT=$(echo "$CHILDREN" | wc -l | tr -d ' ')
STAMP=$(($(date +%s) * 1000))
composite() { # <file> <processing instruction> <repository type>
    {
        echo "<?xml version='1.0' encoding='UTF-8'?>"
        echo "<?$2 version='1.0.0'?>"
        echo "<repository name='maddi' type='$3' version='1.0.0'>"
        echo "  <properties size='2'>"
        echo "    <property name='p2.timestamp' value='$STAMP'/>"
        echo "    <property name='p2.atomic.composite.loading' value='true'/>"
        echo "  </properties>"
        echo "  <children size='$COUNT'>"
        echo "$CHILDREN" | while read -r child; do echo "    <child location='$child'/>"; done
        echo "  </children>"
        echo "</repository>"
    } > "$1"
}
composite "$SITE/compositeContent.xml" compositeMetadataRepository \
    org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository
composite "$SITE/compositeArtifacts.xml" compositeArtifactRepository \
    org.eclipse.equinox.internal.p2.artifact.repository.CompositeArtifactRepository
printf 'version=1\nmetadata.repository.factory.order=compositeContent.xml,\\!\nartifact.repository.factory.order=compositeArtifacts.xml,\\!\n' \
    > "$SITE/p2.index"

OWNER=${SITE_REPO%%/*}
NAME=${SITE_REPO#*/}
PAGES_URL="https://$(echo "$OWNER" | tr '[:upper:]' '[:lower:]').github.io/$NAME/"
SITE_URL="${PAGES_URL}eclipse/"

# The site's front page is the IDE user guide, of this release: the stable link the marketplace listings and the
# plugins' settings name. GitHub renders the Markdown (its API, as on github.com: the same tables and code), since
# .nojekyll switches off the rendering Pages would otherwise do.
echo "==> Rendering docs/ide-user-guide.md as the site's front page"
GUIDE=$(gh api -X POST markdown -f mode=gfm -F text=@docs/ide-user-guide.md)
cat > "$WORK/site/index.html" <<EOF
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>maddi in the IDE</title>
<style>
  :root { color-scheme: light dark; --fg: #1f2328; --bg: #fff; --muted: #59636e; --line: #d1d9e0; --code: #f6f8fa; }
  @media (prefers-color-scheme: dark) {
    :root { --fg: #e6edf3; --bg: #0d1117; --muted: #9198a1; --line: #3d444d; --code: #151b23; }
  }
  body { margin: 0; background: var(--bg); color: var(--fg);
         font: 16px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif; }
  main { max-width: 860px; margin: 0 auto; padding: 32px 16px 64px; }
  h1, h2, h3 { line-height: 1.25; margin: 1.6em 0 0.6em; }
  h1 { font-size: 2em; margin-top: 0; padding-bottom: 0.3em; border-bottom: 1px solid var(--line); }
  h2 { font-size: 1.5em; padding-bottom: 0.3em; border-bottom: 1px solid var(--line); }
  a { color: #0969da; }
  @media (prefers-color-scheme: dark) { a { color: #4493f8; } }
  code { font: 85% ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; background: var(--code);
         padding: 0.2em 0.4em; border-radius: 6px; }
  table { border-collapse: collapse; display: block; overflow-x: auto; margin: 1em 0; }
  th, td { border: 1px solid var(--line); padding: 6px 13px; text-align: left; vertical-align: top; }
  th { background: var(--code); }
  footer { margin-top: 48px; color: var(--muted); font-size: 0.9em; border-top: 1px solid var(--line);
           padding-top: 12px; }
</style>
</head>
<body>
<main>
$GUIDE
<footer>maddi $VERSION · Eclipse update site: <a href="eclipse/">${SITE_URL}</a> ·
<a href="https://github.com/$RELEASE_REPO">source</a></footer>
</main>
</body>
</html>
EOF

git -C "$WORK/site" add -A
git -C "$WORK/site" commit --quiet -m "Eclipse update site and user guide: maddi $VERSION"

if [[ "${DRY_RUN:-0}" == 1 ]]; then
    echo "==> DRY_RUN: not pushing gh-pages, not uploading $SITE_ZIP. The prepared site:"
    git -C "$WORK/site" show --stat --oneline HEAD | head -20
    trap - EXIT
    echo "    left in $WORK/site for inspection"
    exit 0
fi

echo "==> Pushing gh-pages to $SITE_REPO"
git -C "$WORK/site" push --quiet origin gh-pages
echo "==> Attaching $(basename "$SITE_ZIP") to release $TAG on $RELEASE_REPO"
gh release upload "$TAG" -R "$RELEASE_REPO" "$SITE_ZIP" "$SITE_ZIP.sha256" --clobber
echo "==> Done. Update site: $SITE_URL (GitHub Pages may take a minute to serve it)."
