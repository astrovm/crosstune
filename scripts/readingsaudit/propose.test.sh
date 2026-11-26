#!/usr/bin/env bash
# Tests propose.sh on a local origin and a fake gh that records what it was asked. Fake data only.
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin" "$work/gh"
# A file fail-pr-create (or fail-pr-list, ...) makes that call fail once, saying what the file holds.
# Every call also goes to all-calls, which no case clears.
cat > "$work/bin/gh" <<'FAKE'
#!/usr/bin/env bash
echo "$*" >> "$FAKE/calls"
echo "$*" >> "$FAKE/all-calls"
if [ -f "$FAKE/fail-$1-$2" ]; then cat "$FAKE/fail-$1-$2" >&2; rm "$FAKE/fail-$1-$2"; exit 1; fi
case "$1 $2" in
  "pr list") [ -f "$FAKE/open" ] && cat "$FAKE/open"; exit 0 ;;
  "pr view") cat "$FAKE/body" ;;
  "pr create") while [ $# -gt 0 ]; do [ "$1" = --body-file ] && cp "$2" "$FAKE/body"; shift; done; echo 7 > "$FAKE/open" ;;
  "pr edit") while [ $# -gt 0 ]; do [ "$1" = --body-file ] && cp "$2" "$FAKE/body"; shift; done ;;
  "workflow run") ;;
  *) echo "unexpected: $*" >&2; exit 1 ;;
esac
FAKE
# Git, but a file race makes the next push wait until another commit has reached the branch first.
real_git=$(command -v git)
cat > "$work/bin/git" <<FAKE
#!/usr/bin/env bash
if [ "\$1" = push ] && [ -f "\$FAKE/race" ]; then
  rm "\$FAKE/race"
  "$real_git" push -q --force origin "main:refs/heads/\$BRANCH"
fi
exec "$real_git" "\$@"
FAKE
chmod +x "$work/bin/gh" "$work/bin/git"
export PATH="$work/bin:$PATH" FAKE="$work/gh"
export BRANCH=jmdict/remake TABLES=app/src/main/resources/com/astrovm/crosstune NOTICE_MANIFEST=jmdict-notice-texts.txt TAG=9.9.9+20000201000000 RUN=https://example.invalid/run/1
# The throwaway repositories get no hooks from the machine, so the test runs the same everywhere.
mkdir "$work/no-hooks"
export GIT_CONFIG_COUNT=2 GIT_CONFIG_KEY_0=init.defaultBranch GIT_CONFIG_VALUE_0=main GIT_CONFIG_KEY_1=core.hooksPath GIT_CONFIG_VALUE_1="$work/no-hooks"
fail() { echo "FAIL: $1" >&2; exit 1; }
person() { git -c user.name=test -c user.email=test@example.invalid "$@"; }
# Lists of real size. The committed jmdict-readings.txt is about 1.1 MB, far more than a pipe holds
# (64 KiB), so a reader that leaves a pipe early ends the writer with SIGPIPE.
awk 'BEGIN { for (i = 0; i < 60000; i++) printf "語%05d\tご%05d\n", i, i }' > "$work/filler"
list() { printf '# JMdict %s, by the Group\n%s\n' "$1" "$2"; cat "$work/filler"; }
remade() { list 2000-02-01 "星空	ほしぞら$1" > "$TABLES/jmdict-readings.txt"; list 2000-02-01 "おもいで	思い出" > "$TABLES/old-kana.txt"; list 2000-02-01 "おまえ	お前" > "$TABLES/shifted-nouns.txt"; }
# As the workflow's propose job: a fresh checkout of main with all of origin's history and no other
# local branch, the remade lists put in it, then propose.sh. This repository then fetches what it pushed.
checkout() { rm -rf "$work/ci" && git clone -q "$work/origin.git" "$work/ci" && cp -R "$TABLES/." "$work/ci/$TABLES/" && cp "$NOTICE_MANIFEST" "$work/ci/"; }
run_propose() {
  local status=0
  (cd "$work/ci" && "$here/propose.sh") > "$work/out" 2>&1 || status=$?
  git fetch -q --prune origin
  return "$status"
}
propose() { checkout && run_propose; }
calls() { cat "$FAKE/calls" 2>/dev/null || true; }
verified() { python3 "$here/proposal.py" verify "$1" "$(git rev-parse "origin/$BRANCH")" "$TABLES" "$NOTICE_MANIFEST" "$FAKE/body"; }
proof() { PYTHONPATH="$here" python3 -c 'import sys; from check_golden import readings_digest; print(readings_digest(".", *sys.argv[1:]))' origin/main "origin/$BRANCH"; }

