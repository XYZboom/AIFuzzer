"""
AiFuzzer TVM Daemon — 常驻 Python 进程，通过 stdin/stdout JSON 协议与 JVM 通信。

协议：
  输入: {"type":"run", "id":<int>, "source":"<python code>"}
  输出: {"type":"result", "id":<int>, "success":bool, "exit_code":int,
         "stdout":"...", "stderr":"...", "elapsed_ms":<int>}

  心跳:
  输入: {"type":"ping"}
  输出: {"type":"pong"}

  关闭:
  输入: __SHUTDOWN__

使用方法:
  python3 tvm_daemon.py          # 启动 daemon（监听 stdin）
  python3 tvm_daemon.py --test   # 自测试模式（验证 TVM 可用）
"""

import json
import sys
import time
import traceback
from io import StringIO

# 一次性导入 TVM（最耗时的部分）
TVM_AVAILABLE = False
_import_error = ""
_tvm_import_detail = ""
try:
    import tvm
    from tvm import relax
    import tvm.relax.op as op
    TVM_AVAILABLE = True
except Exception as _ie:
    _import_error = repr(_ie)
    _tvm_import_detail = traceback.format_exc()


def run_source(source: str) -> dict:
    """执行单次测试源码，捕获 stdout/stderr。"""
    global tvm, relax, op
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    sys.stdout = StringIO()
    sys.stderr = StringIO()

    start = time.time()
    exit_code = 0
    try:
        exec(source, {"tvm": tvm, "relax": relax, "op": op})
        success = True
    except SystemExit as e:
        # Python 脚本可能调用了 sys.exit()
        success = False
        exit_code = e.code if isinstance(e.code, int) else 1
        traceback.print_exc()
    except Exception:
        traceback.print_exc()
        success = False
        exit_code = 1

    elapsed = int((time.time() - start) * 1000)
    captured_stdout = sys.stdout.getvalue()
    captured_stderr = sys.stderr.getvalue()
    sys.stdout = old_stdout
    sys.stderr = old_stderr

    return {
        "success": success,
        "exit_code": exit_code,
        "stdout": captured_stdout,
        "stderr": captured_stderr,
        "elapsed_ms": elapsed,
    }


def daemon_loop():
    """主循环：从 stdin 逐行读取 JSON 请求并回复到 stdout。"""
    # 先报告就绪状态
    ready_msg = {
        "type": "ready",
        "tvm_available": TVM_AVAILABLE,
    }
    if not TVM_AVAILABLE:
        ready_msg["import_error"] = _import_error
        if hasattr(sys.modules[__name__], '_tvm_import_detail'):
            ready_msg["import_detail"] = _tvm_import_detail
    print(json.dumps(ready_msg), flush=True)

    for raw_line in sys.stdin:
        line = raw_line.strip()
        if not line:
            continue
        if line == "__SHUTDOWN__":
            break

        try:
            req = json.loads(line)
        except json.JSONDecodeError as e:
            print(json.dumps({
                "type": "result",
                "id": -1,
                "success": False,
                "exit_code": -1,
                "stdout": "",
                "stderr": f"JSON decode error: {e}",
                "elapsed_ms": 0,
            }), flush=True)
            continue

        req_type = req.get("type", "run")

        if req_type == "ping":
            print(json.dumps({"type": "pong"}), flush=True)
            continue

        if req_type == "run":
            req_id = req.get("id", -1)
            source = req.get("source", "")
            try:
                result = run_source(source)
                result["id"] = req_id
                result["type"] = "result"
                print(json.dumps(result), flush=True)
            except Exception as e:
                print(json.dumps({
                    "type": "result",
                    "id": req_id,
                    "success": False,
                    "exit_code": -1,
                    "stdout": "",
                    "stderr": f"Daemon internal error: {e}\n{traceback.format_exc()}",
                    "elapsed_ms": 0,
                }), flush=True)
        else:
            print(json.dumps({
                "type": "result",
                "id": req.get("id", -1),
                "success": False,
                "exit_code": -1,
                "stdout": "",
                "stderr": f"Unknown request type: {req_type}",
                "elapsed_ms": 0,
            }), flush=True)


def self_test():
    """自测试：验证 TVM 可用并运行一个简单的 Relax 程序。"""
    if not TVM_AVAILABLE:
        print(f"FAIL: TVM not available: {_import_error}")
        sys.exit(1)
    print("PASS: TVM is available")

    # 简单的 Relax 程序测试（使用 add，不依赖子模块）
    source = """
import tvm
from tvm import relax
import tvm.relax.op as op

def build_mod():
    bb = relax.BlockBuilder()
    x = relax.Var("x", relax.TensorStructInfo(shape=relax.ShapeExpr([16]), dtype="float32"))
    with bb.function("test_func", [x]):
        y = bb.emit(relax.op.add(x, x))
        bb.emit_func_output(y)
    return bb.get()

mod = build_mod()
print("Mod built successfully")
"""
    result = run_source(source)
    if result["success"]:
        print(f"PASS: Relax program executed successfully")
        print(f"  stdout: {result['stdout'].strip()}")
    else:
        print(f"FAIL: Relax program failed")
        print(f"  stderr: {result['stderr'].strip()}")
        sys.exit(1)


if __name__ == "__main__":
    if "--test" in sys.argv:
        self_test()
    else:
        daemon_loop()