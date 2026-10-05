#!/usr/bin/env bash
# ============================================================
# verify_central.sh — 校验本仓发布件在 Maven Central 上真的可拉
#
# 用法:
#   ./verify_central.sh                 # 用 pom 的 <revision>，验本仓全部构件
#   ./verify_central.sh 1.0.6           # 指定版本
#   VERIFY_SKIP_SIGNATURE=1 ./verify_central.sh   # 跳过签名检查
#
# 校验项（与原 z-middleware-integration-test 的 IT 等价）:
#   1. 每个构件的 .pom 可达
#   2. jar 构件额外要求 .jar / -sources.jar / -javadoc.jar / .asc 签名
#   3. 聚合 POM（packaging=pom）只要 .pom + .asc
#   4. 抽样构件的 POM metadata 完整（groupId/artifactId/version/name/license/scm/developers）
#   5. 抽样 jar 的 sources.jar 里含指定类
#
# 退出码: 0 全绿 / 1 有 FAIL
# ============================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 仓根 = 从本脚本所在目录向上找第一个带 pom.xml 的目录。
# 不用固定的 ../.. —— 脚本目录深度各仓不同（_doc/003_script 是两层，z-mcp/tools 是一层）。
REPO_ROOT="$SCRIPT_DIR"
while [ ! -f "$REPO_ROOT/pom.xml" ] && [ "$REPO_ROOT" != "/" ]; do
    REPO_ROOT="$(dirname "$REPO_ROOT")"
done
[ -f "$REPO_ROOT/pom.xml" ] || { echo "[verify] 找不到仓根 pom.xml（从 $SCRIPT_DIR 向上）"; exit 1; }
GROUP_ID="${VERIFY_GROUP_ID:-io.github.yuku123}"
CENTRAL="${CENTRAL_BASE:-https://repo1.maven.org/maven2}"
SKIP_SIG="${VERIFY_SKIP_SIGNATURE:-0}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}[verify]${NC} $1"; }
warn() { echo -e "${YELLOW}[verify]${NC} $1"; }
err()  { echo -e "${RED}[verify]${NC} $1"; FAIL=$((FAIL+1)); }

cd "$REPO_ROOT"

# ---------- 版本 ----------
FAIL=0   # 先初始化：报错路径上也要能记账，否则 set -u 直接崩、连「失败」都报不出来
TOTAL=0
VERSION="${1:-}"
if [ -z "$VERSION" ]; then
    VERSION=$(python3 - <<'PY'
import re, sys
try:
    s = open('pom.xml', encoding='utf-8').read()
except Exception:
    sys.exit(1)
# 优先 properties/revision，其次根 pom 的字面 <version>
m = re.search(r'<revision>([^<]+)</revision>', s)
if m:
    print(m.group(1).strip()); sys.exit(0)
# 无 ${revision} 的仓：取**根工程自己的** <version>，也就是 <artifactId>本工程</> 紧跟的那个。
# ⚠ 三个坑，逐个都真实踩过：
#   ① 不能取第一个 <version> —— 那会命中 <parent> 里的（z-graph: parent 1.0.21 /
#      根工程 1.0.8，误取 parent ⇒ 全表 404）
#   ② 有的仓根 pom 的 <version> 字面就是自引用 ${project.version}（z-graph / z-gw / z-kb
#      三个都是），字面解析到此为止 ⇒ 交给 mvn help:evaluate 兜底
#   ③ 解析结果若仍含 ${ 就不算版本，直接报，不让它去 Central 探一圈 404 回来
root_artifact = re.search(r'</parent>.*?<artifactId>([^<]+)</artifactId>', s, re.S)
if root_artifact:
    aid = re.escape(root_artifact.group(1).strip())
    m = re.search(r'</parent>.*?<artifactId>' + aid + r'</artifactId>\s*<version>([^<]+)</version>', s, re.S)
    if m and '${' not in m.group(1):
        print(m.group(1).strip()); sys.exit(0)
print('')   # 空 ⇒ 交给调用方走 mvn 兜底
PY
)
    # 字面解析不出来（自引用 ${project.version}）⇒ 问 Maven 自己要
    if [ -z "$VERSION" ]; then
        VERSION=$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1 | tr -d '[:space:]')
    fi
fi
[ -z "$VERSION" ] && { err "无法解析本仓版本（pom 字面与 mvn 都问不出），请显式传参：./verify_central.sh <version>"; exit 1; }
case "$VERSION" in
    *'${'*) err "版本解析得到的是未展开的串 ${VERSION}；请显式传参 ./verify_central.sh <version>"; exit 1 ;;