# Main is moved in origin by fetching into it, as a hook may refuse any push to a main.
main_to_origin() {
  git -C "$work/origin.git" fetch -q "$work/repo" main:main && git fetch -q origin
  SOURCE_COMMIT=$(git rev-parse origin/main)
  export SOURCE_COMMIT
}
git init -q --bare "$work/origin.git"
git init -q "$work/repo"
cd "$work/repo"
git remote add origin "$work/origin.git"
mkdir -p "$TABLES/notices"
printf 'old licence\n' > "$TABLES/notices/edrdg-licence.txt"
printf 'old documentation\n' > "$TABLES/notices/jmdict-documentation.txt"
printf 'old digests\n' > "$NOTICE_MANIFEST"
list 2000-01-01 "星空	ほしぞら" > "$TABLES/jmdict-readings.txt"
list 2000-01-01 "おもいで	思い出" > "$TABLES/old-kana.txt"
list 2000-01-01 "おまえ	お前" > "$TABLES/shifted-nouns.txt"
person add "$TABLES" "$NOTICE_MANIFEST" && person commit -q -m "chore: main" && main_to_origin

# The same lists as main: nothing is pushed or asked.
propose || fail "same lists failed"
grep -q "already current for JMdict" "$work/out" || fail "no notice for the same lists"
[ -z "$(calls)" ] || fail "asked gh with nothing to propose"
git ls-remote --exit-code origin "$BRANCH" > /dev/null && fail "pushed with nothing to propose"

# New lists and no branch yet: one commit by the workflow and a new pull request, whose body asks a
# person to approve its runs.
remade ""
propose || { cat "$work/out"; fail "first proposal failed"; }
[ "$(git log --format=%ae%n%s -1 "origin/$BRANCH")" = "$(printf '%s\n%s' '41898282+github-actions[bot]@users.noreply.github.com' 'chore(readings): remake the JMdict word lists from JMdict 2000-02-01')" ] || fail "commit not the workflow's"
[ "$(git rev-list --count "origin/main..origin/$BRANCH")" = 1 ] || fail "not one commit on main"
grep -q "pr create --head $BRANCH --base main --title chore(readings): remake the JMdict word lists from JMdict 2000-02-01" "$FAKE/calls" || fail "no pull request opened"
grep -qxF "To run CI, select **Approve workflows to run** at the bottom of this pull request." "$FAKE/body" || fail "body doesn't ask a person to approve the runs"
grep -q "were from JMdict 2000-01-01" "$FAKE/body" || fail "body lacks the old date"
grep -q "jmdict-simplified \`$TAG\` (JMdict 2000-02-01)" "$FAKE/body" || fail "body lacks the release"
grep -q "3 files changed" "$FAKE/body" || fail "body lacks the diff"
verified origin/main || fail "the proposed input fingerprint or body was invalid"
grep -qF "add a line \`golden: none\` here when no reading changes" "$FAKE/body" || fail "body doesn't say how to pass the locked readings check"
# A mark is a whole line of the body: the hint must never be one, or the check would pass by itself.
grep -qF "add the \`golden: lines checked\` line it prints" "$FAKE/body" || fail "body doesn't say how to pass the whole-lines check"
! grep -qxE ' *golden: (none|lock changed|accept .*|lines checked .*) *' "$FAKE/body" || fail "body has a golden mark of its own"

