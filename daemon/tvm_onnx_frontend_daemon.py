"""
AiFuzzer TVM ONNX Frontend Daemon — 测试 ONNX → TVM 转换路径。

通过 TVM 的 relax.frontend.onnx.from_onnx() 导入 ONNX 模型到 TVM，
编译运行后返回输出 tensor，与 ONNX Runtime 输出对比。

API:
  POST /run_with_output  {"source": "<python code>", "mode": "onnx_export"}
                         → {"success": true, "outputs": {"output_0": {"shape": [1,3,4,4], "dtype": "float32", "data_b64": "..."}}, "elapsed_ms": 100}

  POST /run_onnx_frontend  {"onnx_b64": "<base64_onnx_model>", "inputs": {"x": {"shape": [1,3,4,4], "dtype": "float32", "data_b64": "..."}}}
                           → {"success": true, "outputs": {"output_0": {"shape": [1,3,4,4], "dtype": "float32", "data_b64": "..."}}, "elapsed_ms": 100}

  GET  /health → {"status": "ok", "backend_available": true, "uptime_seconds": 123}
  POST /shutdown → 优雅关闭
"""
import base64
import json
import os
import signal
import sys
import threading
import time
import traceback
from http.server import HTTPServer, BaseHTTPRequestHandler
from io import StringIO, BytesIO

# ── 一次性导入 ──────────────────────────────────────────────
TVM_AVAILABLE = False
IMPORT_ERROR = ""
try:
    import tvm
    from tvm import relax
    import tvm.relax.op as _op
    import numpy as np
    from onnx import helper, TensorProto
    import onnx
    TVM_AVAILABLE = True
except Exception as _ie:
    IMPORT_ERROR = repr(_ie)

EXEC_TIMEOUT_SECONDS = 120
DAEMON_START_TIME = time.time()


def _timeout_handler(signum, frame):
    raise TimeoutError(f"exec timed out after {EXEC_TIMEOUT_SECONDS}s")


def _serialize_tensor(arr: np.ndarray) -> dict:
    """将 numpy 数组序列化为可 JSON 传输的格式。"""
    return {
        "shape": list(arr.shape),
        "dtype": str(arr.dtype),
        "data_b64": base64.b64encode(arr.tobytes()).decode("ascii"),
    }


def _deserialize_tensor(data: dict) -> np.ndarray:
    """从 JSON 格式反序列化 numpy 数组。"""
    arr = np.frombuffer(base64.b64decode(data["data_b64"]), dtype=data["dtype"])
    return arr.reshape(data["shape"])


def onnx_to_np_dtype(elem_type: int) -> str:
    """将 ONNX 元素类型映射到 numpy dtype。"""
    mapping = {
        1: "float32",   # FLOAT
        2: "uint8",     # UINT8
        3: "int8",      # INT8
        4: "uint16",    # UINT16
        5: "int16",     # INT16
        6: "int32",     # INT32
        7: "int64",     # INT64
        10: "float16",  # FLOAT16
        11: "float64",  # DOUBLE
        12: "uint32",   # UINT32
        13: "uint64",   # UINT64
        16: "bool",     # BOOL
    }
    return mapping.get(elem_type, "float32")


def extract_outputs_from_globals(globals_dict: dict) -> dict:
    """从 exec 的 globals 中提取输出 tensor。

    查找所有以 __output_ 开头的变量，作为输出。
    """
    outputs = {}
    for key, val in globals_dict.items():
        if key.startswith("__output_") and isinstance(val, np.ndarray):
            outputs[key] = _serialize_tensor(val)
        elif key.startswith("__output_") and isinstance(val, (list, tuple)):
            for i, v in enumerate(val):
                if isinstance(v, np.ndarray):
                    outputs[f"{key}[{i}]"] = _serialize_tensor(v)
    return outputs


