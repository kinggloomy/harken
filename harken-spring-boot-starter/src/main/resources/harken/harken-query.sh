#!/usr/bin/env bash
# ============================================================================
# harken-query.sh —— 让 AI Agent（或人）用一行命令查运行中的应用
#
# 用法：
#   export HARKEN_URL=http://127.0.0.1:8080/harken
#   export HARKEN_AI_TOKEN=***
#   # 仅当服务端配了 harken.ai.signing-secret 时才需要（配了就必须给，否则 401）
#   export HARKEN_SIGNING_SECRET=***
#
#   ./harken-query.sh 'orderService.count()'
#   ./harken-query.sh --capabilities            # 能力清单（默认已 gzip，curl 自动解）
#   ./harken-query.sh -q 'orderService.count()' # 只输出 result 字段，便于管道
#
# 设计约束（刻意保持）：
#   * 只依赖 curl（+ 开启签名时依赖 openssl），不依赖 jq —— 目标机器上常常只有 curl
#   * 默认输出原始 JSON：不替调用方解释，避免"脚本先把响应改写坏"这类问题
#   * 唯一写死的路径是 /ai/query：这个脚本不可能调到人类通道的 /api/execute
#
# 退出码：0 = ok:true，1 = ok:false，2 = 用法/网络错误
# ============================================================================
set -u

URL="${HARKEN_URL:-http://127.0.0.1:8080/harken}"
TOKEN="${HARKEN_AI_TOKEN:-}"

usage() {
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

if [ -z "$TOKEN" ]; then
    echo "错误：未设置 HARKEN_AI_TOKEN。" >&2
    echo "      AI 通道需要 Bearer Token，取值来自应用配置 harken.ai.token。" >&2
    exit 2
fi

MODE="query"
QUIET=0
SCRIPT=""

for arg in "$@"; do
    case "$arg" in
        -h | --help) usage ;;
        --capabilities) MODE="capabilities" ;;
        -q | --quiet) QUIET=1 ;;
        -*)
            echo "错误：未知选项 $arg" >&2
            exit 2
            ;;
        *) SCRIPT="$arg" ;;
    esac
done

# 把一段文本转成 JSON 字符串字面量（含首尾引号）。
# 顺序很重要：先转反斜杠，再转引号，否则会把刚加上的转义符再转一遍。
json_escape() {
    LC_ALL=C awk '
        BEGIN { printf "\"" }
        {
            gsub(/\\/, "\\\\")
            gsub(/"/, "\\\"")
            gsub(/\t/, "\\t")
            gsub(/\r/, "")
            if (NR > 1) printf "\\n"
            printf "%s", $0
        }
        END { printf "\"" }'
}

# ============================================================================
# 请求签名（SR-04）：只有设置了 HARKEN_SIGNING_SECRET 才生效
#
# 服务端一旦配置了 harken.ai.signing-secret，所有 AI 请求都必须带三个头。
# 待签串（服务端与这里必须逐字节一致）：
#     METHOD \n PATH \n TIMESTAMP \n NONCE \n HEX(SHA256(body))
# 其中 PATH 是 requestURI，即 URL 里去掉 scheme://host 之后的部分。
# ============================================================================
SIGNING_SECRET="${HARKEN_SIGNING_SECRET:-}"
SIGN_ARGS=()

base_path() {
    # 从 HARKEN_URL 里剥掉 scheme://host，留下 /harken 这样的前缀
    printf '%s' "$URL" | sed -E 's#^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+##'
}

sha256_hex() {
    # $1 = 待哈希文本（可为空）
    printf '%s' "$1" | openssl dgst -sha256 -hex 2>/dev/null | awk '{print $NF}'
}

hmac_hex() {
    # $1 = 密钥，$2 = 待签串
    printf '%s' "$2" | openssl dgst -sha256 -hmac "$1" -hex 2>/dev/null | awk '{print $NF}'
}

sign_headers() {
    SIGN_ARGS=()
    [ -z "$SIGNING_SECRET" ] && return 0
    if ! command -v openssl >/dev/null 2>&1; then
        echo "错误：设置了 HARKEN_SIGNING_SECRET，但找不到 openssl。" >&2
        echo "      Ubuntu/Debian: apt install openssl；Alpine: apk add openssl" >&2
        exit 2
    fi
    local method=$1 path=$2 body=$3
    local ts nonce body_hash canonical sig
    ts=$(date +%s)
    nonce=$(openssl rand -hex 16)
    body_hash=$(sha256_hex "$body")
    canonical=$(printf '%s\n%s\n%s\n%s\n%s' "$method" "$path" "$ts" "$nonce" "$body_hash")
    sig=$(hmac_hex "$SIGNING_SECRET" "$canonical")
    SIGN_ARGS=(
        -H "X-Harken-Timestamp: $ts"
        -H "X-Harken-Nonce: $nonce"
        -H "X-Harken-Signature: $sig"
    )
}

call() {
    if [ "$MODE" = "capabilities" ]; then
        sign_headers "GET" "$(base_path)/ai/capabilities" ""
        curl -s --compressed -m 30 \
            -H "Authorization: Bearer $TOKEN" \
            "${SIGN_ARGS[@]}" \
            -H 'Accept-Encoding: gzip' \
            "$URL/ai/capabilities"
    else
        local payload
        payload=$(printf '{"script":%s}' "$(printf '%s' "$SCRIPT" | json_escape)")
        sign_headers "POST" "$(base_path)/ai/query" "$payload"
        # 用 --data-binary 而不是 -d：-d 会吃掉脚本里的换行，Groovy 的缩进就没了
        curl -s -m 60 -X POST "$URL/ai/query" \
            -H "Authorization: Bearer $TOKEN" \
            "${SIGN_ARGS[@]}" \
            -H 'Content-Type: application/json' \
            --data-binary "$payload"
    fi
}

if [ "$MODE" = "query" ] && [ -z "$SCRIPT" ]; then
    echo "错误：缺少脚本参数。" >&2
    usage
fi

RESP=$(call)
RC=$?

if [ $RC -ne 0 ]; then
    echo "错误：请求失败（curl 退出码 $RC）。检查 HARKEN_URL 是否可达、服务是否在运行。" >&2
    exit 2
fi

if [ -z "$RESP" ]; then
    echo "错误：服务端返回空响应。" >&2
    exit 2
fi

if [ "$QUIET" -eq 1 ]; then
    # 只取 result 字段。故意用 sed 而不是解析 JSON：
    # 这个模式下调用方要的就是"一段文本"，任何多余处理都可能改写它。
    printf '%s' "$RESP" | sed -n 's/.*"result":"\(\([^"\\]\|\\.\)*\)".*/\1/p'
else
    printf '%s\n' "$RESP"
fi

case "$RESP" in
    *'"ok":false'*) exit 1 ;;
    *) exit 0 ;;
esac