# The same lists again, branch already on main: nothing pushed, the open pull request kept up to date
# with origin's branch, as the checkout has no local one.
remade "" && rm "$FAKE/calls"
printf '\ngolden: none\ngolden: lines checked 0123456789abcdef\n' >> "$FAKE/body"
pushed=$(git ls-remote origin "refs/heads/$BRANCH" | cut -f1)
propose || { cat "$work/out"; fail "second run failed"; }
grep -q "already has the snapshot" "$work/out" || fail "no notice for a branch already up to date"
grep -q "3 files changed" "$FAKE/body" || fail "body lacks the diff of origin's branch"
[ "$(git ls-remote origin "refs/heads/$BRANCH" | cut -f1)" = "$pushed" ] || fail "pushed a branch already up to date"
grep -q "^pr edit " "$FAKE/calls" && fail "same snapshot replaced the body"
grep -qx 'golden: lines checked 0123456789abcdef' "$FAKE/body" || fail "same snapshot erased the readings proof"

# GitHub bodies may use CRLF, even if the generated stamp had LF.
sed -i 's/$/\r/' "$FAKE/body"
cp "$FAKE/body" "$work/crlf-body"
rm "$FAKE/calls"
propose || { cat "$work/out"; fail "same snapshot with a CRLF body failed"; }
cmp "$FAKE/body" "$work/crlf-body" || fail "same snapshot rewrote the CRLF body"
grep -q '^pr edit ' "$FAKE/calls" && fail "CRLF body caused an edit"

# A run pushed the branch, then gh failed: the next run with the same lists opens the pull request,
# without a push.
rm "$FAKE/open" "$FAKE/calls"
echo "HTTP 502" > "$FAKE/fail-pr-create"
propose && fail "went on after gh failed"
grep -q "HTTP 502" "$work/out" || fail "gh's error not shown"
rm "$FAKE/calls"
propose || { cat "$work/out"; fail "run after a failed pull request failed"; }
[ "$(git ls-remote origin "refs/heads/$BRANCH" | cut -f1)" = "$pushed" ] || fail "pushed again after a failed pull request"
grep -q "^pr create --head $BRANCH " "$FAKE/calls" || fail "pull request not opened after it failed"

# The repository doesn't let workflows open pull requests: the run says which setting to turn on.
rm "$FAKE/open" "$FAKE/calls"
echo "pull request create failed: GraphQL: GitHub Actions is not permitted to create or approve pull requests (createPullRequest)" > "$FAKE/fail-pr-create"
propose && fail "went on when pull requests can't be opened"
grep -q "Settings → Actions → General → Workflow permissions → Allow GitHub Actions to create and approve pull requests" "$work/out" || fail "setting not named"
rm "$FAKE/calls"
propose > /dev/null || fail "no pull request after the setting was turned on"
grep -q "Allow GitHub Actions" "$work/out" && fail "setting named when gh failed for another reason"

# Asking gh for the open pull request fails: nothing more is asked, and the run fails.
rm "$FAKE/calls"
echo "HTTP 401" > "$FAKE/fail-pr-list"
propose && fail "went on after gh pr list failed"
[ "$(calls)" = "pr list --head $BRANCH --base main --state open --json number --jq .[0].number // empty" ] || fail "asked gh more after it failed"

