#!/usr/bin/env bash
# 打 release 包（带签名）。
#
# ## 为什么要有这个脚本
#
# `app/build.gradle.kts` 里的 release 签名配置是**故意不读仓库里的任何文件**的：
# keystore 和密码都从 Gradle property / 环境变量来，缺一项就构建失败
# （见 build.gradle.kts 里 BUG#1 的说明：release 变体曾经没有 signingConfig，
#  打出来的是装不上任何真机的废产物）。
#
# 于是"怎么把凭据喂进去"就落到了每个人身上 —— 之前只有 4 个环境变量名，
# 没人记得住。这个脚本把这件事收拢成一个入口：
#
#     bash scripts/build_release.sh --api-base-url https://api.stashbox.cn/
#
# 凭据从 `~/.stashbox/tingxia-release.properties` 读（**仓库外**，不提交）。
# 想用别的方式（CI / 别的机器），直接给同名环境变量：
#
#     TINGXIA_RELEASE_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD
#
# ## keystore 丢了会怎样
#
# 已装到用户手机上的包**再也装不上覆盖更新**（Android 要求签名一致，
# 只能卸载重装 → 本地库、登录态、下载进度全丢）。
# 所以 `~/.stashbox/tingxia-release.properties` 请单独备份一份（连同 .jks）。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS_FILE="${STASHBOX_SIGNING_PROPS:-$HOME/.stashbox/tingxia-release.properties}"

API_BASE_URL=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --api-base-url) API_BASE_URL="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "未知参数：$1（--help 看用法）" >&2; exit 2 ;;
  esac
done

if [[ -z "$API_BASE_URL" ]]; then
  echo "✗ 缺 --api-base-url" >&2
  echo "  release 包**只认显式指定的后端地址**（构建期守卫会拒绝局域网兜底）——" >&2
  echo "  装出去的包连不上作者家之外的任何一个后端，所以这里必须显式给。" >&2
  exit 2
fi

if [[ ! -f "$PROPS_FILE" ]]; then
  echo "✗ 找不到签名凭据：$PROPS_FILE" >&2
  echo "  生成方法见 ~/.stashbox/README，或临时用 4 个 TINGXIA_RELEASE_* 环境变量。" >&2
  exit 1
fi

# 从 properties 文件里取值，交给 -P
props=()
while IFS='=' read -r k v; do
  [[ -z "$k" || "$k" == \#* ]] && continue
  props+=("-P$k=$v")
done < "$PROPS_FILE"

echo "▶ 打 release 包"
echo "  后端地址：$API_BASE_URL"
echo "  签名凭据：$PROPS_FILE"

cd "$REPO_ROOT"
./gradlew assembleRelease \
  "-PapiBaseUrl=$API_BASE_URL" \
  "${props[@]}" \
  --console=plain "$@"

APK="$REPO_ROOT/app/build/outputs/apk/release/app-release.apk"
if [[ -f "$APK" ]]; then
  echo
  # 注意：这里用 ASCII 括号。全角括号紧跟 $VAR 会被 bash 当成变量名的一部分
  # （"$APK（" → unbound variable），踩过一次。
  echo "✓ 产物：$APK [$(du -h "$APK" | cut -f1)]"
  echo "  签名校验："
  # apksigner 不在 PATH，要从 SDK 里找（ANDROID_HOME 没设时退回 local.properties 的 sdk.dir）
  sdk_dir="${ANDROID_HOME:-}"
  if [[ -z "$sdk_dir" && -f "$REPO_ROOT/local.properties" ]]; then
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" | head -1)"
  fi
  apksigner=""
  for cand in "$sdk_dir"/build-tools/*/apksigner; do
    [[ -x "$cand" ]] && apksigner="$cand"   # 取版本号最大的那个（glob 是字典序，35 > 34）
  done
  if [[ -n "$apksigner" ]]; then
    "$apksigner" verify --print-certs "$APK" 2>/dev/null \
      | sed -n '1,4p' | sed 's/^/    /' || echo "    （校验失败）"
  else
    echo "    （没找到 apksigner，跳过校验）"
  fi
else
  echo "✗ 没找到产物 $APK" >&2
  exit 1
fi