esac
log "仓: $(basename "$REPO_ROOT")  版本: $VERSION  groupId: $GROUP_ID"

# ---------- 构件清单 + packaging ----------
# 聚合 POM（packaging=pom）没有 jar；其余按 jar 处理。
read -r -d '' SPEC <<'PY' || true
PY
SPEC=$(python3 - "$VERSION" <<'PY'
import os, re, sys
import xml.etree.ElementTree as ET
NS = '{http://maven.apache.org/POM/4.0.0}'
ver = sys.argv[1]
root = ET.parse('pom.xml').getroot()
def t(e, tag):
    x = e.find(NS + tag)
    return (x.text or '').strip() if x is not None else ''
out = []
seen = set()
def scan(pom_path, module_hint, allow_dir_scan=True):
    rp = os.path.realpath(pom_path)
    if rp in seen:          # 防环 + 防重复
        return
    seen.add(rp)
    r = ET.parse(pom_path).getroot()
    aid = t(r, 'artifactId')
    pack = t(r, 'packaging') or 'jar'
    if aid and not aid.endswith('-parent'):
        out.append((aid, pack, module_hint))
    mods = [ (m.text or '').strip() for m in r.iter(NS + 'module') ]
    if mods:
        for m in mods:
            p = os.path.join(os.path.dirname(pom_path), m, 'pom.xml')
            if os.path.isfile(p):
                scan(p, m, allow_dir_scan=False)
    elif allow_dir_scan:
        # 无 <modules> 的仓（如 z-boot：每个子目录是独立可发工程）。
        # 只向下扫一层，且跳过 _ 前缀目录，避免顺着嵌套结构无限下钻。
        for name in sorted(os.listdir('.')):
            if name.startswith('.') or name.startswith('_') or name in ('target', 'node_modules'):
                continue
            p = os.path.join(name, 'pom.xml')
            if os.path.isdir(name) and os.path.isfile(p):
                scan(p, name, allow_dir_scan=False)
scan('pom.xml', '')
for aid, pack, _ in sorted(set(out)):
    print(f"{aid}\t{pack}")
PY
)
[ -z "$SPEC" ] && { err "未能从 pom.xml 解析出构件清单"; exit 1; }

# ---------- 探测 ----------
probe() {  # $1=url → http code
    # 只取 curl 的 %{http_code}。curl 自身失败（非 0 退出）时 stdout 可能仍带残缺输出，
    # 早期写法 `curl ... || echo 000` 会把两者粘在一起（真出现过 "200000" 这种 6 位码）
    # ⇒ 统一在这里收口，并把结果规整成 3 位。
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 30 "$1" 2>/dev/null)
    code=$(printf '%s' "$code" | grep -oE '^[0-9]{3}$' || true)
    printf '%s' "${code:-000}"
}
fetch() { curl -s --max-time 30 "$1" 2>/dev/null; }

FAIL=0; TOTAL=0
# 不发 Central 的构件（独立部署应用等）。用 verify-exclude.txt 逐行写 artifactId。
EXCLUDE_FILE="verify-exclude.txt"
is_excluded() {
    [ -f "$SCRIPT_DIR/$EXCLUDE_FILE" ] || return 1
    grep -qx "$1" "$SCRIPT_DIR/$EXCLUDE_FILE"
}
echo ""
echo "──────── 1. 构件可达性 ────────"
while IFS=$'\t' read -r aid pack; do
    [ -z "$aid" ] && continue
    if is_excluded "$aid"; then
        warn "⏭  $aid — 按 $EXCLUDE_FILE 声明不发 Central，跳过"
        continue
    fi
    TOTAL=$((TOTAL+1))
    base="$CENTRAL/${GROUP_ID//.//}/$aid/$VERSION/$aid-$VERSION"
    code=$(probe "$base.pom")
    if [ "$code" = "200" ]; then
        extra=""
        if [ "$pack" = "jar" ]; then
            for suf in .jar -sources.jar -javadoc.jar; do
                c=$(probe "$base$suf")
                [ "$c" = "200" ] || { extra="$extra $suf:$c"; }
            done
        fi
        if [ "$SKIP_SIG" != "1" ]; then
            c=$(probe "$base.pom.asc")
            [ "$c" = "200" ] || extra="$extra .pom.asc:$c"
        fi
        if [ -n "$extra" ]; then
            err "$aid — 缺失:$extra"
        else
            log "✅ $aid ($pack)"
        fi
    else
        err "$aid — .pom HTTP $code"
    fi