# Main moved on: the branch is made again from it, and the open pull request edited.
digest=$(proof)
printf '\ngolden: none\ngolden: lines checked %s\n' "$digest" >> "$FAKE/body"
git checkout -q -- "$TABLES" && echo readme > README && person add README && person commit -q -m "docs: more" && main_to_origin
remade "
月夜	つきよ"
rm "$FAKE/calls"
propose || { cat "$work/out"; fail "update failed"; }
git merge-base --is-ancestor origin/main "origin/$BRANCH" || fail "branch not made from the new main"
[ "$(git rev-list --count "origin/main..origin/$BRANCH")" = 1 ] || fail "old commit kept"
grep -q "^pr edit 7 " "$FAKE/calls" || fail "open pull request not edited"
grep -q "^pr create" "$FAKE/calls" && fail "a second pull request opened"
grep -qxE 'golden: (none|lines checked .*)' "$FAKE/body" && fail "new data inherited an old proof"

# Main moved on again, and the branch already has these lists: it is still made again from the new main.
digest=$(proof)
printf '\ngolden: none\ngolden: lines checked %s\ngolden: accept 月夜 つきよ\ngolden: lines checked 0123456789abcdef\n' "$digest" >> "$FAKE/body"
made_on=$SOURCE_COMMIT
git checkout -q -- "$TABLES" && echo again >> README && person commit -qam "docs: more again" && main_to_origin
remade "
月夜	つきよ"
rm "$FAKE/calls"
SOURCE_COMMIT=$made_on propose || { cat "$work/out"; fail "update with the same lists failed"; }
grep -q "already has the snapshot" "$work/out" && fail "branch with the same lists taken as up to date on an old main"
git merge-base --is-ancestor origin/main "origin/$BRANCH" || fail "branch with the same lists not made from the new main"
[ "$(git rev-list --count "origin/main..origin/$BRANCH")" = 1 ] || fail "old commit kept with the same lists"
[ "$(proof)" = "$digest" ] || fail "the README change changed the readings digest"
grep -qxF "golden: lines checked $digest" "$FAKE/body" || fail "rebasing the same inputs erased the readings proof"
grep -qxF 'golden: none' "$FAKE/body" || fail "rebasing the same inputs erased golden: none"
grep -qxF 'golden: accept 月夜 つきよ' "$FAKE/body" || fail "rebasing the same inputs erased the accepted reading"
grep -qxF 'golden: lines checked 0123456789abcdef' "$FAKE/body" && fail "rebasing kept an obsolete digest"
python3 "$here/check_golden.py" --repo . --base origin/main --head "origin/$BRANCH" --body-file "$FAKE/body" ||
  fail "the locked readings check rejected the rebased proof"

# A newly downloaded notice makes a different snapshot, even though the readings digest is the same.
sed -i 's/$/\r/' "$FAKE/body"
printf 'revised licence\n' > "$TABLES/notices/edrdg-licence.txt"
propose || { cat "$work/out"; fail "update with a changed notice failed"; }
[ "$(proof)" = "$digest" ] || fail "the notice change changed the readings digest"
grep -qxF "golden: lines checked $digest" "$FAKE/body" || fail "a changed notice erased valid readings proof"

# The snapshot can still be rebased with a fresh remake when main changes a locked input, but its
# proof used the older input. First change the golden audit, which is not part of the readings digest.
readings_file=app/src/main/java/com/astrovm/crosstune/Readings.kt
test_unidic=app/src/test/java/com/astrovm/crosstune/TestUniDic.kt
golden_file=app/src/test/resources/com/astrovm/crosstune/readings-golden.tsv
for input in "$golden_file" scripts/readingsaudit/wholelines.py "$readings_file" app/build.gradle.kts; do
  digest=$(proof)
  printf '\ngolden: none\ngolden: lines checked %s\n' "$digest" >> "$FAKE/body"
  mkdir -p "$(dirname "$input")"
  case "$input" in
    "$golden_file") printf '# audit: 1 lines, 1 right readings, sha256 0123456789abcdef\n' > "$input" ;;
    scripts/readingsaudit/wholelines.py) printf 'new audit code\n' > "$input" ;;
    "$readings_file") printf 'new readings code\n' > "$input" ;;
    *) printf 'implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.1")\n' > "$input" ;;
  esac
  person add "$input" && person commit -qm "fix: change $input" && main_to_origin
  propose || { cat "$work/out"; fail "fresh remake after $input changed failed"; }
  grep -qxE 'golden: (none|lines checked .*)' "$FAKE/body" && fail "proof survived a change to $input"
