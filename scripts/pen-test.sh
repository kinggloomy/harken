#!/usr/bin/env bash
# ============================================================================
# pen-test.sh —— 渗透自测四类用例（M5 #8）
#
#   A. 重放：抓到一个合法请求再发一次，能不能成
#   B. 越权：AI 通道能不能变成写通道；只读账号能不能提交；匿名能不能访问
#   C. IP 绕过：伪造 X-Forwarded-For / 直连能不能绕过白名单
#   D. 守卫逃逸：能不能用 DDL、黑名单表、批量更新绕过写入守卫
#
# 「拦住的和没拦住的都写进 SECURITY.md」是这一步的硬要求 —— 所以本脚本里
# 有两条刻意标记为 KNOWN-GAP 的用例（不判失败，只输出事实），
# 它们代表设计上选择不拦的部分。
#
# 用法（在仓库根目录）：
#   mvn -q -DskipTests package
#   bash scripts/pen-test.sh
#
# 端口：18080（普通）/ 18081（开启签名）/ 18082（白名单只放 10.9.9.9）
# ============================================================================
set -u

cd "$(dirname "$0")/.." || exit 2
JAR="harken-demo/target/harken-demo-1.0.0-SNAPSHOT.jar"
JAVA_BIN="${HARKEN_JAVA:-java}"

if [ ! -f "$JAR" ]; then
    echo "错误：找不到 $JAR。先执行：mvn -q -DskipTests package" >&2
    exit 2
fi

P1=18080   # 普通实例
P2=18081   # 开启签名
P3=18082   # 白名单只放 10.9.9.9
BASE1="http://127.0.0.1:$P1"
BASE2="http://127.0.0.1:$P2"
BASE3="http://127.0.0.1:$P3"

AI_TOKEN="pen-ai-token"
SIGN_SECRET="pen-signing-secret-0123456789"

pass=0
fail=0
gap=0

