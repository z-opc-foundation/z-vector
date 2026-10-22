#!/usr/bin/env bash
# ============================================================
# run_wal_mode_benchmark.sh — WAL 落盘模式的写吞吐对照（before / after）
#
# 回答一个问题：「ack 即持久」这条契约，代价是多少吞吐？
#   before = ASYNC（fd25a8d 之前的默认行为：入队即返回、后台 group commit）
#   after  = SYNC （现默认：每条记录一次 write + 一次 fsync，ack 即持久 RPO = 0）
#
# 用法:
#   ./_doc/003_script/run_wal_mode_benchmark.sh
#   MVN="mvn -o" ./_doc/003_script/run_wal_mode_benchmark.sh     # 离线跑
#
# 退出码:
#   0 跑完了（结果单已落盘）
#   1 基准跑失败（看日志）
#   2 环境问题（找不到 mvn）
#
# 产物: z-vector-storage/target/wal-mode-benchmark/report.{json,md}
# ============================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR"
while [ ! -f "$REPO_ROOT/pom.xml" ] && [ "$REPO_ROOT" != "/" ]; do
    REPO_ROOT="$(dirname "$REPO_ROOT")"
done
[ -f "$REPO_ROOT/pom.xml" ] || { echo "[bench] 找不到仓根 pom.xml（从 $SCRIPT_DIR 向上）"; exit 2; }

MVN="${MVN:-mvn}"
TEST_CLASS="WalModeThroughputBenchmarkTest"
REPORT_DIR="$REPO_ROOT/z-vector-storage/target/wal-mode-benchmark"

echo "[bench] 仓根: $REPO_ROOT"
echo "[bench] 跑基准: ${TEST_CLASS}（before=ASYNC / after=SYNC）"

cd "$REPO_ROOT" || exit 2
# shellcheck disable=SC2086
$MVN -pl z-vector-storage test \
    -Dtest="$TEST_CLASS" \
    -DfailIfNoTests=false \
    2>&1 | tee /tmp/zvector-wal-mode-benchmark.log
MVN_RC="${PIPESTATUS[0]}"

REPORT_MD="$REPORT_DIR/report.md"
REPORT_JSON="$REPORT_DIR/report.json"
if [ ! -f "$REPORT_JSON" ]; then
    echo "[bench] 没有结果单（$REPORT_JSON）—— 基准没跑起来，看上面的日志"
    exit 2
fi

echo "[bench] 结果单: $REPORT_JSON"
if [ -f "$REPORT_MD" ]; then
    echo "----------------------------------------"
    cat "$REPORT_MD"
    echo "----------------------------------------"
fi

if [ "$MVN_RC" != "0" ]; then
    echo "[bench] 🔴 maven 退出码 $MVN_RC（详见上面的日志）"
    exit 1
fi
echo "[bench] 🟢 基准跑完"
exit 0
