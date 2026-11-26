package com.prismai.llmhost.generation

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

internal const val PINNED_QWEN_SHA256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
private const val FILE_NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"

internal fun requiredPinnedQwenModel(): File {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val explicitPath = InstrumentationRegistry.getArguments().getString("prismQwenModelPath")
    val model = if (!explicitPath.isNullOrBlank()) {
        File(explicitPath)
    } else {
        // Local APK-only runners cannot pass instrumentation arguments. The
        // optional asset is ignored by Git and never enters an app APK.
        val assets = instrumentation.context.assets
        assumeTrue("Provide prismQwenModelPath or the optional pinned Qwen test asset",
            FILE_NAME in assets.list("smoke-model").orEmpty())
        File(instrumentation.targetContext.cacheDir, FILE_NAME).also { target ->
            if (!target.isFile) {
                val temporary = File.createTempFile("fixture-", ".part", target.parentFile)
                try {
                    assets.open("smoke-model/$FILE_NAME").use { input ->
                        temporary.outputStream().buffered().use { input.copyTo(it, 1024 * 1024) }
                    }
                    assertPinnedQwenHash(temporary)
                    check(temporary.renameTo(target)) { "Could not install the Qwen fixture in the test cache" }
                } finally {
                    temporary.delete()
                }
            }
        }
    }
    assertTrue("Qwen fixture is not readable: $model", model.isFile && model.canRead())
    assertPinnedQwenHash(model)
    return model
}

private fun assertPinnedQwenHash(model: File) {
    val digest = MessageDigest.getInstance("SHA-256")
    model.inputStream().buffered().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    Log.i("PrismQwenFixture", "model_sha256=$hash bytes=${model.length()}")
    assertEquals(PINNED_QWEN_SHA256, hash)
}
