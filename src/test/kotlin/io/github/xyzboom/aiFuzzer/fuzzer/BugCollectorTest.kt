package io.github.xyzboom.aiFuzzer.fuzzer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class BugCollectorTest {

    @Test
    fun `bug collector should ignore known false positives`() {
        val result = TvmBackend.TvmResult(
            success = false, exitCode = 1, stdout = "",
            stderr = "ImportError: no module named tvm", elapsedMs = 0,
            errorCategory = ErrorCategory.UNKNOWN,
            errorSummary = "", sourceFile = ""
        )
        assertFalse(BugCollector.isWorthyBug(result))
    }

    @Test
    fun `collector should save TvmResult with source file`() {
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
        val savedFiles = reportsDir.listFiles { f -> f.name.contains("seed10042") }
        assertNotNull(savedFiles)
        assertTrue(savedFiles.size >= 1)
        val saved = savedFiles.first()
        assertTrue(saved.readText().contains("Bug Report"))
        assertTrue(saved.readText().contains("x = 1"))
        saved.delete()
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