#!/usr/bin/env bash
# 把本地语音识别的模型放进 app/src/main/assets/asr/（DESIGN 决策 8.3、VOICE_PLAN.md）。
#
# 模型 239MB，超过 GitHub 单个文件 100MB 的上限，进不了 git；换机器、新克隆之后跑一次：
#   ./tools/fetch_asr_model.sh
# 已经在、校验对得上就什么都不做。来源是 sherpa-onnx 官方 GitHub Releases，按 sha256 校验。
#
# 只能用 2024-07-17 那版：2025-09-09 是粤语微调（WSYue），什么都认成粤语。
set -euo pipefail

cd "$(dirname "$0")/.."
DEST=app/src/main/assets/asr
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models
SV=sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17

MODEL_SHA=c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51
TOKENS_SHA=f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc
VAD_SHA=9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6

sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
ok() { [ -f "$1" ] && [ "$(sha "$1")" = "$2" ]; }

if ok "$DEST/model.int8.onnx" "$MODEL_SHA" && ok "$DEST/tokens.txt" "$TOKENS_SHA" && ok "$DEST/silero_vad.onnx" "$VAD_SHA"; then
  echo "语音模型已经在了：$DEST"
  exit 0
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$DEST"

if ! ok "$DEST/model.int8.onnx" "$MODEL_SHA" || ! ok "$DEST/tokens.txt" "$TOKENS_SHA"; then
  echo "下载 SenseVoice（约 160MB）…"
  curl -fsSL --retry 3 -o "$TMP/sv.tar.bz2" "$BASE/$SV.tar.bz2"
  tar xjf "$TMP/sv.tar.bz2" -C "$TMP" "$SV/model.int8.onnx" "$SV/tokens.txt"
  [ "$(sha "$TMP/$SV/model.int8.onnx")" = "$MODEL_SHA" ] || { echo "model.int8.onnx 校验不对" >&2; exit 1; }
  [ "$(sha "$TMP/$SV/tokens.txt")" = "$TOKENS_SHA" ] || { echo "tokens.txt 校验不对" >&2; exit 1; }
  mv "$TMP/$SV/model.int8.onnx" "$TMP/$SV/tokens.txt" "$DEST/"
fi

if ! ok "$DEST/silero_vad.onnx" "$VAD_SHA"; then
  echo "下载 Silero VAD…"
  curl -fsSL --retry 3 -o "$TMP/silero_vad.onnx" "$BASE/silero_vad.onnx"
  [ "$(sha "$TMP/silero_vad.onnx")" = "$VAD_SHA" ] || { echo "silero_vad.onnx 校验不对" >&2; exit 1; }
  mv "$TMP/silero_vad.onnx" "$DEST/"
fi

echo "好了：$DEST"
ls -la "$DEST"
