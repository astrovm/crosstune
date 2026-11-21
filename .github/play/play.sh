#!/usr/bin/env bash
# Sends the app to Google Play with fastlane supply. release.yml, promote.yml and store-listing.yml check out
# only this folder and the store text, so none of the app's build code is where the Play key is. promote.yml and
# store-listing.yml take this folder from their own commit, and the store text from the version they send.
#
#   play.sh release TAG        Crosstune-TAG.aab to internal testing, which Google doesn't review. That's all,
#                              unless PLAY_TRACKS names testing tracks: then one more edit sends that release on
#                              to each of them, with the listing, and that edit goes to Google's review.
#   play.sh production VERSION The release VERSION (2.26.0) on internal testing goes on to production, with its
#                              English release notes and the listing, in one edit, so one review takes them all.
#                              When PLAY_TRACKS names testing tracks, it comes from the first of them instead,
#                              with only its notes, as the listing went there. Any other version is refused,
#                              and so is one older than production's, or a source release with any other
#                              version code beside it. The checks read every track it goes to, a form factor's
#                              production (tv:production) too, in the same Play edit that promotes it.
#                              Before anything is sent, its tag v2.26.0 has to be on origin, no version code on
#                              Play may lack a tag (as for a release), and the bundle Play has as that version
#                              code has to be the one the Release workflow made: its SHA-256 is in the GitHub
#                              release's notes, "App Bundle SHA-256: `...`". A release made before the notes
#                              had that line (v2.27.0 and older) can't go on until someone adds it by hand, after
#                              checking that the .aab on the release is the one the workflow made.
#   play.sh listing            The listing alone. CHECK_ONLY=true only asks Play whether it would take it.
#   play.sh setup              Bundler and the gems Gemfile.lock pins, in a step before the key is there.
#   play.sh --help             This text.
#
# It reads PLAY_KEY_JSON (the key), PLAY_TRACKS (testing tracks, with commas or spaces between, none when it's
# unset or empty), PLAY_LANGUAGES (folders of fastlane/metadata/android, default en-US) and CHECK_ONLY, from the
# working directory. production reads GITHUB_REPOSITORY, GITHUB_API_URL (GitHub sets both) and, when it is set,
# GH_TOKEN, which a job with no permissions can pass: the release of a public repository is public, and a token
# only lifts the limit on requests with no token.
#
# Every commit asks Play to keep a review in progress, and production never shares an edit with another track
# (review_guard.rb). While a review is in progress, Play refuses the commit, nothing is sent, and the job fails
# and says to re-run it when the review ends. A re-run starts over: a bundle that Play already has isn't sent
# again. Another bundle with the same version code stops the job. A tag with a suffix (v2.27.0-rc.1)
# isn't sent to Play.
#
# No release takes a track back to an older version. When internal testing, a testing track or production has
# a newer version, as for an older tag's release run again, Play gets nothing and the job fails. A version code
# on Play that no tag on origin has stops the job: something sent by hand, or a tag since deleted.
set -euo pipefail

if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
  exit 0
fi

# A tag with a suffix, as in v2.27.0-rc.1, has the version code of 2.27.0, and Play takes each version code
# once. Sent to Play, it would keep 2.27.0 itself out. release.yml doesn't run this for one; this is the same
# check, before the key is used. Testers on Play get each version on internal testing.
if [[ "${1:-}" == release && "${2:-}" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+[-.][0-9A-Za-z.-]+$ ]]; then
  echo "::notice::$2 has a suffix, so it isn't sent to Google Play:" \
    "Play takes each version code once, and the version without the suffix needs it"
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
  if [[ -n "${PLAY_KEY_JSON:-}" ]]; then
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

if [[ -z "${PLAY_KEY_JSON:-}" ]]; then
  echo "::error::PLAY_KEY_JSON isn't set"
  exit 1
fi

# The testing tracks a release goes to after internal testing, none by default. Production has its own step,
# and internal testing always comes first.
play_tracks="${PLAY_TRACKS:-}"
read -r -a tracks <<< "${play_tracks//,/ }"
for track in "${tracks[@]}"; do
  # A form factor's tracks too, such as tv:production.
  if [[ "$track" =~ (^|:)(production|internal)$ || ! "$track" =~ ^[A-Za-z0-9:_-]+$ ]]; then
    echo "::error::PLAY_TRACKS can't hold '$track'. It names testing tracks:" \
      "production goes on its own, with promote.yml, and internal testing always gets the release first"
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
(umask 077 && printf '%s' "$PLAY_KEY_JSON" > "$key")

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

