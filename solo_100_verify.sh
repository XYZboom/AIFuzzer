#!/bin/bash
# Sequential solo backend 100-round mutation verification
# Each backend runs alone (1 worker) to avoid daemon overload.

set -e

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
cd /root/Code/kotlin/aifuzzer

echo "===== TVM CUDA 100 rounds ====="
echo "Started at: $(date)"
gradle :run --args="fuzz -c configs/mut-tvm-100-verify.yaml" --no-daemon 2>&1 | tee /tmp/tvm-100.log
echo "Finished at: $(date)"
echo "TVM result: $(grep 'Successes:' /tmp/tvm-100.log | tail -1)"

echo ""
echo "===== ONNX 100 rounds ====="
echo "Started at: $(date)"
gradle :run --args="fuzz -c configs/mut-onnx-100-verify.yaml" --no-daemon 2>&1 | tee /tmp/onnx-100.log
echo "Finished at: $(date)"
echo "ONNX result: $(grep 'Successes:' /tmp/onnx-100.log | tail -1)"

echo ""
echo "===== PyTorch CUDA 100 rounds ====="
echo "Started at: $(date)"
gradle :run --args="fuzz -c configs/mut-pytorch-100-verify.yaml" --no-daemon 2>&1 | tee /tmp/pytorch-100.log
echo "Finished at: $(date)"
echo "PyTorch result: $(grep 'Successes:' /tmp/pytorch-100.log | tail -1)"

echo ""
echo "===== SUMMARY ====="
echo "TVM:     $(grep 'Successes:' /tmp/tvm-100.log | tail -1)"
echo "ONNX:    $(grep 'Successes:' /tmp/onnx-100.log | tail -1)"
echo "PyTorch: $(grep 'Successes:' /tmp/pytorch-100.log | tail -1)"