say()  { printf '\n\033[1m%s\033[0m\n' "$1"; }
ok()   { pass=$((pass + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
no()   { fail=$((fail + 1)); printf '  \033[31mFAIL\033[0m %s\n       %s\n' "$1" "$2"; }
known(){ gap=$((gap + 1)); printf '  \033[33mKNOWN-GAP\033[0m %s\n       %s\n' "$1" "$2"; }

cleanup() {
    for pid in ${PIDS:-}; do kill -9 "$pid" 2>/dev/null; done
    for p in $P1 $P2 $P3; do
        # 只杀自家端口；8090 等别人的端口一概不碰
        fuser -k "${p}/tcp" 2>/dev/null
    done
}
trap cleanup EXIT

start() {
    local port=$1; shift
    nohup "$JAVA_BIN" -Dfile.encoding=UTF-8 -jar "$JAR" \
        --server.port="$port" --demo.exit-after=false "$@" \
        > "/tmp/pen-$port.log" 2>&1 < /dev/null &
    PIDS="${PIDS:-} $!"
}

wait_up() {
    local url=$1 name=$2
    for _ in $(seq 1 90); do
        local code
        code=$(curl -s -o /dev/null -m 2 -w '%{http_code}' "$url")
        [ "$code" != "000" ] && { echo "  $name 就绪 (http $code)"; return 0; }
        sleep 1
    done
    echo "  $name 启动失败，见 /tmp/pen-$3.log" >&2
    return 1
}

echo "=== 启动三个实例 ==="
start $P1 --harken.ai.token=$AI_TOKEN
start $P2 --harken.ai.token=$AI_TOKEN --harken.ai.signing-secret=$SIGN_SECRET
start $P3 --harken.web.allowed-ips=10.9.9.9 --harken.ai.token=$AI_TOKEN
wait_up "$BASE1/harken/api/health" "普通实例" $P1 || exit 2
wait_up "$BASE2/harken/api/health" "签名实例" $P2 || exit 2
wait_up "$BASE3/harken/api/health" "白名单实例" $P3 || exit 2

# ---------------------------------------------------------------------------
# 登录（人类通道）
# ---------------------------------------------------------------------------
LOGIN=$(curl -s -m 10 -X POST "$BASE1/harken/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"demo-admin-pw"}')
TOKEN=$(printf '%s' "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then
    echo "登录失败，无法继续：$LOGIN" >&2
    exit 2
fi
SESS="X-Harken-Session: $TOKEN"

# ---------------------------------------------------------------------------
# 签名工具（与服务端逐字节一致）
# ---------------------------------------------------------------------------
sha256_hex() { printf '%s' "$1" | openssl dgst -sha256 -hex 2>/dev/null | awk '{print $NF}'; }
hmac_hex()   { printf '%s' "$2" | openssl dgst -sha256 -hmac "$1" -hex 2>/dev/null | awk '{print $NF}'; }

# sig_query <timestamp> <nonce> <body>  → 打印签名
sig_query() {
    local canonical
    canonical=$(printf '%s\n%s\n%s\n%s\n%s' "POST" "/harken/ai/query" "$1" "$2" "$(sha256_hex "$3")")
    hmac_hex "$SIGN_SECRET" "$canonical"
}

# 签名请求：结果写入全局 CODE / RESP。
# 不用 $(ai_signed ...) 取返回值 —— 命令替换会开子 shell，函数里赋的变量传不回来。
CODE=""
RESP=""
ai_signed() {
    local ts=$1 nonce=$2 body=$3 sig raw
    sig=$(sig_query "$ts" "$nonce" "$body")
    raw=$(curl -s -w '\n%{http_code}' -m 20 -X POST "$BASE2/harken/ai/query" \
        -H "Authorization: Bearer $AI_TOKEN" \
        -H "X-Harken-Timestamp: $ts" \
        -H "X-Harken-Nonce: $nonce" \
        -H "X-Harken-Signature: $sig" \
        -H 'Content-Type: application/json' \
        --data-binary "$body")
    CODE=$(printf '%s' "$raw" | tail -1)
    RESP=$(printf '%s' "$raw" | sed '$d')
}

errtype() { printf '%s' "$1" | sed -n 's/.*"errorType":"\([^"]*\)".*/\1/p'; }

# ===========================================================================
say "A. 重放（配了签名密钥的实例）"
# ===========================================================================
TS=$(date +%s)
NONCE="pen-nonce-$(date +%s%N | cut -c1-12)"
BODY='{"script":"orderService.count()"}'

ai_signed "$TS" "$NONCE" "$BODY"
R="$RESP"
[ "$CODE" = "200" ] && ok "A1 带正确签名的请求通过（$CODE）" \
                     || no "A1 带正确签名的请求通过" "实际 $CODE, $R"

ai_signed "$TS" "$NONCE" "$BODY"
R="$RESP"
if [ "$CODE" = "401" ] && [ "$(errtype "$R")" = "HARKEN_SIGNATURE_REPLAY" ]; then
    ok "A2 同一请求重发第二次被拒（HARKEN_SIGNATURE_REPLAY）"
else
    no "A2 同一请求重发第二次被拒" "实际 $CODE, $(errtype "$R")"
fi

# 篡改 body，签名不变
TS=$(date +%s); NONCE="pen-nonce-$(date +%s%N | cut -c1-12)"
SIG=$(sig_query "$TS" "$NONCE" "$BODY")
R=$(curl -s -m 20 -X POST "$BASE2/harken/ai/query" \
    -H "Authorization: Bearer $AI_TOKEN" \
    -H "X-Harken-Timestamp: $TS" -H "X-Harken-Nonce: $NONCE" \
    -H "X-Harken-Signature: $SIG" -H 'Content-Type: application/json' \
    --data-binary '{"script":"orderService.markAllPaid()"}')
if printf '%s' "$R" | grep -q 'HARKEN_SIGNATURE_INVALID'; then
    ok "A3 篡改 body 但沿用旧签名被拒（防篡改）"
else
    no "A3 篡改 body 但沿用旧签名被拒" "$R"
fi

# 不带任何签名头
R=$(curl -s -m 20 -X POST "$BASE2/harken/ai/query" \
    -H "Authorization: Bearer $AI_TOKEN" -H 'Content-Type: application/json' \
    --data-binary "$BODY")
printf '%s' "$R" | grep -q 'HARKEN_SIGNATURE_REQUIRED' \
    && ok "A4 缺签名头被拒（HARKEN_SIGNATURE_REQUIRED）" \
    || no "A4 缺签名头被拒" "$R"

# 过期时间戳（1 小时前），签名本身正确
OLD=$(( $(date +%s) - 3600 ))
NONCE="pen-nonce-$(date +%s%N | cut -c1-12)"
ai_signed "$OLD" "$NONCE" "$BODY"
R="$RESP"
if [ "$CODE" = "401" ] && [ "$(errtype "$R")" = "HARKEN_SIGNATURE_STALE" ]; then
    ok "A5 一小时前的请求被拒（HARKEN_SIGNATURE_STALE）"
else
    no "A5 一小时前的请求被拒" "实际 $CODE, $(errtype "$R")"
fi

# ===========================================================================
say "B. 越权"
# ===========================================================================
# B1 AI Token 拿去调人类通道
R=$(curl -s -m 10 -X POST "$BASE1/harken/api/execute" \
    -H "Authorization: Bearer $AI_TOKEN" -H 'Content-Type: application/json' \
    -d '{"script":"orderService.count()","commit":false}')
printf '%s' "$R" | grep -q 'HARKEN_UNAUTHORIZED\|HARKEN_UNAUTHENTICATED\|未登录\|会话' \
    && ok "B1 AI Token 无法调用人类通道" || no "B1 AI Token 无法调用人类通道" "$R"

# B2 人类会话拿去调 AI 通道
R=$(curl -s -m 10 -X POST "$BASE1/harken/ai/query" \
    -H "$SESS" -H 'Content-Type: application/json' \
    -d '{"script":"orderService.count()"}')
printf '%s' "$R" | grep -q 'HARKEN_AI_UNAUTHORIZED' \
    && ok "B2 人类会话无法调用 AI 通道" || no "B2 人类会话无法调用 AI 通道" "$R"

# B3 AI 通道试图提交事务
R=$(curl -s -m 10 -X POST "$BASE1/harken/ai/query" \
    -H "Authorization: Bearer $AI_TOKEN" -H 'Content-Type: application/json' \
    -d '{"script":"orderService.count()","commit":true}')
printf '%s' "$R" | grep -q 'commit' \
    && ok "B3 AI 通道传 commit 被明确拒绝（不是静默忽略）" || no "B3 AI 通道传 commit 被明确拒绝" "$R"

# B4 只读账号提交事务
RL=$(curl -s -m 10 -X POST "$BASE1/harken/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"reader","password":"demo-reader-pw"}')
RTOKEN=$(printf '%s' "$RL" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -n "$RTOKEN" ]; then
    R=$(curl -s -m 10 -X POST "$BASE1/harken/api/execute" \
        -H "X-Harken-Session: $RTOKEN" -H 'Content-Type: application/json' \
        -d '{"script":"orderService.markPaid(\"SO-1001\")","commit":true}')
    printf '%s' "$R" | grep -q 'HARKEN_FORBIDDEN_COMMIT' \
        && ok "B4 READONLY 账号 commit=true 被拒（HARKEN_FORBIDDEN_COMMIT）" \
        || no "B4 READONLY 账号 commit=true 被拒" "$R"
else
    known "B4 READONLY 账号 commit=true 被拒" "demo 未配置 reader 账号，跳过"
fi

# B5 匿名访问
R=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$BASE1/harken/api/catalog")
[ "$R" = "401" ] && ok "B5 匿名访问 /api/catalog 返回 401" || no "B5 匿名访问 /api/catalog 返回 401" "实际 $R"

# ===========================================================================
say "C. IP 绕过（白名单实例只允许 10.9.9.9）"
# ===========================================================================
R=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$BASE3/harken/")
[ "$R" = "403" ] && ok "C1 非白名单来源直连被拒（403）" || no "C1 非白名单来源直连被拒" "实际 $R"

# 伪造 X-Forwarded-For 试图冒充白名单地址（trust-forwarded-for 默认关闭）
R=$(curl -s -o /dev/null -w '%{http_code}' -m 10 \
    -H 'X-Forwarded-For: 10.9.9.9' -H 'X-Real-IP: 10.9.9.9' "$BASE3/harken/")
[ "$R" = "403" ] && ok "C2 伪造 X-Forwarded-For 无法绕过（默认不信任该头）" \
                 || no "C2 伪造 X-Forwarded-For 无法绕过" "实际 $R"

# 恶意 Origin 跨站提交
R=$(curl -s -m 10 -X POST "$BASE1/harken/api/execute" \
    -H "$SESS" -H 'Origin: http://evil.example' -H 'Content-Type: application/json' \
    -d '{"script":"orderService.count()","commit":false}')
printf '%s' "$R" | grep -q 'HARKEN_CROSS_ORIGIN' \
    && ok "C3 跨站 Origin 被拒（HARKEN_CROSS_ORIGIN）" || no "C3 跨站 Origin 被拒" "$R"

known "C3' 一旦开启 trust-forwarded-for，该头就是身份" \
      "反代场景必须由 nginx 覆盖写入，否则任何客户端都能自称白名单地址（设计取舍，非缺陷）"

# ===========================================================================
say "D. 写入守卫逃逸"
# ===========================================================================
D1=$(curl -s -m 20 -X POST "$BASE1/harken/api/execute" -H "$SESS" \
    -H 'Content-Type: application/json' \
    -d '{"script":"orderService.createArchiveTable()","commit":false}')
printf '%s' "$D1" | grep -q 'HARKEN_DDL_FORBIDDEN' \
    && ok "D1 DDL 逃逸（create table）被拒（HARKEN_DDL_FORBIDDEN）" \
    || no "D1 DDL 逃逸被拒" "$D1"

D2=$(curl -s -m 20 -X POST "$BASE1/harken/api/execute" -H "$SESS" \
    -H 'Content-Type: application/json' \
    -d '{"script":"orderService.closeAllSupplyOrders()","commit":false}')
printf '%s' "$D2" | grep -q 'HARKEN_TABLE_FORBIDDEN' \
    && ok "D2 触碰黑名单表 supply_order 被拒（HARKEN_TABLE_FORBIDDEN）" \
    || no "D2 触碰黑名单表被拒" "$D2"

D3=$(curl -s -m 20 -X POST "$BASE1/harken/api/execute" -H "$SESS" \
    -H 'Content-Type: application/json' \
    -d '{"script":"orderService.markAllPaid()","commit":false}')
printf '%s' "$D3" | grep -q 'HARKEN_ROW_LIMIT' \
    && ok "D3 批量更新超行数上限被拒（HARKEN_ROW_LIMIT）" \
    || no "D3 批量更新超行数上限被拒" "$D3"

# 危险 API：设计上只标黄不拦截，这里如实记录「没拦住的是哪一类」
D4=$(curl -s -m 20 -X POST "$BASE1/harken/api/execute" -H "$SESS" \
    -H 'Content-Type: application/json' \
    -d '{"script":"new File(\"/etc/hostname\").text","commit":false}')
if printf '%s' "$D4" | grep -q '"ok":true'; then
    known "D4 危险 API（读文件）不被拦截" \
          "按设计只在前端标黄提示（SR-07：提示不阻止）。运行期边界靠事务回滚 + 行数闸 + 表黑名单，不靠 API 白名单"
else
    ok "D4 危险 API（读文件）被执行层拒绝（比设计更严）"
fi

known "D5 动态拼接 SQL 的表名识别" \
      "SqlInspector 是轻量词法分析，不做数据流追踪：拼接出来的表名识别不到（已写在类注释与 SECURITY.md）"

# ===========================================================================
printf '\n==============================================================\n'
printf '通过 %d 项，失败 %d 项，已知缺口 %d 项\n' "$pass" "$fail" "$gap"
printf '==============================================================\n'
[ "$fail" -eq 0 ] || exit 1
