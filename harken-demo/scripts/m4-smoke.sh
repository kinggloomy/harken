#!/usr/bin/env bash
# ============================================================================
# Harken M4 冒烟测试：受限模式 + 双交付形态
#
# 覆盖 M4 的四项验收：
#   1) mode:RESTRICTED 时 /api/health.restricted=true（前端据此隐藏编辑器）
#   2) 参数缺失无法提交（RESTRICTED 下 execute 必填参数校验）
#   3) DANGEROUS 脚本不出现（目录按 maxRisk 过滤 + execute 二次拦截）
#   4) 非 Spring 的 Library main 跑通完整流程（登录→health→catalog→execute）
#
# 同时验 Starter（FULL）形态：restricted=false、目录下发源码、自由编写仍可用。
#
# 用法（两个 demo 都已启动）：
#   bash harken-demo/scripts/m4-smoke.sh [LIB_BASE] [STARTER_BASE]
# 默认 LIB_BASE=http://127.0.0.1:8091  STARTER_BASE=http://127.0.0.1:18080
#
# 只用 curl + sed，不依赖 jq。
# ============================================================================
set -u

LIB="${1:-http://127.0.0.1:8091}"
ST="${2:-http://127.0.0.1:18080}"
LH="$LIB/harken"
SH="$ST/harken"
pass=0
fail=0

say() { printf '\n\033[1m%s\033[0m\n' "$1"; }
ok()   { pass=$((pass + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
no() {
    fail=$((fail + 1))
    printf '  \033[31mFAIL\033[0m %s\n       expect=%s actual=%s\n       body=%s\n' \
        "$1" "$2" "$3" "$4"
}

CODE=""; BODY=""; HEADERS=""

req() {
    local method=$1 url=$2
    shift 2
    local resp
    resp=$(curl -s -i -m 20 -X "$method" "$url" "$@")
    HEADERS=$(printf '%s' "$resp" | sed -n '1,/^[[:space:]]*$/p')
    CODE=$(printf '%s' "$resp" | head -1 | tr -d '\r' | awk '{print $2}')
    BODY=$(printf '%s\n' "$resp" | sed '1,/^[[:space:]]*$/d')
}

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
expect_absent() {
    local desc=$1 needle=$2 hay=${3:-$BODY}
    case "$hay" in
        *"$needle"*) no "$desc" "不应含 $needle" "却含有" "$hay" ;;
        *) ok "$desc" ;;
    esac
}

# ---------------------------------------------------------------------------
# 一、Library 形态（非 Spring，main 直接起嵌入式 Tomcat，RESTRICTED 模式）
# ---------------------------------------------------------------------------
say "A. 未认证访问 Library 形态（RESTRICTED）"
req GET "$LH/api/health"
expect_code "无会话访问 /api/health 返回 401" 401

say "B. Library 形态管理员登录"
req POST "$LH/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"lib-admin-pw"}'
expect_code "登录成功" 200
LIBTOK=$(field x token)
[ -n "$LIBTOK" ] && ok "拿到会话令牌" || no "拿到会话令牌" "非空" "空" "$BODY"
LSESS="X-Harken-Session: $LIBTOK"

say "C. Library 形态 health：RESTRICTED 模式"
req GET "$LH/api/health" -H "$LSESS"
expect_code "带会话访问 /api/health 成功" 200
expect_contains "health 声明受限模式" '"restricted":true'
expect_contains "health 声明 mode=RESTRICTED" '"mode":"RESTRICTED"'

say "D. Library 形态 catalog：受限模式过滤 + 不下发源码"
req GET "$LH/api/catalog" -H "$LSESS"
expect_code "catalog 成功" 200
expect_contains "catalog 声明 maxRisk=WRITE" '"maxRisk":"WRITE"'
expect_contains "列表含 READ 脚本 lookup" '"id":"lookup"'
expect_contains "列表含 WRITE 脚本 exportReport" '"id":"exportReport"'
expect_absent "DANGEROUS 脚本 purgeCache 不出现" '"id":"purgeCache"'
expect_absent "受限模式不下发脚本源码（无 script 字段）" '"script":'

say "E. Library 形态执行 lookup（带必填参数）"
req POST "$LH/api/execute" -H "$LSESS" -H 'Content-Type: application/json' \
    -d '{"scriptId":"lookup","params":{"code":"ABC123"},"commit":false}'
expect_code "带参数的受限执行成功" 200
expect_contains "结果回显了传入的参数 code" '查到 code=ABC123'

say "F. Library 形态执行 lookup（缺必填参数 → 拒绝）"
req POST "$LH/api/execute" -H "$LSESS" -H 'Content-Type: application/json' \
    -d '{"scriptId":"lookup","params":{},"commit":false}'
expect_code "缺必填参数返回 400" 400
expect_contains "错误说明缺少必填参数" '缺少必填参数'

say "G. Library 形态尝试执行被过滤的 DANGEROUS 脚本（撞 scriptId 也拦）"
req POST "$LH/api/execute" -H "$LSESS" -H 'Content-Type: application/json' \
    -d '{"scriptId":"purgeCache","commit":false}'
expect_code "被过滤的脚本无法被直接指定执行（403 风险超限）" 403
expect_contains "错误说明风险等级超限" '超过受限模式允许上限'

say "H. Library 形态执行 exportReport（WRITE，commitAllowed=true，试跑回滚）"
req POST "$LH/api/execute" -H "$LSESS" -H 'Content-Type: application/json' \
    -d '{"scriptId":"exportReport","params":{"days":3},"commit":false}'
expect_code "WRITE 试跑成功" 200
expect_contains "结果回显了 days 参数" '将导出最近 3 天'
expect_contains "试跑标记已回滚" '"rolledBack":true'

# ---------------------------------------------------------------------------
# 二、Starter 形态（Spring Boot 自动配置，FULL 模式）
# ---------------------------------------------------------------------------
say "I. Starter 形态管理员登录"
req POST "$SH/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"demo-admin-pw"}'
expect_code "登录成功" 200
STTOK=$(field x token)
[ -n "$STTOK" ] && ok "拿到会话令牌" || no "拿到会话令牌" "非空" "空" "$BODY"
SSESS="X-Harken-Session: $STTOK"

say "J. Starter 形态 health：FULL 模式"
req GET "$SH/api/health" -H "$SSESS"
expect_code "health 成功" 200
expect_contains "health 声明非受限" '"restricted":false'
expect_contains "health 声明 mode=FULL" '"mode":"FULL"'

say "K. Starter 形态 catalog：FULL 模式下发源码"
req GET "$SH/api/catalog" -H "$SSESS"
expect_code "catalog 成功" 200
expect_contains "FULL 模式下发脚本源码（含 script 字段）" '"script":'
expect_contains "列表含 ping" '"id":"ping"'
expect_contains "列表含 greet" '"id":"greet"'
expect_contains "列表含 previewWrites" '"id":"previewWrites"'
expect_contains "列表含 dangerDemo（FULL 不过滤）" '"id":"dangerDemo"'

say "L. Starter 形态自由编写脚本仍可执行（FULL 的立身之本）"
req POST "$SH/api/execute" -H "$SSESS" -H 'Content-Type: application/json' \
    -d '{"script":"return 40+2","commit":false}'
expect_code "自由脚本执行成功" 200
expect_contains "结果正确（42）" '"result":"42"'

# ---------------------------------------------------------------------------
printf '\n---------------------------------------------\n'
printf 'M4 通过 %d 项，失败 %d 项\n' "$pass" "$fail"
[ "$fail" -eq 0 ] || exit 1
