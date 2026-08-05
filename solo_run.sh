#!/bin/bash
set -euo pipefail
BIN="/root/Code/kotlin/aifuzzer/build/install/aiFuzzer/bin/aiFuzzer"
CFG="/root/Code/kotlin/aifuzzer/configs"
for be in mut-tvm-100 mut-pytorch-100 mut-onnx-100; do
  echo "=== $be ==="
  "$BIN" fuzz --config "$CFG/$be.yaml" 2>&1 | grep -E "Successes:|Failures:"
done
echo "=== DONE ==="
