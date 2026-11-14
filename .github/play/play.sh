#!/usr/bin/env bash
# Sends the app to Google Play with fastlane supply. release.yml, promote.yml and store-listing.yml run it from
# a checkout with only this folder and the store text, so none of the app's build code is where the Play key is.
#
#   play.sh release TAG        Crosstune-TAG.aab to internal testing, which Google doesn't review. Then one edit
#                              sends that release on to each track in PLAY_TRACKS, with the listing. So each
#                              release goes to Google's review once.
#   play.sh production VERSION The release VERSION (2.26.0) on the first track in PLAY_TRACKS goes on to
#                              production, with its English release notes, in an edit of its own.
#   play.sh listing            The listing alone. CHECK_ONLY=true only asks Play whether it would take it.
#
# It reads PLAY_SERVICE_ACCOUNT_JSON (the key), PLAY_TRACKS (testing tracks, with commas or spaces between,
# default beta), PLAY_LANGUAGES (folders of fastlane/metadata/android, default en-US) and CHECK_ONLY, from the
# working directory.
#
# Every commit asks Play to keep a review in progress, and production never shares an edit with another track
# (review_guard.rb). While a review is in progress, Play refuses the commit, nothing is sent, and the job fails
# and says to re-run it when the review ends. A re-run starts over: a bundle that is already on internal testing
# isn't sent again. Another bundle with the same version code stops the job. A tag with a suffix (v2.27.0-rc.1)
# isn't sent to Play.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
package=com.astrovm.crosstune
metadata=fastlane/metadata/android

if [[ -z "${PLAY_SERVICE_ACCOUNT_JSON:-}" ]]; then
  echo "::error::PLAY_SERVICE_ACCOUNT_JSON isn't set"
  exit 1
fi

# The testing tracks a release goes to. Production has its own step, and internal testing always comes first.
play_tracks="${PLAY_TRACKS:-beta}"
read -r -a tracks <<< "${play_tracks//,/ }"
if [[ ${#tracks[@]} == 0 ]]; then
  echo "::error::PLAY_TRACKS names no track"
  exit 1
fi
for track in "${tracks[@]}"; do
  # A form factor's tracks too, such as tv:production.
  if [[ "$track" =~ (^|:)(production|internal)$ || ! "$track" =~ ^[A-Za-z0-9:_-]+$ ]]; then
    echo "::error::PLAY_TRACKS can't hold '$track'. It names testing tracks: production goes on its own, with promote.yml, and internal testing always gets the release first"
    exit 1
  fi
done

work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/play.XXXXXX")"
trap 'rm -rf "$work"' EXIT
key="$work/play.json"
(umask 077 && printf '%s' "$PLAY_SERVICE_ACCOUNT_JSON" > "$key")

# The versions in Gemfile.lock, and only those. The tests add their own group.
export BUNDLE_GEMFILE="$here/Gemfile"
export BUNDLE_FROZEN=true
export BUNDLE_WITHOUT="${BUNDLE_WITHOUT-test}"
export BUNDLE_PATH="${BUNDLE_PATH:-${RUNNER_TEMP:-$work}/bundle}"
# The versions are pinned. Skip the update check, and send no usage metrics.
export FASTLANE_SKIP_UPDATE_CHECK=1
export FASTLANE_OPT_OUT_USAGE=1
if ! command -v bundle > /dev/null; then
  sudo gem install bundler --no-document --silent
fi
bundle install --quiet

play() {
  bundle exec ruby "$here/play.rb" "$@"
}

version_code() {
  # The same as release.yml.
  if [[ ! "$1" =~ ^v?([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    echo "::error::'$1' must look like 2.26.0 or v2.26.0" >&2
    exit 1
  fi
  echo $((10#${BASH_REMATCH[1]} * 1000000 + 10#${BASH_REMATCH[2]} * 10000 + 10#${BASH_REMATCH[3]} * 100))
}

# Just the English release notes: other folders use F-Droid's language codes, which Play doesn't all accept.
copy_notes() {
  local notes="$1" code="$2"
  mkdir -p "$notes/en-US/changelogs"
  cp "$metadata/en-US/changelogs/$code.txt" "$notes/en-US/changelogs/"
}

# A copy with only what the listing shows, under the names Google Play has for the languages. The folders
# use F-Droid's names, and Play has no plain "es": the Spanish here is Latin America's.
copy_listing() {
  local listing="$1" language from target
  local -a languages
  read -r -a languages <<< "${PLAY_LANGUAGES:-en-US}"
  for language in "${languages[@]}"; do
    from="$metadata/$language"
    if [[ ! "$language" =~ ^[A-Za-z0-9-]+$ || ! -f "$from/title.txt" ]]; then
      echo "::error::No listing in $metadata/$language"
      exit 1
    fi
    case "$language" in
      es) target=es-419 ;;
      *) target=$language ;;
    esac
    mkdir -p "$listing/$target"
    cp "$from/title.txt" "$from/short_description.txt" "$from/full_description.txt" "$listing/$target/"
    if [[ -d "$from/images" ]]; then
      cp -r "$from/images" "$listing/$target/"
    fi
  done
}

release() {
  local tag="$1" code found
  # A tag with a suffix, as in v2.27.0-rc.1, has the version code of 2.27.0, and Play takes each version code
  # once. Sent to Play, it would keep 2.27.0 itself out. Testers on Play get each version on the testing tracks.
  if [[ "$tag" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+[-.] ]]; then
    echo "::notice::$tag has a suffix, so it isn't sent to Google Play: Play takes each version code once, and the version without the suffix needs it"
    exit 0
  fi
  code="$(version_code "$tag")"
  copy_notes "$work/notes" "$code"
  copy_listing "$work/listing"

  # Internal testing first: Google doesn't review it, so testers there get each release in minutes, and the
  # track never sits on an old version. While a review is in progress, Play takes no commit, this one included.
  # A re-run finds the bundle there already.
  found=0
  play has-bundle "$key" "$package" internal "$code" "Crosstune-$tag.aab" || found=$?
  if [[ "$found" == 0 ]]; then
    echo "Internal testing already has this bundle as $code, so it isn't sent again"
  elif [[ "$found" == 3 ]]; then
    play supply \
      --package_name "$package" \
      --json_key "$key" \
      --aab "Crosstune-$tag.aab" \
      --track internal \
      --metadata_path "$work/notes" \
      --skip_upload_apk true \
      --skip_upload_metadata true \
      --skip_upload_images true \
      --skip_upload_screenshots true
  else
    exit "$found"
  fi

  # One edit, so Google reviews the release on every testing track and the listing at once. The release
  # carries its notes along.
  local IFS=,
  play edit "$key" "$package" --from internal --code "$code" --to "${tracks[*]}" --listing "$work/listing"
}

production() {
  local code
  code="$(version_code "$1")"
  copy_notes "$work/notes" "$code"
  play edit "$key" "$package" --from "${tracks[0]}" --code "$code" --to production --notes "$work/notes"
}

listing() {
  local -a check=()
  if [[ "${CHECK_ONLY:-false}" == true ]]; then
    check=(--check-only)
  fi
  copy_listing "$work/listing"
  play edit "$key" "$package" --listing "$work/listing" "${check[@]}"
}

case "${1:-}" in
  release) release "${2:?usage: play.sh release TAG}" ;;
  production) production "${2:?usage: play.sh production VERSION}" ;;
  listing) listing ;;
  *)
    echo "usage: play.sh release TAG | play.sh production VERSION | play.sh listing" >&2
    exit 2
    ;;
esac