# The version code of each tag on origin, with commas between, read now: a re-run of a job sees the tags
# pushed since. No token: the repository is public, and the job has no permissions. With a tag named, it has to
# be one of them, as a version goes to production only with its tag.
tag_codes() {
  local required="${1:-}" refs ref tag codes="" found=false
  if ! refs="$(GIT_TERMINAL_PROMPT=0 git ls-remote --tags origin)"; then
    echo "::error::The tags on origin couldn't be listed, so nothing was sent to Google Play" >&2
    exit 1
  fi
  while read -r _ ref; do
    tag="${ref#refs/tags/}"
    if [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      codes+="${codes:+,}$(version_code "$tag")"
      if [[ "$tag" == "$required" ]]; then
        found=true
      fi
    fi
  done <<< "$refs"
  if [[ -n "$required" && "$found" != true ]]; then
    echo "::error::The tag $required isn't on origin, so nothing was sent to Google Play:" \
      "a version goes to production only with its tag, from a release on main" >&2
    exit 1
  fi
  echo "$codes"
}

# GitHub's release of a tag, as JSON in the file named. Its notes have the App Bundle's SHA-256, which play.rb
# compares with the bundle Play has. Public, so with no token it only meets a lower limit on requests.
fetch_release() {
  local tag="$1" file="$2" repository="${GITHUB_REPOSITORY:-}" api="${GITHUB_API_URL:-https://api.github.com}"
  local -a auth=()
  if [[ -z "$repository" ]]; then
    echo "::error::GITHUB_REPOSITORY isn't set, so the release of $tag can't be read" >&2
    exit 1
  fi
  if [[ -n "${GH_TOKEN:-}" ]]; then
    auth=(--header "Authorization: Bearer $GH_TOKEN")
  fi
  if ! curl --fail --silent --show-error --location --max-time 30 --retry 3 \
    --header "Accept: application/vnd.github+json" "${auth[@]}" \
    --output "$file" "$api/repos/$repository/releases/tags/$tag"; then
    echo "::error::The release $tag couldn't be read from GitHub, so nothing was sent to Google Play" >&2
    exit 1
  fi
}

# Just the English release notes: other folders use F-Droid's language codes, which Play doesn't all accept.
copy_notes() {
  local notes="$1" code="$2" from="$metadata/en-US/changelogs/$2.txt"
  if [[ ! -f "$from" ]]; then
    echo "::error::$from isn't here, so nothing was sent to Google Play"
    exit 1
  fi
  mkdir -p "$notes/en-US/changelogs"
  cp "$from" "$notes/en-US/changelogs/"
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

# The tracks a release or a promotion reads for strays and newer versions: internal testing, then each track in
# PLAY_TRACKS, with commas between. Production is read too, by play.rb.
searched() {
  local IFS=,
  echo "internal${tracks[*]:+,${tracks[*]}}"
}

release() {
  local tag="$1" code found known
  code="$(version_code "$tag")"
  known="$(tag_codes)"
  copy_notes "$work/notes" "$code"
  if [[ ${#tracks[@]} -gt 0 ]]; then
    copy_listing "$work/listing"
  fi

  # Internal testing first: Google doesn't review it, so testers there get each release in minutes, and the
  # track never sits on an old version. While a review is in progress, Play takes no commit, this one included.
  # A re-run finds the bundle there already. Before anything is sent, Play is asked what internal testing, each
  # track in PLAY_TRACKS and production have, and a newer version on any of them stops the job.
  found=0
  play plan "$key" "$package" "$(searched)" "$code" "Crosstune-$tag.aab" "$known" ||
    found=$?
  case "$found" in
    0) echo "Internal testing already has this bundle as $code, so it isn't sent again" ;;
    4)
      # Sent in a run whose commit Play refused: Play may keep the bundle, and takes each version code once.
      echo "Google Play has this bundle as $code from an earlier run, and no testing track has a newer version," \
        "so it isn't sent again"
      play edit "$key" "$package" --code "$code" --to internal --notes "$work/notes"
      ;;
    3)
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
      ;;
    *) exit "$found" ;;
  esac

  # With no testing tracks, the release waits on internal testing for promote.yml, which takes the listing
  # along to production.
  if [[ ${#tracks[@]} == 0 ]]; then
    return
  fi
  # One edit, so Google reviews the release on every testing track and the listing at once. The release
  # carries its notes along.
  local IFS=,
  play edit "$key" "$package" --from internal --code "$code" --to "${tracks[*]}" --listing "$work/listing"
}

production() {
  local version="${1#v}" code tag known
  code="$(version_code "$version")"
  tag="v$version"
  # Before Play is asked anything: the tag, and the release the workflow made for it.
  known="$(tag_codes "$tag")"
  fetch_release "$tag" "$work/release.json"
  copy_notes "$work/notes" "$code"
  if [[ ${#tracks[@]} -gt 0 ]]; then
    # The listing went to review with the testing tracks already.
    play promotion "$key" "$package" "${tracks[0]}" "$(searched)" "$code" "$known" \
      "$work/release.json" "$tag" "$work/notes"
  else
    # The listing goes with the release, in one folder, as supply reads one folder an edit.
    copy_listing "$work/notes"
    play promotion "$key" "$package" internal internal "$code" "$known" \
      "$work/release.json" "$tag" "$work/notes" "$work/notes"
  fi
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
    echo "usage: play.sh release TAG | production VERSION | listing | setup | --help" >&2
    exit 2
    ;;
esac
