#!/bin/bash
FAILURES=""
PASSED=true
run_gate() {
  local NAME=$1; shift
  echo "[Gate] $NAME..." >&2
  OUT=$("$@" 2>&1) || { PASSED=false; FAILURES="${FAILURES}\n\n❌ ${NAME}:\n${OUT}"; }
  [ "$PASSED" = true ] && echo "[Gate] ✅ $NAME" >&2
}

# ── Find a base ref to diff against, so style/static-analysis gates only ──────
# cover files this ticket actually touched, not pre-existing repo-wide debt.
# Prefer the project's configured baseBranch (the same branch PRs target) over
# guessing, so this can't disagree with what the PR actually diffs against.
CONFIGURED_BASE_BRANCH=$(python3 -c "import json; print(json.load(open('.claude/pipeline-config.json')).get('baseBranch',''))" 2>/dev/null)

BASE_REF=""
for CANDIDATE in "origin/$CONFIGURED_BASE_BRANCH" "$CONFIGURED_BASE_BRANCH" origin/develop origin/main origin/master develop main master; do
  [ -z "$CANDIDATE" ] || [ "$CANDIDATE" = "origin/" ] && continue
  if git rev-parse --verify -q "$CANDIDATE" >/dev/null 2>&1; then
    MERGE_BASE=$(git merge-base HEAD "$CANDIDATE" 2>/dev/null) && [ -n "$MERGE_BASE" ] && { BASE_REF="$MERGE_BASE"; break; }
  fi
done

CHANGED_JAVA_FILES=""
if [ -n "$BASE_REF" ]; then
  CHANGED_JAVA_FILES=$(git diff --name-only --diff-filter=ACMR "$BASE_REF" -- '*.java')
fi

run_gate "Unit Tests"        ./mvnw test -q
run_gate "Integration Tests" ./mvnw verify -q -DskipUnitTests

if [ -n "$CHANGED_JAVA_FILES" ]; then
  CHECKSTYLE_INCLUDES=$(echo "$CHANGED_JAVA_FILES" | sed -E 's#^src/(main|test)/java/##' | paste -sd, -)
  SPOTBUGS_CLASSES=$(echo "$CHANGED_JAVA_FILES" | sed -E 's#^src/(main|test)/java/##; s#\.java$##; s#/#.#g' | paste -sd, -)
  run_gate "Checkstyle (changed files)" ./mvnw checkstyle:check -q "-Dcheckstyle.includes=$CHECKSTYLE_INCLUDES"
  run_gate "SpotBugs (changed files)"   ./mvnw spotbugs:check -q "-Dspotbugs.onlyAnalyze=$SPOTBUGS_CLASSES"
else
  echo "[Gate] No changed .java files vs base branch — skipping Checkstyle/SpotBugs" >&2
fi

if [ "$PASSED" = true ]; then
  echo "[Gate] ✅ ALL PASSED" >&2
  echo '{"decision": "allow"}'; exit 0
fi
MSG="Fix ALL gate failures:\n${FAILURES}\nDo NOT skip failing tests."
ESCAPED=$(echo -e "$MSG" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")
echo "{\"decision\": \"block\", \"reason\": $ESCAPED}"