def run_source_with_output(source: str, timeout: int = EXEC_TIMEOUT_SECONDS) -> dict:
    """执行源码，捕获输出 tensor 并返回。

    源码应使用 __output_<name> 变量来标记输出，
    或使用 print("__OUTPUT__:" + json.dumps(...)) 格式。

    也支持标准的 exec 输出捕获（stdout 解析）。
    """
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    old_alarm = None
    sys.stdout = StringIO()
    sys.stderr = StringIO()

    start = time.time()
    exit_code = 0
    globals_dict = {
        "tvm": tvm if TVM_AVAILABLE else None,
        "relax": relax if TVM_AVAILABLE else None,
        "op": _op if TVM_AVAILABLE else None,
        "np": np,
        "onnx": onnx if TVM_AVAILABLE else None,
        "helper": helper if TVM_AVAILABLE else None,
        "TensorProto": TensorProto if TVM_AVAILABLE else None,
        "base64": base64,
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

    # 尝试从 globals 提取输出
    outputs = extract_outputs_from_globals(globals_dict)

    # 也尝试从 stdout 解析
    if not outputs:
        for line in captured_stdout.split("\n"):
            if line.startswith("__OUTPUT__:"):
                try:
                    parsed = json.loads(line[len("__OUTPUT__:"):])
                    if isinstance(parsed, dict):
                        outputs = parsed
                except (json.JSONDecodeError, TypeError):
                    pass

    return {
        "success": success,
        "exit_code": exit_code,
        "stdout": captured_stdout,
        "stderr": captured_stderr,
        "elapsed_ms": elapsed,
        "outputs": outputs,
        "globals_had_outputs": len(outputs) > 0,
    }


def run_onnx_via_tvm_frontend(
    onnx_bytes: bytes,
    input_tensors: dict[str, np.ndarray] | None = None,
    timeout: int = EXEC_TIMEOUT_SECONDS,
) -> dict:
    """通过 TVM 的 ONNX frontend 导入并运行 ONNX 模型。

    流程:
    1. 加载 ONNX 模型
    2. 从 ONNX 模型提取输入 shape 和 dtype
    3. 如果未提供输入，生成随机输入
    4. 用 tvm.relax.frontend.onnx.from_onnx() 导入 TVM
    5. 编译并运行
    6. 返回输出 tensor
    """
    if not TVM_AVAILABLE:
        return {
            "success": False,
            "exit_code": -1,
            "stdout": "",
            "stderr": "TVM not available",
            "elapsed_ms": 0,
            "outputs": {},
        }

    start = time.time()
    captured_stdout = StringIO()
    captured_stderr = StringIO()
    old_stdout = sys.stdout
    old_stderr = sys.stderr

    try:
        sys.stdout = captured_stdout
        sys.stderr = captured_stderr

        # 加载 ONNX 模型
        onnx_model = onnx.load(BytesIO(onnx_bytes))
        onnx.checker.check_model(onnx_model)

        # 从 ONNX 模型提取输入 shape 和 dtype
        input_shapes = {}
        if input_tensors is None:
            input_tensors = {}

        for inp in onnx_model.graph.input:
            name = inp.name
            shape_dims = [d.dim_value for d in inp.type.tensor_type.shape.dim]
            dtype = onnx_to_np_dtype(inp.type.tensor_type.elem_type)
            input_shapes[name] = shape_dims

            if name not in input_tensors:
                # 生成随机输入
                input_tensors[name] = np.random.uniform(0.0, 1.0, size=shape_dims).astype(dtype)

        # 构建 shape_dict
        shape_dict = {name: tensor.shape for name, tensor in input_tensors.items()}

        # 导入 TVM（relax.frontend.onnx 是独立子模块，需单独导入）
        from tvm.relax.frontend.onnx import from_onnx as tvm_from_onnx
        mod = tvm_from_onnx(onnx_model, shape_dict=shape_dict)

        # 编译
        target = tvm.target.Target("cuda" if tvm.cuda().exist else "llvm")
        device = tvm.cuda() if tvm.cuda().exist else tvm.cpu()
        exe = relax.build(mod, target=target)
        vm = relax.VirtualMachine(exe, device)

        # 准备输入（VM main 函数接受位置参数，不是关键字参数）
        tvm_inputs = []
        for name, arr in input_tensors.items():
            tvm_tensor = tvm.runtime.empty(arr.shape, arr.dtype, device=device)
            tvm_tensor.copyfrom(arr)
            tvm_inputs.append(tvm_tensor)

        # 运行
        result = vm["main"](*tvm_inputs)

        # 提取输出
        outputs = {}
        # TVM 多输出返回 tvm_ffi.container.Array（可迭代容器）
        if isinstance(result, (list, tuple)) or (
            hasattr(result, "__iter__") and not hasattr(result, "numpy")
        ):
            for i, val in enumerate(result):
                np_val = val.numpy() if hasattr(val, "numpy") else np.array(val)
                outputs[f"output_{i}"] = _serialize_tensor(np_val)
        else:
            np_val = result.numpy() if hasattr(result, "numpy") else np.array(result)
            outputs["output_0"] = _serialize_tensor(np_val)

        success = True
        exit_code = 0
        print(f"TVM frontend OK: {len(outputs)} outputs", file=sys.stderr)

    except Exception as e:
        success = False
        exit_code = 1
        print(f"TVM frontend FAILED: {e}", file=sys.stderr)
        traceback.print_exc()
        outputs = {}

    elapsed = int((time.time() - start) * 1000)
    sys.stdout = old_stdout
    sys.stderr = old_stderr

    return {
        "success": success,
        "exit_code": exit_code,
        "stdout": captured_stdout.getvalue(),
        "stderr": captured_stderr.getvalue(),
        "elapsed_ms": elapsed,
        "outputs": outputs,
    }


class DaemonRequestHandler(BaseHTTPRequestHandler):
    """HTTP 请求处理器。"""

    def do_POST(self):
        try:
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length)
            req = json.loads(body)
        except Exception as e:
            self._json_response(400, {"error": f"Bad request: {e}"})
            return

        if self.path == "/run_with_output":
            self._handle_run_with_output(req)
        elif self.path == "/run_onnx_frontend":
            self._handle_onnx_frontend(req)
        elif self.path == "/shutdown":
            self._handle_shutdown()
        else:
            self._json_response(404, {"error": f"unknown path: {self.path}"})

    def do_GET(self):
        if self.path == "/health":
            self._handle_health()
        else:
            self._json_response(404, {"error": f"unknown path: {self.path}"})

    def _handle_run_with_output(self, req):
        """运行 Python 源码，返回输出 tensor。"""
        source = req.get("source", "")
        try:
            result = run_source_with_output(source)
            self._json_response(200, result)
        except Exception as e:
            self._json_response(500, {
                "success": False,
                "exit_code": -1,
                "stdout": "",
                "stderr": f"Daemon error: {e}\n{traceback.format_exc()}",
                "elapsed_ms": 0,
                "outputs": {},
            })

    def _handle_onnx_frontend(self, req):
        """接收 ONNX 模型，通过 TVM frontend 导入并运行。"""
        onnx_b64 = req.get("onnx_b64", "")
        inputs_raw = req.get("inputs", {})

        if not onnx_b64:
            self._json_response(400, {
                "success": False, "exit_code": -1,
                "stdout": "", "stderr": "Missing onnx_b64",
                "elapsed_ms": 0, "outputs": {},
            })
            return

        try:
            onnx_bytes = base64.b64decode(onnx_b64)
            input_tensors = {
                name: _deserialize_tensor(data)
                for name, data in inputs_raw.items()
            }
            result = run_onnx_via_tvm_frontend(onnx_bytes, input_tensors)
            self._json_response(200, result)
        except Exception as e:
            self._json_response(500, {
                "success": False, "exit_code": -1,
                "stdout": "", "stderr": f"Daemon error: {e}\n{traceback.format_exc()}",
                "elapsed_ms": 0, "outputs": {},
            })

    def _handle_health(self):
        self._json_response(200, {
            "status": "ok",
            "backend_available": TVM_AVAILABLE,
            "uptime_seconds": int(time.time() - DAEMON_START_TIME),
            "import_error": IMPORT_ERROR if not TVM_AVAILABLE else "",
        })

    def _handle_shutdown(self):
        self._json_response(200, {"status": "shutting_down"})
        threading.Thread(target=self.server.shutdown, daemon=True).start()

    def _json_response(self, status_code, data):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


