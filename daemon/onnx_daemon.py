"""
AiFuzzer ONNX Daemon — 常驻 Python HTTP 服务，通过 HTTP POST 与 JVM 通信。

API:
  POST /run    {"source": "<python code>"}
               → {"success": true, "exit_code": 0, "stdout": "...", "stderr": "...", "elapsed_ms": 1}

  GET  /health → {"status": "ok", "onnx_available": true, "uptime_seconds": 123}

  POST /shutdown → 优雅关闭

使用方法:
  python3 daemon/onnx_daemon.py                  # 启动 daemon（默认端口 34790）
  python3 daemon/onnx_daemon.py --port 8888      # 指定端口
  python3 daemon/onnx_daemon.py --test           # 自测试模式
"""

import json
import signal
import sys
import threading
import time
import traceback
from http.server import HTTPServer, BaseHTTPRequestHandler
from io import StringIO

# 一次性导入 ONNX + ONNX Runtime
ONNX_AVAILABLE = False
ORT_AVAILABLE = False
_import_error = ""

try:
    from onnx import helper, TensorProto
    import onnx
    ONNX_AVAILABLE = True
except Exception as _ie:
    _import_error = repr(_ie)
    _import_detail = traceback.format_exc()

try:
    import onnxruntime as ort
    ORT_AVAILABLE = True
except Exception as _ie:
    _import_error += "; " + repr(_ie)

EXEC_TIMEOUT_SECONDS = 120
DAEMON_START_TIME = time.time()


def _timeout_handler(signum, frame):
    raise TimeoutError(f"exec timed out after {EXEC_TIMEOUT_SECONDS}s")


def run_source(source: str, timeout: int = EXEC_TIMEOUT_SECONDS) -> dict:
    """执行单次测试源码，捕获 stdout/stderr。"""
    global onnx, helper, TensorProto, ort
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    sys.stdout = StringIO()
    sys.stderr = StringIO()

    start = time.time()
    exit_code = 0
    try:
        if hasattr(signal, "SIGALRM"):
            signal.signal(signal.SIGALRM, _timeout_handler)
            signal.alarm(timeout)

        exec(source, {"onnx": onnx, "helper": helper, "TensorProto": TensorProto,
                      "ort": ort, "np": __import__("numpy")})
        success = True
    except SystemExit as e:
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
        if hasattr(signal, "SIGALRM"):
            signal.alarm(0)

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
        try:
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length)
            req = json.loads(body)
        except Exception as e:
            return self._json_response(400, {"error": repr(e)})

        source = req.get("source", "")
        result = run_source(source)
        return self._json_response(200, result)

    def _handle_health(self):
        return self._json_response(200, {
            "status": "ok",
            "onnx_available": ONNX_AVAILABLE,
            "ort_available": ORT_AVAILABLE,
            "uptime_seconds": int(time.time() - DAEMON_START_TIME),
        })

    def _handle_shutdown(self):
        self._json_response(200, {"status": "shutting down"})
        threading.Thread(target=self.server.shutdown, daemon=True).start()

    def _json_response(self, status_code, data):
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(data).encode("utf-8"))

    def log_message(self, format, *args):
        pass  # 不输出 HTTP 日志到 stdout


def main():
    import os

    port = 34790  # 默认端口
    if "--port" in sys.argv:
        idx = sys.argv.index("--port")
        if idx + 1 < len(sys.argv):
            port = int(sys.argv[idx + 1])

    if "--test" in sys.argv:
        print(f"ONNX available: {ONNX_AVAILABLE}")
        print(f"ONNX Runtime available: {ORT_AVAILABLE}")
        if ONNX_AVAILABLE:
            import onnx
            print(f"ONNX version: {onnx.__version__}")
        if ORT_AVAILABLE:
            print(f"ONNX Runtime version: {ort.__version__}")
        if ONNX_AVAILABLE and ORT_AVAILABLE:
            print("All OK!")
        return

    server = HTTPServer(("127.0.0.1", port), DaemonRequestHandler)
    server.socket.settimeout(1.0)

    # 打印 JSON 就绪信息（JVM 将读取此信息获取端口等）
    server_info = {
        "type": "ready",
        "tvm_available": ONNX_AVAILABLE and ORT_AVAILABLE,
        "port": port,
        "pid": os.getpid(),
    }
    if not (ONNX_AVAILABLE and ORT_AVAILABLE):
        server_info["import_error"] = _import_error

    print(json.dumps(server_info), flush=True)

    def sig_handler(signum, frame):
        server.shutdown()

    signal.signal(signal.SIGTERM, sig_handler)
    signal.signal(signal.SIGINT, sig_handler)

    try:
        while True:
            try:
                server.handle_request()
            except TimeoutError:
                pass
            except OSError:
                break
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
