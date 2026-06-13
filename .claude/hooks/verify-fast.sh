#!/usr/bin/env bash
# hook 전용 빠른 검증 (Stop 이벤트마다 실행)
# 1~2초 안에 끝나는 grep 3개만 포함. ./gradlew test는 제외.
# 전체 체크리스트(테스트 포함)는 수동 /verify 또는 subagent에서 실행.

cd "$(git rev-parse --show-toplevel 2>/dev/null || echo .)"

PASS=0
FAIL=0
OUT=""

# 1. TODO 탐지
TODO_HITS=$(grep -rn "TODO" src/main --include="*.java" \
  | grep -v "src/main/java/com/readum/.*/example/" 2>/dev/null)
if [ -z "$TODO_HITS" ]; then
  OUT+="  TODO       ✅ 없음\n"
else
  COUNT=$(echo "$TODO_HITS" | wc -l | tr -d ' ')
  OUT+="  TODO       ❌ ${COUNT}건\n"
  while IFS= read -r line; do
    OUT+="    $line\n"
  done <<< "$TODO_HITS"
  FAIL=$((FAIL + 1))
fi

# 2. @Value 탐지 (domain/ + presentation/)
VALUE_HITS=$(grep -rn "@Value" src/main --include="*.java" \
  | grep -E "src/main/java/com/readum/(domain|presentation)/" \
  | grep -v "src/main/java/com/readum/.*/example/" 2>/dev/null)
if [ -z "$VALUE_HITS" ]; then
  OUT+="  @Value     ✅ 없음\n"
else
  COUNT=$(echo "$VALUE_HITS" | wc -l | tr -d ' ')
  OUT+="  @Value     ⚠️  ${COUNT}건\n"
  while IFS= read -r line; do
    OUT+="    $line\n"
  done <<< "$VALUE_HITS"
fi

# 3. Service/Repository 대응 테스트 클래스 존재 여부
MISSING=$(comm -23 \
  <(find src/main/java -name "*Service.java" -o -name "*Repository.java" \
      | grep -v "src/main/java/com/readum/.*/example/" \
      | sed 's|src/main/java/||; s|\.java$||' | sort) \
  <(find src/test/java -name "*ServiceTest.java" -o -name "*RepositoryTest.java" \
      | sed 's|src/test/java/||; s|Test\.java$||' | sort) 2>/dev/null)
if [ -z "$MISSING" ]; then
  OUT+="  누락 테스트  ✅ 없음\n"
else
  COUNT=$(echo "$MISSING" | wc -l | tr -d ' ')
  OUT+="  누락 테스트  ❌ ${COUNT}건\n"
  while IFS= read -r line; do
    OUT+="    $line\n"
  done <<< "$MISSING"
  FAIL=$((FAIL + 1))
fi

# 결과 출력 (이상 있을 때만 표시해 노이즈 최소화)
if [ $FAIL -gt 0 ] || [ -n "$VALUE_HITS" ]; then
  echo ""
  echo "── /verify (fast) ──────────────────────"
  echo -e "$OUT"
  echo "(./gradlew test 는 /verify 수동 실행에서 확인)"
  echo "────────────────────────────────────────"
fi