done <<< "$SPEC"

# ---------- 抽样 POM metadata ----------
echo ""
echo "──────── 2. POM metadata ────────"
SAMPLE=$(echo "$SPEC" | awk -F'\t' '$2=="jar"{print $1}' | while read -r a; do
    is_excluded "$a" || echo "$a"
done | head -1)
if [ -n "$SAMPLE" ]; then
    content=$(fetch "$CENTRAL/${GROUP_ID//.//}/$SAMPLE/$VERSION/$SAMPLE-$VERSION.pom")
    for tag in groupId artifactId version name license scm developers; do
        if echo "$content" | grep -q "<$tag>"; then
            log "✅ $SAMPLE 含 <$tag>"
        else
            err "$SAMPLE 缺 <$tag>（Central Portal 强制要求）"
        fi
    done
    echo "$content" | grep -q "<version>$VERSION</version>" \
        && log "✅ $SAMPLE version 与验的版本一致" \
        || err "$SAMPLE version 与 $VERSION 不一致"
else
    warn "无 jar 构件，跳过 POM metadata 抽检"
fi

# ---------- 抽样 sources.jar 含类 ----------
# 用 <artifactId>verify-classes.txt</> 声明要抽检的类（相对 sources.jar 内路径）。
CLASSPICK="verify-classes.txt"
SRCJAR=""
LISTING=""
CANDIDATE=""
if [ -f "$SCRIPT_DIR/$CLASSPICK" ]; then
    echo ""
    echo "──────── 3. sources.jar 内容抽检 ────────"
    # 逐行：跳过空行与 # 注释；每行必须能 cut 出 artifactId 与路径两段。
    while IFS= read -r cls || [ -n "$cls" ]; do
        cls="${cls%%$'\r'}"                 # 兼容 CRLF
        case "$cls" in
            ""|\#*) continue ;;
        esac
        aid=$(echo "$cls" | cut -d: -f1)
        path=$(echo "$cls" | cut -d: -f2-)
        aid="$(echo "$aid" | tr -d '[:space:]')"
        path="$(echo "$path" | tr -d '[:space:]')"
        if [ -z "$aid" ] || [ -z "$path" ] || [ "$aid" = "$cls" ]; then
            err "无法解析 $CLASSPICK 的一行（需为 <artifactId>:<类路径>）：$cls"
            continue
        fi
        if [ "$aid" != "$CANDIDATE" ]; then
            # 换了 artifactId ⇒ 换一份 sources.jar
            [ -n "$SRCJAR" ] && rm -f "$SRCJAR" 2>/dev/null
            CANDIDATE="$aid"
            # unzip -l 要求可 seek 的文件，进程替换（/dev/fd/N）会报
            # "End-of-central-directory signature not found" ⇒ 先落临时文件。
            SRCJAR=$(mktemp -t verify-sources.XXXXXX.jar)
            curl -s --max-time 60 -o "$SRCJAR" \
                "$CENTRAL/${GROUP_ID//.//}/$CANDIDATE/$VERSION/$CANDIDATE-$VERSION-sources.jar"
            LISTING=$(unzip -l "$SRCJAR" 2>/dev/null)
            if [ -z "$LISTING" ]; then
                err "$CANDIDATE sources.jar 下载或读取失败（判据本身坏了，该结论不作数）"
                SRCJAR=""; LISTING=""
            fi
        fi
        [ -z "$SRCJAR" ] && continue
        if echo "$LISTING" | grep -q " $path\$"; then
            log "✅ $path 在 $aid sources.jar 中"
        else
            err "$path 不在 $aid sources.jar 中（改名后包路径可能未同步）"
        fi
    done < "$SCRIPT_DIR/$CLASSPICK"
    [ -n "$SRCJAR" ] && rm -f "$SRCJAR" 2>/dev/null
else
    warn "无 ${CLASSPICK}，跳过 sources.jar 抽检；可新建该文件逐行写 <artifactId>:<类路径>"
fi

# ---------- 收口 ----------
echo ""
if [ "$FAIL" -eq 0 ]; then
    log "✅ 全绿：$TOTAL 个构件在 Central 上均可拉，版本 $VERSION"
    exit 0
else
    err "❌ $FAIL 项失败 / 共 $TOTAL 个构件"
    exit 1
fi
