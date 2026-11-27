#!/usr/bin/env bash
set -euo pipefail

# Publishers always use the asset attached to the checksum-bearing GitHub tag.
[[ "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-(alpha|beta)\.[0-9]+)?$ ]] || {
  echo "Unsupported release tag: $RELEASE_TAG" >&2
  exit 1
}
ref="refs/tags/$RELEASE_TAG"
git fetch --no-tags --depth=1 origin "$ref:$ref"
version="$(git show "$ref:gradle.properties" | sed -n 's/^mod_version=//p')"
minecraft="$(git show "$ref:gradle.properties" | sed -n 's/^minecraft_version=//p')"
test "$RELEASE_TAG" = "v$version"
[[ "$minecraft" =~ ^[0-9]+(\.[0-9]+)+$ ]]
digest="$(git for-each-ref --format='%(contents)' "$ref" | sed -nE 's/^SHA256 ([0-9a-f]{64})$/\1/p')"
[[ "$digest" =~ ^[0-9a-f]{64}$ ]]
jar="fast-recipes-$version.jar"
directory="$(mktemp -d "$RUNNER_TEMP/fast-recipes-release.XXXXXX")"

release="$(gh release view "$RELEASE_TAG" --json isDraft,body,assets)"
jq -e --arg jar "$jar" '.isDraft == false and any(.assets[]; .name == $jar)' <<< "$release" >/dev/null
jq -er '.body | select(length > 0)' <<< "$release" > "$directory/changelog.md"
gh release download "$RELEASE_TAG" --pattern "$jar" --dir "$directory"
printf '%s  %s\n' "$digest" "$directory/$jar" | sha256sum --check

case "$version" in
  *-alpha.*) channel=alpha ;;
  *-beta.*) channel=beta ;;
  *) channel=release ;;
esac
{
  echo "VERSION=$version"
  echo "MINECRAFT=$minecraft"
  echo "CHANNEL=$channel"
  echo "JAR_FILE=$directory/$jar"
  echo "CHANGELOG_FILE=$directory/changelog.md"
} >> "$GITHUB_ENV"
