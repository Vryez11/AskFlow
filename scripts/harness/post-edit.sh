#!/bin/bash
# PostToolUse 훅 (Edit, Write, MultiEdit). 검증 하네스: conventions/harness.md 2-3
# web의 TS·JS 파일을 고치면 그 파일만 oxlint로 고치고 검사한다.
# Kotlin 린트는 Gradle을 거쳐 느리므로 Stop 훅에서 한 번에 돌린다.
set -euo pipefail

input=$(cat)
file=$(jq -r '.tool_input.file_path // empty' <<<"$input")
[ -n "$file" ] || exit 0

root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"
rel="${file#"$root"/}"

case "$rel" in
  web/*.ts | web/*.tsx | web/*.js | web/*.jsx) ;;
  *) exit 0 ;;
esac

oxlint="$root/web/node_modules/.bin/oxlint"
[ -x "$oxlint" ] || exit 0

if ! output=$(cd "$root/web" && "$oxlint" --fix --deny-warnings "${rel#web/}" 2>&1); then
  printf '%s의 린트 오류를 고치세요.\n%s\n' "$rel" "$output" >&2
  exit 2
fi
