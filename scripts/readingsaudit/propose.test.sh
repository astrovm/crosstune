#!/usr/bin/env bash
# Tests propose.sh on a local origin and a fake gh that records what it was asked. Fake data only.
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin" "$work/gh"
cat > "$work/bin/gh" <<'FAKE'
#!/usr/bin/env bash
echo "$*" >> "$FAKE/calls"
case "$1 $2" in
  "pr list") [ -f "$FAKE/open" ] && cat "$FAKE/open"; exit 0 ;;
  "pr create") while [ $# -gt 0 ]; do [ "$1" = --body-file ] && cp "$2" "$FAKE/body"; shift; done; echo 7 > "$FAKE/open" ;;
  "pr edit") while [ $# -gt 0 ]; do [ "$1" = --body-file ] && cp "$2" "$FAKE/body"; shift; done ;;
  "workflow run") ;;
  *) echo "unexpected: $*" >&2; exit 1 ;;
esac
FAKE
chmod +x "$work/bin/gh"
export PATH="$work/bin:$PATH" FAKE="$work/gh"
export BRANCH=jmdict/remake TABLES=lists TAG=9.9.9+20000201000000 RUN=https://example.invalid/run/1
# The throwaway repositories get no hooks from the machine, so the test runs the same everywhere.
mkdir "$work/no-hooks"
export GIT_CONFIG_COUNT=2 GIT_CONFIG_KEY_0=init.defaultBranch GIT_CONFIG_VALUE_0=main GIT_CONFIG_KEY_1=core.hooksPath GIT_CONFIG_VALUE_1="$work/no-hooks"
fail() { echo "FAIL: $1" >&2; exit 1; }
person() { git -c user.name=test -c user.email=test@example.invalid "$@"; }
list() { printf '# JMdict %s, by the Group\n%s\n' "$1" "$2"; }
remade() { list 2000-02-01 "星空	ほしぞら$1" > lists/jmdict-readings.txt; list 2000-02-01 "おもいで	思い出" > lists/old-kana.txt; }
propose() { "$here/propose.sh" > "$work/out" 2>&1; }
calls() { cat "$FAKE/calls" 2>/dev/null || true; }

# Main is moved in origin by fetching into it, as a hook may refuse any push to a main.
main_to_origin() { git -C "$work/origin.git" fetch -q "$work/repo" main:main && git fetch -q origin; }
git init -q --bare "$work/origin.git"
git init -q "$work/repo"
cd "$work/repo"
git remote add origin "$work/origin.git"
mkdir lists
list 2000-01-01 "星空	ほしぞら" > lists/jmdict-readings.txt
list 2000-01-01 "おもいで	思い出" > lists/old-kana.txt
person add lists && person commit -q -m "chore: main" && main_to_origin

# The same lists as main: nothing is pushed or asked.
propose || fail "same lists failed"
grep -q "already what JMdict" "$work/out" || fail "no notice for the same lists"
[ -z "$(calls)" ] || fail "asked gh with nothing to propose"
git ls-remote --exit-code origin "$BRANCH" > /dev/null && fail "pushed with nothing to propose"

# New lists and no branch yet: one commit by the workflow, a new pull request, and CI started on it.
remade ""
propose || { cat "$work/out"; fail "first proposal failed"; }
[ "$(git log --format=%ae%n%s -1 "origin/$BRANCH")" = "$(printf '%s\n%s' '41898282+github-actions[bot]@users.noreply.github.com' 'chore(readings): remake the JMdict word lists from JMdict 2000-02-01')" ] || fail "commit not the workflow's"
[ "$(git rev-list --count "origin/main..origin/$BRANCH")" = 1 ] || fail "not one commit on main"
grep -q "pr create --head $BRANCH --base main --title chore(readings): remake the JMdict word lists from JMdict 2000-02-01" "$FAKE/calls" || fail "no pull request opened"
[ "$(tail -n1 "$FAKE/calls")" = "workflow run ci.yml --ref $BRANCH" ] || fail "CI not started last"
grep -q "were from JMdict 2000-01-01" "$FAKE/body" || fail "body lacks the old date"
grep -q "jmdict-simplified \`$TAG\` (JMdict 2000-02-01)" "$FAKE/body" || fail "body lacks the release"
grep -q "2 files changed" "$FAKE/body" || fail "body lacks the diff"

# The same lists again, branch already on main: nothing to push, gh not asked.
git switch -q main && remade "" && rm "$FAKE/calls"
propose || fail "second run failed"
grep -q "already has the word lists" "$work/out" || fail "no notice for a branch already up to date"
[ -z "$(calls)" ] || fail "asked gh for a branch already up to date"

# Main moved on: the branch is made again from it, the open pull request edited, CI started again.
git checkout -q -- lists && echo readme > README && person add README && person commit -q -m "docs: more" && main_to_origin
remade "
月夜	つきよ"
propose || { cat "$work/out"; fail "update failed"; }
git merge-base --is-ancestor origin/main "origin/$BRANCH" || fail "branch not made from the new main"
[ "$(git rev-list --count "origin/main..origin/$BRANCH")" = 1 ] || fail "old commit kept"
grep -q "^pr edit 7 " "$FAKE/calls" || fail "open pull request not edited"
grep -q "^pr create" "$FAKE/calls" && fail "a second pull request opened"
[ "$(tail -n1 "$FAKE/calls")" = "workflow run ci.yml --ref $BRANCH" ] || fail "CI not started after the update"

# A person's commit on the branch: nothing is thrown away, and the run fails.
git switch -q "$BRANCH" && echo note > NOTE && person add NOTE && person commit -q -m "fix: by hand" && git push -q origin "$BRANCH"
held=$(git rev-parse HEAD)
git switch -q main && remade "
雨空	あまぞら" && rm "$FAKE/calls"
propose && fail "went on over a person's commit"
grep -q "has a commit by a person" "$work/out" || fail "no error for a person's commit"
[ "$(git ls-remote origin "refs/heads/$BRANCH" | cut -f1)" = "$held" ] || fail "a person's commit was thrown away"
[ -z "$(calls)" ] || fail "asked gh over a person's commit"
echo "propose.sh: all cases pass"
