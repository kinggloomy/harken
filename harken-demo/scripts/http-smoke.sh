#!/usr/bin/env bash
# ============================================================================
# Harken HTTP 冒烟测试
#
# 用法：
#   1. 先启动 demo（另开一个终端）：
#        java -jar harken-demo/target/harken-demo-1.0.0-SNAPSHOT.jar --server.port=18080
#   2. 再跑本脚本：
#        bash harken-demo/scripts/http-smoke.sh
#        bash harken-demo/scripts/http-smoke.sh http://127.0.0.1:18080
#
# 覆盖的是 M2 的验收清单：401 / 403 / 429 / 只读角色 / 试跑回滚 / 提交落库 /
# AI 通道隔离 / 语法错误 / 未知路径 / 重载踢会话。
#
# 只用 curl + sed，不依赖 jq —— 目标机器上有什么就用什么。
# ============================================================================
set -u

BASE="${1:-http://127.0.0.1:18080}"
H="$BASE/harken"
pass=0
fail=0

say() { printf '\n%s\n' "$1"; }
ok() { pass=$((pass + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
no() {
    fail=$((fail + 1))
    printf '  \033[31mFAIL\033[0m %s\n       expect=%s actual=%s\n       body=%s\n' \
        "$1" "$2" "$3" "$4"
}

CODE=""
BODY=""
HEADERS=""

# 一次请求：把状态码与响应体分别放进 CODE / BODY。
# 用 curl -i 一次性取回「头 + 体」再切分，刻意不落临时文件 ——
# 少两个文件、少一次清理，也就少一类「清理失败把脚本搞挂」的故障。
req() {
    local method=$1 url=$2
    shift 2
    local resp
    resp=$(curl -s -i -m 20 -X "$method" "$url" "$@")
    HEADERS=$(printf '%s' "$resp" | sed -n '1,/^[[:space:]]*$/p')
    CODE=$(printf '%s' "$resp" | head -1 | tr -d '\r' | awk '{print $2}')
    BODY=$(printf '%s\n' "$resp" | sed '1,/^[[:space:]]*$/d')
}

# 从 JSON 里取字符串字段（respond 体都很小，sed 足够）
field() { printf '%s' "$BODY" | sed -n 's/.*"'"$2"'":"\([^"]*\)".*/\1/p'; }

expect_code() {
    local desc=$1 want=$2
    if [ "$CODE" = "$want" ]; then ok "$desc"; else no "$desc" "$want" "$CODE" "$BODY"; fi
}

expect_contains() {
    local desc=$1 needle=$2 hay=${3:-$BODY}
    case "$hay" in
        *"$needle"*) ok "$desc" ;;
        *) no "$desc" "包含 $needle" "未命中" "$hay" ;;
    esac
}

# 断言响应体里**不**含某段文本。「索引给名字、方法按需取」这类契约
# 只有反向断言才能锁住：光验「有 markPaid」区分不了「索引里就有」还是「问了才给」。
expect_not_contains() {
    local desc=$1 needle=$2 hay=${3:-$BODY}
    case "$hay" in
        *"$needle"*) no "$desc" "不含 $needle" "包含了" "$hay" ;;
        *) ok "$desc" ;;
    esac
}


# 断言响应头里包含某段文本（大小写不敏感）。
# 控制台的 CSP / X-Frame-Options 这类硬性安全头必须由脚本验，不能只写在文档里。
expect_header() {
    local desc=$1 name=$2 needle=$3
    if printf '%s' "$HEADERS" | grep -qi "^$name:.*$needle"; then
        ok "$desc"
    else
        no "$desc" "$name 含 $needle" "未命中" "$HEADERS"
    fi
}

# ---------------------------------------------------------------------------
say "1. 未认证访问"
req GET "$H/api/health"
expect_code "无会话访问 /api/health 返回 401" 401
expect_contains "错误码是 HARKEN_UNAUTHORIZED" HARKEN_UNAUTHORIZED

req POST "$H/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"wrong-password"}'
expect_code "密码错误返回 401" 401
expect_contains "错误码不区分「用户不存在」" HARKEN_BAD_CREDENTIALS

# ---------------------------------------------------------------------------
say "2. 管理员登录"
req POST "$H/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"demo-admin-pw"}'
expect_code "管理员登录成功" 200
ADMIN=$(field x token)
[ -n "$ADMIN" ] && ok "拿到会话令牌" || no "拿到会话令牌" "非空" "空" "$BODY"
expect_contains "响应声明角色为 ADMIN" '"role":"ADMIN"'

