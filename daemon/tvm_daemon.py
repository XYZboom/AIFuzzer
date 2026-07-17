"""
AiFuzzer TVM Daemon — 常驻 Python HTTP 服务，通过 HTTP POST 与 JVM 通信。

API:
  POST /run    {"source": "<python code>"}
               → {"success": true, "exit_code": 0, "stdout": "...", "stderr": "...", "elapsed_ms": 1}

  GET  /health → {"status": "ok", "backend_available": true, "uptime_seconds": 123}

  POST /shutdown → 优雅关闭

使用方法:
  python3 daemon/tvm_daemon.py               # 启动 daemon（默认端口 34789）
  python3 daemon/tvm_daemon.py --port 8888      # 指定端口
  python3 daemon/tvm_daemon.py --test           # 自测试模式（验证 TVM 可用）
"""

import json
import signal
import sys
import threading
import time
import traceback
from http.server import HTTPServer, BaseHTTPRequestHandler
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


# 每次 exec 的最大超时时间（秒），避免 TVM 内部永久阻塞
EXEC_TIMEOUT_SECONDS = 120
DAEMON_START_TIME = time.time()


def _timeout_handler(signum, frame):
    """SIGALRM 处理器，抛出 TimeoutError。"""
    raise TimeoutError(f"exec timed out after {EXEC_TIMEOUT_SECONDS}s")


def run_source(source: str, timeout: int = EXEC_TIMEOUT_SECONDS) -> dict:
    """执行单次测试源码，捕获 stdout/stderr。

    使用 signal.alarm 进行超时保护：TVM 编译可能在某些输入下无限阻塞
    （如 CUDA 同步、死锁等），此时 alarm 会触发 TimeoutError。
    """
    global tvm, relax, op
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    old_alarm = None
    sys.stdout = StringIO()
    sys.stderr = StringIO()

    start = time.time()
    exit_code = 0
    try:
        # 设置 alarm 超时
        if hasattr(signal, "SIGALRM"):
            signal.signal(signal.SIGALRM, _timeout_handler)
            old_alarm = signal.alarm(timeout)

        exec(source, {"tvm": tvm, "relax": relax, "op": op})
        success = True
    except SystemExit as e:
        # Python 脚本可能调用了 sys.exit()
        success = False
        exit_code = e.code if isinstance(e.code, int) else 1
        traceback.print_exc()
    except TimeoutError as e:
        success = False
        exit_code = -1
        print(f"TIMEOUT: {e}", file=sys.stderr)
    except Exception:
        traceback.print_exc()
        success = False
        exit_code = 1
    finally:
        # 恢复 alarm
        if hasattr(signal, "SIGALRM") and old_alarm is not None:
            signal.alarm(old_alarm)

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


class DaemonRequestHandler(BaseHTTPRequestHandler):
    """HTTP 请求处理器。每个请求在独立线程中处理（由 ThreadingHTTPServer 保证）。"""

    def do_POST(self):
        if self.path == "/run":
            self._handle_run()
        elif self.path == "/shutdown":
            self._handle_shutdown()
        else:
            self._json_response(404, {"error": f"unknown path: {self.path}"})

    def do_GET(self):
        if self.path == "/health":
            self._handle_health()
        else:
            self._json_response(404, {"error": f"unknown path: {self.path}"})

    def _handle_run(self):
        """处理 /run：执行 TVM Relax 代码。"""
        try:
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length)
            req = json.loads(body)
        except Exception as e:
            self._json_response(400, {
                "success": False,
                "exit_code": -1,
                "stdout": "",
                "stderr": f"Bad request: {e}",
                "elapsed_ms": 0,
            })
            return

        source = req.get("source", "")
        try:
            result = run_source(source)
            self._json_response(200, result)
        except Exception as e:
            self._json_response(500, {
                "success": False,
                "exit_code": -1,
                "stdout": "",
                "stderr": f"Daemon internal error: {e}\n{traceback.format_exc()}",
                "elapsed_ms": 0,
            })

    def _handle_health(self):
        self._json_response(200, {
            "status": "ok",
            "backend_available": TVM_AVAILABLE,
            "uptime_seconds": int(time.time() - DAEMON_START_TIME),
            "import_error": _import_error if not TVM_AVAILABLE else "",
        })

    def _handle_shutdown(self):
        self._json_response(200, {"status": "shutting_down"})
        # 在响应发送后关闭服务
        threading.Thread(target=self.server.shutdown, daemon=True).start()

    def _json_response(self, status_code: int, data: dict):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        """抑制默认的 HTTP 日志输出（太吵）。"""
        pass


def self_test():
    """自测试：验证 TVM 可用并运行一个简单的 Relax 程序。"""
    if not TVM_AVAILABLE:
        print(f"FAIL: TVM not available: {_import_error}")
        sys.exit(1)
    print("PASS: TVM is available")

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


def main():
    import threading  # 用于 /shutdown

    port = 34789  # 默认端口
    if "--port" in sys.argv:
        idx = sys.argv.index("--port")
        if idx + 1 < len(sys.argv):
            port = int(sys.argv[idx + 1])

    if "--test" in sys.argv:
        self_test()
        return

    server = HTTPServer(("127.0.0.1", port), DaemonRequestHandler)
    # 使用多线程处理请求
    server.socket.settimeout(1.0)  # 1 秒超时，让 serve_forever 能响应信号

    # 打印配置信息（JVM 将读取此信息获取端口等）
    import os
    server_info = {
        "type": "ready",
        "backend_available": TVM_AVAILABLE,
        "port": port,
        "pid": os.getpid(),
    }
    if not TVM_AVAILABLE:
        server_info["import_error"] = _import_error
        server_info["import_detail"] = _tvm_import_detail

    print(json.dumps(server_info), flush=True)

    # 注册信号处理优雅退出
    def sig_handler(signum, frame):
        server.shutdown()

    signal.signal(signal.SIGTERM, sig_handler)
    signal.signal(signal.SIGINT, sig_handler)

    try:
        while True:
            try:
                server.handle_request()
            except TimeoutError:
                pass  # socket 超时，继续循环
            except OSError:
                break
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()