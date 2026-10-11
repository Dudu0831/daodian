#!/usr/bin/env bash
# 一键发布：签名打包 → 蒲公英 → 校验 → VPS 备份/版本转接 → latest.json。
# 用法：scripts/publish.sh "这一版改了什么"；只检查环境：scripts/publish.sh --check
# 发之前增加 app/build.gradle.kts 的 versionCode，修改 versionName。
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 scripts/pgyer_release.py "$@"