done

# If the branch push succeeds and the body edit fails, the next run makes the body current.
digest=$(proof)
printf '\ngolden: none\ngolden: lines checked %s\n' "$digest" >> "$FAKE/body"
printf '# audit: 2 lines, 2 right readings, sha256 0123456789abcdef\n' > "$golden_file"
person add "$golden_file" && person commit -qm "fix: change the locked audit again" && main_to_origin
echo "HTTP 502" > "$FAKE/fail-pr-edit"
propose && fail "went on after gh pr edit failed"
held=$(git rev-parse "origin/$BRANCH")
git merge-base --is-ancestor origin/main "$held" || fail "the branch was not pushed"
verified origin/main > "$work/verify" 2>&1 && fail "CI accepted proof from before the failed body edit"
grep -q "proposal body is stale" "$work/verify" || fail "CI did not identify the stale body"
propose || { cat "$work/out"; fail "rerun after a failed body update failed"; }
[ "$(git rev-parse "origin/$BRANCH")" = "$held" ] || fail "pushed the snapshot again to repair the body"
grep -qxE 'golden: (none|lines checked .*)' "$FAKE/body" && fail "rerun left obsolete proof on the updated pull request"
verified origin/main || fail "CI rejected the repaired body"

# A maintainer can add the golden rows the proposal asks for without changing the generated files.
(
  git -C "$work/ci" switch -q --discard-changes --detach "$held" || exit 1
  printf '# Manual golden update\n' >> "$work/ci/$golden_file"
  cd "$work/ci"
  person add "$golden_file" && person commit -qm "test: add golden readings"
  python3 "$here/proposal.py" verify origin/main HEAD "$TABLES" "$NOTICE_MANIFEST" "$FAKE/body" || exit 1
  printf 'changed generated notice\n' >> "$TABLES/notices/edrdg-licence.txt"
  person add "$TABLES" && person commit -qm "test: modify generated bytes"
  if python3 "$here/proposal.py" verify origin/main HEAD "$TABLES" "$NOTICE_MANIFEST" "$FAKE/body"; then
    fail "CI accepted a change to the generated snapshot"
  fi
) || fail "CI rejected a manual golden update on the generated snapshot"

# A legacy body that survives two pushes cannot acquire new provenance from the first push.
legacy_bot="41898282+github-actions[bot]@users.noreply.github.com"
legacy=$(git -c user.name="github-actions[bot]" -c user.email="$legacy_bot" commit-tree "$held^{tree}" -p origin/main -m 'chore: old workflow snapshot')
git push -q --force origin "$legacy:refs/heads/$BRANCH"
git fetch -q origin
digest=$(proof)
sed -i '/^<!-- JMdict proposal /d' "$FAKE/body"
printf '\ngolden: none\ngolden: lines checked %s\n' "$digest" >> "$FAKE/body"
printf '# audit: 3 lines, 3 right readings, sha256 0123456789abcdef\n' > "$golden_file"
person add "$golden_file" && person commit -qm "fix: change the locked audit once more" && main_to_origin
echo "HTTP 502" > "$FAKE/fail-pr-edit"
propose && fail "went on after the legacy body edit failed"
printf 'another licence edit\n' >> "$TABLES/notices/edrdg-licence.txt"
propose || { cat "$work/out"; fail "a notice update after a failed legacy edit failed"; }
grep -qxE 'golden: (none|lines checked .*)' "$FAKE/body" && fail "stale legacy proof acquired a new stamp"
verified origin/main || fail "CI rejected the repaired legacy body"


