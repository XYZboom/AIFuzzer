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
import os
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

try:
    import onnxruntime as ort
    ORT_AVAILABLE = True
except Exception as _ie:
    _import_error += "; " + repr(_ie)

import numpy as np

# 每次 exec 的最大超时时间（秒），避免 ONNX Runtime 的 C 扩展无限阻塞
# 使用 signal.alarm 超时保护，与 TVM daemon 一致
EXEC_TIMEOUT_SECONDS = 120
DAEMON_START_TIME = time.time()


def _timeout_handler(signum, frame):
    """SIGALRM 处理器，抛出 TimeoutError。"""
    raise TimeoutError(f"exec timed out after {EXEC_TIMEOUT_SECONDS}s")


def run_source(source: str, timeout: int = EXEC_TIMEOUT_SECONDS) -> dict:
    """执行单次测试源码，捕获 stdout/stderr。

    使用 signal.alarm 进行超时保护：ONNX Runtime 的 C 扩展可能在某些输入下
    无限阻塞，此时 alarm 会触发 TimeoutError，与 TVM daemon 一致。
    """
    global onnx, helper, TensorProto, ort, np
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

        exec(source, {
            "onnx": onnx, "helper": helper, "TensorProto": TensorProto,
            "ort": ort, "np": np,
        })
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


def _serialize_tensor(arr) -> dict:
    """将 numpy 数组序列化为可 JSON 传输的格式。"""
    import base64
    return {
        "shape": list(arr.shape),
        "dtype": str(arr.dtype),
        "data_b64": base64.b64encode(arr.tobytes()).decode("ascii"),
    }


def run_source_with_output(source: str, timeout: int = EXEC_TIMEOUT_SECONDS) -> dict:
    """执行源码，捕获输出 tensor。

    源码应使用 __output_<name> 变量标记输出，或打印 __OUTPUT__:<json> 格式。
    """
    import base64
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    old_alarm = None
    sys.stdout = StringIO()
    sys.stderr = StringIO()

    start = time.time()
    exit_code = 0
    globals_dict = {
        "onnx": onnx, "helper": helper, "TensorProto": TensorProto,
        "ort": ort, "np": np, "base64": base64,
    }

    try:
        if hasattr(signal, "SIGALRM"):
            signal.signal(signal.SIGALRM, _timeout_handler)
            old_alarm = signal.alarm(timeout)
        exec(source, globals_dict)
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
        if hasattr(signal, "SIGALRM") and old_alarm is not None:
            signal.alarm(old_alarm)

    elapsed = int((time.time() - start) * 1000)
    captured_stdout = sys.stdout.getvalue()
    captured_stderr = sys.stderr.getvalue()
    sys.stdout = old_stdout
    sys.stderr = old_stderr

    # 从 globals 提取输出
    outputs = {}
    inputs = {}
    for key, val in globals_dict.items():
        if key.startswith("__output_") and isinstance(val, np.ndarray):
            outputs[key] = _serialize_tensor(val)
        elif key.startswith("__output_") and isinstance(val, (list, tuple)):
            for i, v in enumerate(val):
                if isinstance(v, np.ndarray):
                    outputs[f"{key}[{i}]"] = _serialize_tensor(v)
        elif key.startswith("__input_") and isinstance(val, np.ndarray):
            inputs[key] = _serialize_tensor(val)
        elif key.startswith("__input_") and isinstance(val, (list, tuple)):
            for i, v in enumerate(val):
                if isinstance(v, np.ndarray):
                    inputs[f"{key}[{i}]"] = _serialize_tensor(v)

    # 从 stdout 解析 __OUTPUT__: 前缀
    if not outputs:
        for line in captured_stdout.split("\n"):
            if line.startswith("__OUTPUT__:"):
                try:
                    parsed = json.loads(line[len("__OUTPUT__:"):])
                    if isinstance(parsed, dict):
                        outputs = parsed
                except (json.JSONDecodeError, TypeError):
                    pass

    # 提取 ONNX 模型 bytes（用于后续转换测试）
    models_b64 = {}
    for key, val in globals_dict.items():
        if key.startswith("_model_") and hasattr(val, "SerializeToString"):
            try:
                models_b64[key] = base64.b64encode(val.SerializeToString()).decode("ascii")
            except Exception:
                pass

    return {
        "success": success,
        "exit_code": exit_code,
        "stdout": captured_stdout,
        "stderr": captured_stderr,
        "elapsed_ms": elapsed,
        "outputs": outputs,
        "inputs": inputs,
        "models_b64": models_b64,
    }


class DaemonRequestHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path == "/run":
            self._handle_run()
        elif self.path == "/run_with_output":
            self._handle_run_with_output()
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

    def _handle_run_with_output(self):
        try:
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length)
            req = json.loads(body)
        except Exception as e:
            return self._json_response(400, {"error": repr(e)})

        source = req.get("source", "")
        result = run_source_with_output(source)
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
        "backend_available": ONNX_AVAILABLE and ORT_AVAILABLE,
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