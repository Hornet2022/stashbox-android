#!/usr/bin/env bash
#
# CP6.7 蒲公英 Android 内测包发布脚本（v1 §11.6 CP6.7 落地）
#
# 流程：
#   1. gradle assembleRelease（如果你有 release keystore）or assembleDebug（CP7 用 debug 版）
#   2. 检查 APK 存在
#   3. 调蒲公英 API 上传 APK + 拿 build key
#   4. 输出蒲公英短链 + 二维码
#
# 用法：
#   # CP7 内测（debug 包 + 蒲公英上传）
#   ./scripts/build_and_release.sh --api-key <YOUR_PGYER_API_KEY> --build-type debug
#
#   # CP8 公测（release 包 + 蒲公英上传）
#   ./scripts/build_and_release.sh --api-key <YOUR_PGYER_API_KEY> --build-type release
#
# 环境变量（也可不用 CLI 参数，从环境读）：
#   PGYER_API_KEY    蒲公英 API key（你后台 https://www.pgyer.com/account/api 获取）
#   PGYER_PASSWORD   APK 安装密码（可选，不设 = 不加密）
#
# 不需要上云（蒲公英是第三方 SaaS）。

set -euo pipefail

# ──────────────── 参数解析 ────────────────
API_KEY="${PGYER_API_KEY:-}"
PASSWORD="${PGYER_PASSWORD:-}"
BUILD_TYPE="debug"  # 默认 debug，CP7 内测用
APP_NAME="听匣"

usage() {
  cat <<EOF
用法: $0 [选项]

选项:
  --api-key KEY       蒲公英 API key（也可设 PGYER_API_KEY env）
  --password PWD      APK 安装密码（可选）
  --build-type TYPE   debug (默认) / release
  --app-name NAME     应用名（默认: 听匣）
  -h, --help          显示本帮助

示例:
  $0 --api-key abc123 --build-type debug
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --api-key)     API_KEY="$2"; shift 2 ;;
    --password)    PASSWORD="$2"; shift 2 ;;
    --build-type)  BUILD_TYPE="$2"; shift 2 ;;
    --app-name)    APP_NAME="$2"; shift 2 ;;
    -h|--help)     usage ;;
    *)             echo "未知参数: $1" >&2; usage ;;
  esac
done

if [[ -z "$API_KEY" ]]; then
  echo "❌ 错误: 必须提供蒲公英 API key" >&2
  echo "  方式 1: --api-key abc123" >&2
  echo "  方式 2: export PGYER_API_KEY=abc123" >&2
  exit 1
fi

# ──────────────── 路径 ────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
APK_PATH="${PROJECT_ROOT}/app/build/outputs/apk/${BUILD_TYPE}/app-${BUILD_TYPE}.apk"

if [[ ! -f "${PROJECT_ROOT}/gradlew" ]]; then
  echo "❌ 错误: 没找到 gradlew（在 ${PROJECT_ROOT}）" >&2
  exit 1
fi

# ──────────────── Step 1: 构建 APK ────────────────
echo "📦 Step 1: 构建 APK (${BUILD_TYPE})..."
cd "$PROJECT_ROOT"
./gradlew "assemble${BUILD_TYPE^}" 2>&1 | tail -20

if [[ ! -f "$APK_PATH" ]]; then
  echo "❌ 错误: APK 没找到在 ${APK_PATH}" >&2
  exit 1
fi

APK_SIZE_MB=$(du -m "$APK_PATH" | cut -f1)
echo "✅ APK 构建成功 (${APK_SIZE_MB} MB): ${APK_PATH}"

# ──────────────── Step 2: 上传蒲公英 ────────────────
echo ""
echo "☁️  Step 2: 上传蒲公英..."

# 蒲公英 API: https://www.pgyer.com/doc/view/upload
UPLOAD_URL="https://www.pgyer.com/apiv2/app/upload"
BUILD_INSTALL_TYPE="${BUILD_TYPE}"  # 1=build, 2=internal
BUILD_INSTALL_DATE=1  # 默认 1 天有效期

FORM_FIELDS=(
  -F "_api_key=${API_KEY}"
  -F "buildType=2"  # 2 = 内部版本
  -F "buildName=${APP_NAME} CP7 内测"
  -F "buildBuildVersion=$(date +%Y%m%d-%H%M%S)"
  -F "buildVersion=${BUILD_TYPE}"
  -F "buildIdentifier=com.tingxia.audio"
  -F "buildIcon=ignore"
)

if [[ -n "$PASSWORD" ]]; then
  FORM_FIELDS+=(-F "buildPassword=${PASSWORD}")
fi

UPLOAD_RESPONSE=$(curl -sS --max-time 120 "${FORM_FIELDS[@]}" -F "file=@${APK_PATH}" "$UPLOAD_URL")

# 蒲公英返回 JSON 格式: {"code":0,"data":{"buildKey":"...","buildShortcutUrl":"..."},...}
if ! command -v jq &>/dev/null; then
  echo "⚠️  提示: 装 jq 更方便解析蒲公英返回（brew install jq）"
  echo "📄 蒲公英原始返回:"
  echo "$UPLOAD_RESPONSE"
  exit 0
fi

CODE=$(echo "$UPLOAD_RESPONSE" | jq -r '.code')
if [[ "$CODE" != "0" ]]; then
  echo "❌ 上传失败: $UPLOAD_RESPONSE" >&2
  exit 1
fi

BUILD_KEY=$(echo "$UPLOAD_RESPONSE" | jq -r '.data.buildKey')
SHORTCUT_URL=$(echo "$UPLOAD_RESPONSE" | jq -r '.data.buildShortcutUrl')
QR_URL="https://www.pgyer.com/app/qrcode/${BUILD_KEY}"

echo ""
echo "✅ 蒲公英上传成功!"
echo "  Build Key:       ${BUILD_KEY}"
echo "  短链 (URL):      ${SHORTCUT_URL}"
echo "  二维码 (URL):    ${QR_URL}"

# ──────────────── Step 3: 输出结果 ────────────────
echo ""
echo "🎉 发布完成！把下面发给 100 用户："
echo ""
echo "  📱 下载链接: ${SHORTCUT_URL}"
echo "  📷 二维码:   ${QR_URL}"
if [[ -n "$PASSWORD" ]]; then
  echo "  🔐 安装密码: ${PASSWORD}"
fi
echo ""
echo "下一步:"
echo "  1. 复制短链发到内测微信群"
echo "  2. 提示用户扫二维码或点链接下载 APK"
echo "  3. 用户首次安装需要允许\"未知来源\"（Android 8+）"