def self_test():
    """自测试：验证 ONNX → TVM frontend 可用。"""
    if not TVM_AVAILABLE:
        print(f"FAIL: TVM not available: {IMPORT_ERROR}")
        sys.exit(1)

    print("PASS: TVM is available")
    print(f"  TVM version: {tvm.__version__}")

    # 测试简单的 ONNX 模型
    print("\nTesting ONNX → TVM frontend...")
    try:
        # 创建一个简单的 ONNX 模型（Relu）
        X = helper.make_tensor_value_info("X", TensorProto.FLOAT, [1, 3, 4, 4])
        Y = helper.make_tensor_value_info("Y", TensorProto.FLOAT, [1, 3, 4, 4])
        node = helper.make_node("Relu", inputs=["X"], outputs=["Y"])
        graph = helper.make_graph([node], "test", [X], [Y])
        model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 11)])

        onnx_bytes = model.SerializeToString()
        print(f"  ONNX model created: {len(onnx_bytes)} bytes")

        input_tensors = {"X": np.random.randn(1, 3, 4, 4).astype(np.float32)}
        result = run_onnx_via_tvm_frontend(onnx_bytes, input_tensors)

        if result["success"]:
            print(f"  PASS: TVM frontend OK, outputs: {list(result['outputs'].keys())}")
            for name, data in result["outputs"].items():
                print(f"    {name}: shape={data['shape']}, dtype={data['dtype']}")
        else:
            print(f"  FAIL: {result['stderr']}")
            sys.exit(1)
    except Exception as e:
        print(f"  FAIL: {e}")
        traceback.print_exc()
        sys.exit(1)

    print("\nAll tests passed!")


def main():
    port = 34791  # 默认端口（TVM daemon=34789, ONNX=34790, PyTorch=34890）
    if "--port" in sys.argv:
        idx = sys.argv.index("--port")
        if idx + 1 < len(sys.argv):
            port = int(sys.argv[idx + 1])

    if "--test" in sys.argv:
        self_test()
        return

    server = HTTPServer(("127.0.0.1", port), DaemonRequestHandler)
    server.socket.settimeout(1.0)

    server_info = {
        "type": "ready",
        "backend_available": TVM_AVAILABLE,
        "port": port,
        "pid": os.getpid(),
    }
    if not TVM_AVAILABLE:
        server_info["import_error"] = IMPORT_ERROR

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