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
#   play.sh setup              Bundler and the gems Gemfile.lock pins, in a step before the key is there.
#
# It reads PLAY_SERVICE_ACCOUNT_JSON (the key), PLAY_TRACKS (testing tracks, with commas or spaces between,
# default beta), PLAY_LANGUAGES (folders of fastlane/metadata/android, default en-US) and CHECK_ONLY, from the
# working directory.
#
# Every commit asks Play to keep a review in progress, and production never shares an edit with another track
# (review_guard.rb). While a review is in progress, Play refuses the commit, nothing is sent, and the job fails
# and says to re-run it when the review ends. A re-run starts over: a bundle that Play already has isn't sent
# again. Another bundle with the same version code stops the job. A tag with a suffix (v2.27.0-rc.1)
# isn't sent to Play. Nor is a tag older than what a track it goes to has, such as a backport: it stays on
# GitHub only, and production refuses it.
set -euo pipefail

# A tag with a suffix, as in v2.27.0-rc.1, has the version code of 2.27.0, and Play takes each version code
# once. Sent to Play, it would keep 2.27.0 itself out. release.yml doesn't run this for one; this is the same
# check, before the key is used. Testers on Play get each version on the testing tracks.
if [[ "${1:-}" == release && "${2:-}" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+[-.][0-9A-Za-z.-]+$ ]]; then
  echo "::notice::$2 has a suffix, so it isn't sent to Google Play: Play takes each version code once, and the version without the suffix needs it"
  exit 0
fi

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
package=com.astrovm.crosstune
metadata=fastlane/metadata/android

# The versions in Gemfile.lock, and only those, Bundler's own too. The tests add their own group.
export BUNDLE_GEMFILE="$here/Gemfile"
export BUNDLE_FROZEN=true
export BUNDLE_WITHOUT="${BUNDLE_WITHOUT-test}"
export BUNDLE_PATH="${BUNDLE_PATH:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/play-gems}"
BUNDLER_VERSION="$(awk '/^BUNDLED WITH$/ { getline; print $1 }' "$here/Gemfile.lock")"
export BUNDLER_VERSION
# The versions are pinned. Skip the update check, and send no usage metrics.
export FASTLANE_SKIP_UPDATE_CHECK=1
export FASTLANE_OPT_OUT_USAGE=1

bundler_installed() {
  gem list --installed --exact bundler --version "$BUNDLER_VERSION" > /dev/null
}

# Installing a gem runs its own code, as in building its native extensions, so it happens in a step of its own,
# before the key is there. Every gem is checked against the checksums in Gemfile.lock.
if [[ "${1:-}" == setup ]]; then
  if [[ -n "${PLAY_SERVICE_ACCOUNT_JSON:-}" ]]; then
    echo "::error::play.sh setup installs gems, which run their own code, so it never runs with the Play key"
    exit 1
  fi
  if ! bundler_installed; then
    gems="$(gem env gemdir)"
    # The runner's Ruby is the system's, whose gems only root can write.
    if mkdir -p "$gems" 2> /dev/null && [[ -w "$gems" ]]; then
      gem install bundler --version "$BUNDLER_VERSION" --no-document --silent
    else
      sudo gem install bundler --version "$BUNDLER_VERSION" --no-document --silent
    fi
  fi
  bundle install --quiet
  exit 0
fi

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

# Nothing is installed from here on, where the key is.
if ! bundler_installed || ! bundle check > /dev/null 2>&1; then
  echo "::error::The gems aren't installed. Run play.sh setup first, without the key"
  exit 1
fi

work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/play.XXXXXX")"
trap 'rm -rf "$work"' EXIT
key="$work/play.json"
(umask 077 && printf '%s' "$PLAY_SERVICE_ACCOUNT_JSON" > "$key")

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
  code="$(version_code "$tag")"
  copy_notes "$work/notes" "$code"
  copy_listing "$work/listing"

  # Internal testing first: Google doesn't review it, so testers there get each release in minutes, and the
  # track never sits on an old version. While a review is in progress, Play takes no commit, this one included.
  # A re-run finds the bundle there already. Before anything is sent, Play is asked for every track this goes
  # to: one with a newer version means an older tag run again, or a backport, and neither takes a track back.
  found=0
  play has-bundle "$key" "$package" "$(IFS=,; echo "internal,${tracks[*]}")" "$code" "Crosstune-$tag.aab" || found=$?
  if [[ "$found" == 5 ]]; then
    echo "::notice::$tag stays on GitHub only: Google Play already has a newer version on a testing track"
    return
  elif [[ "$found" == 0 ]]; then
    echo "Internal testing already has this bundle as $code, so it isn't sent again"
  elif [[ "$found" == 4 ]]; then
    # Sent in a run whose commit Play refused: Play may keep the bundle, and takes each version code once.
    echo "Google Play has this bundle as $code from an earlier run, and no testing track has a newer version, so it isn't sent again"
    play edit "$key" "$package" --code "$code" --to internal --notes "$work/notes"
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
    echo "usage: play.sh release TAG | play.sh production VERSION | play.sh listing | play.sh setup" >&2
    exit 2
    ;;
esac
