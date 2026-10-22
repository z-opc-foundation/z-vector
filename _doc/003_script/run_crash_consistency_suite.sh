#!/usr/bin/env bash
# ============================================================
# run_crash_consistency_suite.sh — 跑 z-vector 崩溃恢复一致性验证套件
#
# 验的是「持久化是真的」：写入 N 条 → 从另一个 JVM 里 kill -9 → 重启 → 三口径对账
#   ① collection 计数   ② HNSW 图连通性（可查性，不是文件在不在）   ③ payload 索引
# 时点矩阵：checkpoint 前 / checkpoint 后 / WAL 半途（含确定性半截尾）
#
# 用法:
#   ./_doc/003_script/run_crash_consistency_suite.sh            # 三时点矩阵必须全绿
#   ./_doc/003_script/run_crash_consistency_suite.sh --strict   # 连「已 ack 写入的持久性」探针也必须绿
#   MVN="mvn -o" ./_doc/003_script/run_crash_consistency_suite.sh   # 离线跑
#
# CI 挂载（示例）:
#   - name: z-vector crash-recovery consistency
#     run: ./_doc/003_script/run_crash_consistency_suite.sh
#   - name: archive report
#     if: always()
#     uses: actions/upload-artifact@v4
#     with:
#       name: crash-consistency-report
#       path: z-vector-storage/target/crash-consistency/
#
# 退出码:
#   0 三时点矩阵全绿（--strict 下要求探针也绿）
#   1 有红点（矩阵红 ⇒ 真缺陷；探针红 ⇒ 已知缺陷，见结果单 findings 与修复卡）
#   2 环境问题（找不到 mvn / 套件没跑起来）
#
# 产物: z-vector-storage/target/crash-consistency/report.{json,md}
# ============================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR"
while [ ! -f "$REPO_ROOT/pom.xml" ] && [ "$REPO_ROOT" != "/" ]; do
    REPO_ROOT="$(dirname "$REPO_ROOT")"
done
[ -f "$REPO_ROOT/pom.xml" ] || { echo "[crash] 找不到仓根 pom.xml（从 $SCRIPT_DIR 向上）"; exit 2; }

MVN="${MVN:-mvn}"
STRICT=0
for arg in "$@"; do
    case "$arg" in
        --strict) STRICT=1 ;;
        *) echo "[crash] 未知参数: $arg"; exit 2 ;;
    esac
done

REPORT_DIR="$REPO_ROOT/z-vector-storage/target/crash-consistency"
TEST_CLASS="CrashRecoveryConsistencyTest"

echo "[crash] 仓根: $REPO_ROOT"
echo "[crash] 跑套件: ${TEST_CLASS}（strict=${STRICT}）"

cd "$REPO_ROOT" || exit 2
# shellcheck disable=SC2086
$MVN -pl z-vector-storage test \
    -Dtest="$TEST_CLASS" \
    -DfailIfNoTests=false \
    -Dcrash.strict.durability="$( [ "$STRICT" = "1" ] && echo true || echo false )" \
    2>&1 | tee /tmp/zvector-crash-suite.log
MVN_RC="${PIPESTATUS[0]}"

REPORT_JSON="$REPORT_DIR/report.json"
if [ ! -f "$REPORT_JSON" ]; then
    echo "[crash] 没有结果单（$REPORT_JSON）—— 套件没跑起来，看上面的日志"
    exit 2
fi

echo "[crash] 结果单: $REPORT_JSON"
if command -v python3 >/dev/null 2>&1; then
    python3 - "$REPORT_JSON" "$STRICT" <<'PY'
import json
import sys

path, strict = sys.argv[1], sys.argv[2] == "1"
with open(path, encoding="utf-8") as f:
    report = json.load(f)

MATRIX = {"PRE_CHECKPOINT", "POST_CHECKPOINT", "MID_WAL", "MID_WAL_TORN"}
matrix_red, probe_red = [], []
for sc in report.get("scenarios", []):
    reds = [m for m in sc.get("metrics", []) if m.get("status") == "RED"]
    if not reds:
        continue
    if sc.get("name") in MATRIX:
        matrix_red.append((sc["name"], reds))
    else:
        probe_red.append((sc["name"], reds))

print("[crash] ---- 判定明细 ----")
for sc in report.get("scenarios", []):
    print("[crash] %-26s %s" % (sc.get("name"), sc.get("timing")))
    for m in sc.get("metrics", []):
        print("[crash]   %s %-24s 期望=%s 实际=%s" % (
            m.get("status"), m.get("name"), m.get("expected"), m.get("actual")))
for f in report.get("findings", []):
    print("[crash] 发现: %s" % f)

if matrix_red:
    print("[crash] 🔴 三时点矩阵有红点 ⇒ 崩溃恢复一致性被打破（真缺陷）")
    sys.exit(1)
if probe_red and strict:
    print("[crash] 🔴 探针有红点（--strict）")
    sys.exit(1)
if probe_red:
    print("[crash] 🟡 探针有红点（非 --strict ⇒ 不阻断 CI；见结果单 findings 与修复卡）")
print("[crash] 🟢 三时点矩阵全绿")
sys.exit(0)
PY
    PY_RC=$?
    if [ "$PY_RC" != "0" ]; then
        exit "$PY_RC"
    fi
fi

# 没有 python3 ⇒ 只能看 maven 的退出码（严格模式下 maven 侧也会红）
if [ "$MVN_RC" != "0" ]; then
    echo "[crash] 🔴 maven 退出码 $MVN_RC（详见上面的日志）"
    exit 1
fi
echo "[crash] 🟢 套件通过"
exit 0