# A stale digest cannot be carried forward even after a README-only change.
printf '\ngolden: none\ngolden: lines checked 0123456789abcdef\n' >> "$FAKE/body"
echo more >> README && person commit -qm "docs: more" -- README && main_to_origin
propose || { cat "$work/out"; fail "update with a stale digest failed"; }
grep -qxE 'golden: (none|lines checked .*)' "$FAKE/body" && fail "a stale proof survived a rebase"

# Another push reaches the branch between reading it and pushing: the lease refuses, nothing is lost.
remade "
雪空	ゆきぞら" && rm "$FAKE/calls"
touch "$FAKE/race"
propose && fail "pushed over a push it didn't see"
[ "$(git ls-remote origin "refs/heads/$BRANCH" | cut -f1)" = "$(git rev-parse origin/main)" ] || fail "the other push was thrown away"
[ -z "$(calls)" ] || fail "asked gh after the push was refused"

# Origin can't be read: the run fails, and never takes it for a missing branch.
remade "
風花	かざはな" && rm -f "$FAKE/calls"
checkout && git -C "$work/ci" remote set-url origin "$work/nowhere.git"
run_propose && fail "went on without origin"
grep -q "Can't read main from origin" "$work/out" || fail "no error for an origin it can't read"
[ -z "$(calls)" ] || fail "asked gh without origin"

# Commits a person made on the branch: nothing is thrown away, and the run fails. One the person
# wrote, one the workflow wrote and the person rebased or amended, one the person wrote with the
# workflow's address as its author, one with it as its committer, and so many that their addresses
# are more than a pipe holds.
bot="41898282+github-actions[bot]@users.noreply.github.com"
git push -q --force origin "origin/main:refs/heads/$BRANCH"
remade ""
propose || { cat "$work/out"; fail "proposal before a person's commits failed"; }
proposed=$(git rev-parse "origin/$BRANCH")
git checkout -q -- "$TABLES"
# 3000 empty commits by a person, about 130 KB of addresses from git log.
many() {
  awk -v from="$proposed" -v branch="refs/heads/$BRANCH" 'BEGIN {
    for (i = 1; i <= 3000; i++) {
      printf "commit %s\nauthor test <test@example.invalid> %d +0000\ncommitter test <test@example.invalid> %d +0000\ndata 7\nfix: %02d\n", branch, i, i, i % 100
      if (i == 1) printf "from %s\n", from
      printf "\n"
    }
  }' | git fast-import --quiet --force
  git switch -q "$BRANCH"
}
for how in wrote rebased authored-as-bot committed-as-bot "made many commits"; do
  git switch -q -C "$BRANCH" "$proposed"
  case $how in
    wrote) echo note > NOTE && person add NOTE && person commit -q -m "fix: by hand" ;;
    rebased) person commit -q --amend --no-edit ;;
    authored-as-bot) echo note > NOTE && person add NOTE && person commit -q --author="github-actions[bot] <$bot>" -m "fix: by hand" ;;
    committed-as-bot)
      echo note > NOTE && person add NOTE
      GIT_COMMITTER_NAME="github-actions[bot]" GIT_COMMITTER_EMAIL="$bot" person commit -q -m "fix: by hand"
      [ "$(git log -1 --format='%ae %ce')" = "test@example.invalid $bot" ] || fail "commit not committed as the workflow"
      ;;
    "made many commits") git switch -q main && many ;;
  esac
  git push -q --force origin "$BRANCH"
  held=$(git rev-parse HEAD)
  git switch -q main && remade "
雨空	あまぞら" && rm -f "$FAKE/calls"
  propose && fail "went on over a commit a person $how"
  grep -q "has a commit by a person" "$work/out" || fail "no error for a commit a person $how"
  [ "$(git ls-remote origin "refs/heads/$BRANCH" | cut -f1)" = "$held" ] || fail "a commit a person $how was thrown away"
  [ -z "$(calls)" ] || fail "asked gh over a commit a person $how"
  git checkout -q -- "$TABLES"