SESS="X-Harken-Session: $ADMIN"
req GET "$H/api/health" -H "$SESS"
expect_code "带会话访问 /api/health 成功" 200
expect_contains "事务能力可用" '"supported":true'
expect_contains "AI 通道已启用" '"enabled":true'

# ---------------------------------------------------------------------------
say "3. 前置条件检查"
# 这个脚本会真的改数据（试跑一次 + 提交一次），所以必须在「刚启动的 demo」上跑。
# H2 是内存库，重启即恢复初始状态。不检查的话，第二次跑会得到一堆假失败 ——
# 那种「测试自己把环境弄脏了」的噪音比没有测试更糟。
req POST "$H/api/execute" -H "$SESS" -H 'Content-Type: application/json' \
    -d '{"script":"orderService.statusOf(\"SO-1004\")"}'
if [ "$(field x result)" != "NEW" ]; then
    printf '\n  \033[33mSKIP\033[0m 数据库不是初始状态（SO-1004 当前为 %s）。\n' "$(field x result)"
    printf '       这个脚本会真的改数据，请在刚启动的 demo 上重跑：\n'
    printf '       java -jar harken-demo/target/harken-demo-1.0.0-SNAPSHOT.jar --server.port=18080\n\n'
    exit 2
fi
ok "数据库处于初始状态（SO-1004 = NEW）"

# ---------------------------------------------------------------------------
say "4. 试跑不落库（这是整个工具的卖点）"
req POST "$H/api/execute" -H "$SESS" -H 'Content-Type: application/json' \
    -d '{"script":"def n = orderService.markPaid(\"SO-1004\"); return n","commit":false}'
expect_code "试跑执行成功" 200
expect_contains "影响行数为 1" '"result":"1"'
expect_contains "结果标记已回滚" '"rolledBack":true'

req POST "$H/api/execute" -H "$SESS" -H 'Content-Type: application/json' \
    -d '{"script":"orderService.statusOf(\"SO-1004\")"}'
expect_code "事务外复核查询成功" 200
expect_contains "试跑之后数据仍是 NEW（回滚真的生效）" '"result":"NEW"'

# ---------------------------------------------------------------------------
say "5. 提交真的落库"
req POST "$H/api/execute" -H "$SESS" -H 'Content-Type: application/json' \
    -d '{"script":"def n = orderService.markPaid(\"SO-1004\"); return n","commit":true}'
expect_code "提交执行成功" 200
expect_contains "结果标记未回滚" '"rolledBack":false'

req POST "$H/api/execute" -H "$SESS" -H 'Content-Type: application/json' \
    -d '{"script":"orderService.statusOf(\"SO-1004\")"}'
expect_contains "提交之后数据变成 PAID" '"result":"PAID"'

# ---------------------------------------------------------------------------
say "6. 数据保护：行数闸 / 关键表 / DDL"
# 这三条都应该是「HTTP 200 + ok=false + 具体错误码」：
# 脚本被业务规则拒绝不算传输失败，用 5xx 会让调用方分不清「服务挂了」和「脚本被拦了」。
req POST "$H/api/execute" -H "$SESS" -H 'Content-Type: application/json' \
    -d '{"script":"orderService.markAllPaid()","commit":false}'
expect_code "行数闸在 HTTP 通道同样生效（仍是 200）" 200
expect_contains "错误码是 HARKEN_ROW_LIMIT" HARKEN_ROW_LIMIT
expect_contains "错误信息给出上限" '上限 2 行'

req POST "$H/api/execute" -H "$SESS" -H 'Content-Type: application/json' \
    -d '{"script":"orderService.statusOf(\"SO-1003\")"}'
expect_contains "被拦下之后数据仍是 NEW" '"result":"NEW"'

req POST "$H/api/execute" -H "$SESS" -H 'Content-Type: application/json' \
    -d '{"script":"orderService.closeAllSupplyOrders()"}'
expect_contains "关键表写入被拒" HARKEN_TABLE_FORBIDDEN
expect_contains "错误信息指出是哪张表" supply_order

req POST "$H/api/execute" -H "$SESS" -H 'Content-Type: application/json' \
    -d '{"script":"orderService.supplyOrderCount()"}'
expect_contains "关键表仍然可读（黑名单只拦写）" '"result":"2"'

req POST "$H/api/execute" -H "$SESS" -H 'Content-Type: application/json' \
    -d '{"script":"orderService.createArchiveTable()"}'
