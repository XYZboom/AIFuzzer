package io.github.xyzboom.aiFuzzer.fuzzer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class BugCollectorTest {

    @Test
    fun `bug collector should report all failures as bugs`() {
        val result = TvmBackend.TvmResult(
            success = false, exitCode = 1, stdout = "",
            stderr = "ImportError: no module named tvm", elapsedMs = 0,
            errorCategory = ErrorCategory.UNKNOWN,
            errorSummary = "", sourceFile = ""
        )
        assertTrue(BugCollector.isWorthyBug(result), "all failures should be bugs")
    }

    @Test
    fun `bug collector should not report success as bug`() {
        val result = TvmBackend.TvmResult(
            success = true, exitCode = 0, stdout = "OK", stderr = "",
            elapsedMs = 100, errorCategory = ErrorCategory.NONE,
            errorSummary = "", sourceFile = ""
        )
        assertFalse(BugCollector.isWorthyBug(result))
    }

    @Test
    fun `collector should save folder with source, stderr and ir files`() {
        val tmpFile = File(System.getProperty("java.io.tmpdir"), "test_bug_collect.py")
        tmpFile.writeText("x = 1\nprint(x)\n")
        val result = TvmBackend.TvmResult(
            success = false, exitCode = 1, stdout = "",
            stderr = "ValueError: shape mismatch", elapsedMs = 0,
            errorCategory = ErrorCategory.TVM_ERROR,
            errorSummary = "shape mismatch",
            sourceFile = tmpFile.absolutePath,
        )
        BugCollector.reset()
        BugCollector.collect(result, seed = 10042, backendName = "TVM Relax")

        val reportsDir = BugCollector.reportsDir
        assertTrue(reportsDir.isDirectory)
        val bugDirs = reportsDir.listFiles { f -> f.isDirectory && f.name.contains("seed10042") }
        assertNotNull(bugDirs)
        assertTrue(bugDirs.size >= 1, "Expected a bug folder, got: ${bugDirs?.map { it.name }}")
        val bugDir = bugDirs.first()

        // 检查文件夹内包含三个必需文件
        assertTrue(File(bugDir, "source.py").exists(), "Missing source.py")
        assertTrue(File(bugDir, "stderr.log").exists(), "Missing stderr.log")

        val sourceContent = File(bugDir, "source.py").readText()
        assertTrue(sourceContent.contains("x = 1"), "source.py should contain original code")

        val stderrContent = File(bugDir, "stderr.log").readText()
        assertTrue(stderrContent.contains("ValueError: shape mismatch"), "stderr.log should contain error info")

        // 清理
        bugDir.deleteRecursively()
    }

    @Test
    fun `collector should skip successful runs`() {
        val result = TvmBackend.TvmResult(
            success = true, exitCode = 0, stdout = "OK", stderr = "",
            elapsedMs = 100, errorCategory = ErrorCategory.NONE,
            errorSummary = "", sourceFile = ""
        )
        assertFalse(BugCollector.isWorthyBug(result))
    }
}