done
# A notice-only change still opens a proposal, with both pages and their digest file.
git push -q --force origin "origin/main:refs/heads/$BRANCH"
rm -f "$FAKE/open" "$FAKE/calls"
printf 'new licence\n' > "$TABLES/notices/edrdg-licence.txt"
printf 'new documentation\n' > "$TABLES/notices/jmdict-documentation.txt"
printf 'new digests\n' > "$NOTICE_MANIFEST"
propose || { cat "$work/out"; fail "notice-only proposal failed"; }
grep -q "^pr create " "$FAKE/calls" || fail "no pull request for changed notices"
changed=$(git diff --name-only origin/main "origin/$BRANCH")
expected=$(printf '%s\n' "$TABLES/notices/edrdg-licence.txt" "$TABLES/notices/jmdict-documentation.txt" "$NOTICE_MANIFEST")
[ "$changed" = "$expected" ] || fail "notice-only proposal changed the wrong paths"
grep -q "3 files changed" "$FAKE/body" || fail "body lacks the notice diff"

# Main may move after the proposal job checks out a commit. Compare the published snapshot with both
# the checkout it started from and the main tip it actually reads at runtime.
old_commit=$SOURCE_COMMIT
held=$(git rev-parse "origin/$BRANCH")
checkout
mkdir -p "$(dirname "$test_unidic")"
printf 'dictionary fixture changed after checkout\n' >> "$test_unidic"
person add "$test_unidic" && person commit -qm "fix: change the dictionary fixture" && main_to_origin
rm -f "$FAKE/calls"
SOURCE_COMMIT=$old_commit run_propose && fail "accepted a stale snapshot after the job checked out"
grep -q "changed since the snapshot was made" "$work/out" || fail "no reason for rejecting stale checkout"
verified origin/main > "$work/verify" 2>&1 && fail "CI accepted a snapshot produced for older inputs"
grep -q "inputs on main differ" "$work/verify" || fail "CI did not reject outdated inputs"
[ "$(git rev-parse "origin/$BRANCH")" = "$held" ] || fail "the stale checkout pushed its snapshot"
[ -z "$(calls)" ] || fail "the stale checkout asked gh"

# When main changes an input after remake, reject its artifact before pushing or calling GitHub.
for input in "$TABLES/notices/jmdict-documentation.txt" "$readings_file" "$test_unidic" app/build.gradle.kts \
  gradle/libs.versions.toml gradle/wrapper/gradle-wrapper.properties app/src/test/resources/robolectric.properties; do
  old_commit=$SOURCE_COMMIT
  mkdir -p "$(dirname "$input")"
  case "$input" in
    app/build.gradle.kts) printf 'implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.2")\n' > "$input" ;;
    gradle/libs.versions.toml) printf '[versions]\nkuromoji = "0.9.2"\n' > "$input" ;;
    *) printf 'new input on main for %s\n' "$input" >> "$input" ;;
  esac
  person add "$input" && person commit -qm "fix: change $input during remake" && main_to_origin
  printf 'older documentation from the job\n' > "$TABLES/notices/jmdict-documentation.txt"
  held=$(git rev-parse "origin/$BRANCH")
  rm -f "$FAKE/calls"
  SOURCE_COMMIT=$old_commit propose && fail "accepted a stale snapshot after $input changed"
  grep -q 'changed since the snapshot was made' "$work/out" || fail "missing stale snapshot message for $input"
  [ "$held" = "$(git rev-parse "origin/$BRANCH")" ] || fail "stale job pushed after $input changed"
  [ -z "$(calls)" ] || fail "stale job asked gh after $input changed"
done

# A CI run started with gh workflow run never counts as a pull request's checks (GitHub's docs,
# "Troubleshooting required status checks"). A person approves the pull request's own runs instead.
grep -q "^workflow run" "$FAKE/all-calls" && fail "propose.sh started a workflow by gh workflow run"
echo "propose.sh: all cases pass"