expect_contains "DDL 被拒" HARKEN_DDL_FORBIDDEN

# ---------------------------------------------------------------------------
say "7. 只读角色"
req POST "$H/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"reader","password":"demo-reader-pw"}'
expect_code "只读账号登录成功" 200
READER=$(field x token)
RSESS="X-Harken-Session: $READER"

req POST "$H/api/execute" -H "$RSESS" -H 'Content-Type: application/json' \
    -d '{"script":"return 1","commit":true}'
expect_code "READONLY 提交被拒绝 403" 403
expect_contains "拒绝原因是角色不足" HARKEN_FORBIDDEN_COMMIT

req POST "$H/api/execute" -H "$RSESS" -H 'Content-Type: application/json' \
    -d '{"script":"orderService.count()","commit":false}'
expect_code "READONLY 仍可试跑" 200
expect_contains "试跑拿到订单总数" '"result":"5"'

# ---------------------------------------------------------------------------
say "8. 编译与元数据"
req POST "$H/api/compile" -H "$SESS" -H 'Content-Type: application/json' \
    -d '{"script":"def x = \n\nif ("}'
expect_code "语法错误返回 400" 400
expect_contains "错误类型为 COMPILATION" COMPILATION

req POST "$H/api/compile" -H "$SESS" -H 'Content-Type: application/json' \
    -d '{"script":"orderService.count()"}'
expect_code "合法脚本编译通过" 200

req GET "$H/api/metadata" -H "$SESS"
expect_code "元数据接口成功" 200
expect_contains "索引里有业务 bean" orderService
expect_not_contains "索引里不含方法名（方法按需取，别把全量方法塞进快照）" markPaid

req GET "$H/api/metadata?bean=orderService" -H "$SESS"
expect_code "按 bean 取方法表成功" 200
expect_contains "方法表里有 markPaid" markPaid

req GET "$H/api/metadata?type=com.harlanking.demo.order.MoneyUtil" -H "$SESS"
expect_code "按类名取静态方法成功" 200
expect_contains "静态方法表里有 centsToYuan" centsToYuan

# ---------------------------------------------------------------------------
say "9. AI 通道"
req POST "$H/ai/query" -H 'Content-Type: application/json' -d '{"script":"return 1"}'
expect_code "无 Token 访问 AI 通道返回 401" 401
expect_contains "AI 错误码独立" HARKEN_AI_UNAUTHORIZED

req POST "$H/ai/query" -H 'Authorization: Bearer wrong-token' \
    -H 'Content-Type: application/json' -d '{"script":"return 1"}'
expect_code "Token 错误返回 401" 401

req POST "$H/ai/query" -H 'Authorization: Bearer demo-ai-token' \
    -H 'Content-Type: application/json' -d '{"script":"return 1","commit":true}'
expect_code "AI 通道带 commit 参数返回 400" 400
expect_contains "明确说明不接受 commit" "AI 通道不接受 commit 参数"

req POST "$H/ai/query" -H 'Authorization: Bearer demo-ai-token' \
    -H 'Content-Type: application/json' -d '{"script":"orderService.count()"}'
expect_code "AI 只读查询成功" 200
expect_contains "响应标记 channel=ai" '"channel":"ai"'
expect_contains "AI 查询结果标记已回滚" '"rolledBack":true'

req GET "$H/ai/capabilities" -H 'Authorization: Bearer demo-ai-token'
expect_code "能力清单成功" 200
expect_contains "能力清单声明只读" '"readOnly":true'
expect_contains "能力清单包含业务 bean" orderService

req POST "$H/ai/execute" -H 'Authorization: Bearer demo-ai-token' \
    -H 'Content-Type: application/json' -d '{"script":"return 1"}'
expect_code "AI 通道上不存在 /execute" 404

# ---------------------------------------------------------------------------
say "10. 路由与边界"
req GET "$H/api/nope" -H "$SESS"
expect_code "未知路径返回 404" 404

req POST "$H/api/execute" -H "$SESS" -H 'Content-Type: application/json' -d '{bad json'
expect_code "非法 JSON 返回 400 而不是 500" 400

req GET "$H/api/health" -H "X-Harken-Session: forged-token"
expect_code "伪造令牌返回 401" 401

# ---------------------------------------------------------------------------
say "11. 失败锁定"
for i in 1 2 3 4 5; do
    req POST "$H/auth/login" -H 'Content-Type: application/json' \
        -d '{"username":"reader","password":"nope"}'
done
expect_code "连续 5 次失败后第 5 次返回 429" 429

req POST "$H/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"reader","password":"demo-reader-pw"}'
expect_code "锁定期间正确密码也进不去" 429

# ---------------------------------------------------------------------------
say "12. 控制台页面（M3）"

req GET "$H/"
expect_code "GET /harken/ 返回控制台页面" 200
expect_contains "页面里有产品名" "Harken"
expect_header "带严格 CSP（无 unsafe-inline / unsafe-eval）" "Content-Security-Policy" "default-src .none."
expect_header "禁止被 iframe 套用" "X-Frame-Options" "DENY"
expect_header "index 不缓存，升级后立刻生效" "Cache-Control" "no-store"

req GET "$H"
expect_code "GET /harken（无尾斜杠）跳到带斜杠的地址" 302
expect_header "跳转目标是 /harken/" "Location" "/harken/"

req GET "$H/assets/app.css"
expect_code "样式表可取" 200
expect_header "资源走重新验证而非盲缓存" "Cache-Control" "no-cache"

ETAG=$(printf '%s' "$HEADERS" | tr -d '\r' | sed -n 's/^[Ee][Tt]ag: //p' | head -1)
if [ -n "$ETAG" ]; then ok "资源带 ETag"; else no "资源带 ETag" "非空" "空" "$HEADERS"; fi

req GET "$H/assets/app.css" -H "If-None-Match: $ETAG"
expect_code "带 If-None-Match 返回 304（省掉 400KB）" 304

plain=$(curl -s -o /dev/null -w '%{size_download}' "$H/assets/vendor/codemirror.js")
zipped=$(curl -s -o /dev/null -w '%{size_download}' -H 'Accept-Encoding: gzip' \
    "$H/assets/vendor/codemirror.js")
if [ -n "$plain" ] && [ -n "$zipped" ] && [ "$zipped" -lt $((plain / 2)) ]; then
    ok "CodeMirror 走 gzip：$plain → $zipped 字节"
else
    no "CodeMirror 走 gzip" "压缩到一半以下" "$zipped/$plain" ""
fi

# --path-as-is 让 curl 不替我们规整 ".."，测的才是服务端自己。
# 400 与 404 都算拒绝：Tomcat 会在进 servlet 之前就把非法路径打回，
# Harken 自己的白名单是第二道（单测里验的是它）。
req GET "$H/assets/../../../../etc/passwd" --path-as-is
case "$CODE" in
    400|404) ok "路径穿越被拒（$CODE）" ;;
    *) no "路径穿越被拒" "400 或 404" "$CODE" "$BODY" ;;
esac
case "$BODY" in
    *root:*) no "穿越请求没有吐出文件内容" "无 root:" "泄漏了" "$BODY" ;;
    *) ok "穿越请求没有吐出文件内容" ;;
esac

req POST "$H/"
expect_code "页面只接受 GET（POST 返回 405）" 405

req GET "$H/api/audits?limit=3" -H "$SESS"
expect_code "最近执行可反查" 200
expect_contains "响应声明审计可查询" '"queryable":true'
expect_contains "带回了记录列表" '"records"'
expect_contains "记录里有 traceId" '"traceId"'

req POST "$H/api/execute" -H "$SESS" -H 'Content-Type: application/json' \
    -H 'Origin: https://evil.example' -d '{"script":"return 1"}'
expect_code "跨站 Origin 的执行被拒" 403
expect_contains "错误码是 HARKEN_CROSS_ORIGIN" HARKEN_CROSS_ORIGIN

req POST "$H/api/execute" -H "$SESS" -H 'Content-Type: application/json' \
    -H "Origin: $BASE" -d '{"script":"return 40+2"}'
expect_code "同源 Origin 的执行放行" 200
expect_contains "同源执行结果正确" '"result":"42"'

# ---------------------------------------------------------------------------
say "13. 凭据重载"
req POST "$H/auth/reload" -H "$SESS"
expect_code "ADMIN 重载凭据成功" 200
expect_contains "重载踢掉了会话" '"droppedSessions"'

req GET "$H/api/health" -H "$SESS"
expect_code "重载后旧会话立即失效" 401

# ---------------------------------------------------------------------------
printf '\n---------------------------------------------\n'
printf '通过 %d 项，失败 %d 项\n' "$pass" "$fail"
[ "$fail" -eq 0 ] || exit 1
