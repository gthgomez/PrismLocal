package com.prismai.llmhost.model

import com.prismai.llmhost.HuggingFaceModelEntry
import com.prismai.llmhost.util.readTextBounded
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class HuggingFaceSearchResult(
    val repoId: String,
    val modelName: String,
    val downloads: Int,
    val likes: Int,
    val availableFiles: List<HuggingFaceModelFile>,
)

data class HuggingFaceModelFile(
    val fileName: String,
    val quantization: String,
    val sizeBytes: Long,
    val downloadUrl: String,
)

object HuggingFaceSearchEngine {

    private const val HF_API_BASE = "https://huggingface.co/api/models"

    /**
     * Searches Hugging Face Hub for GGUF repositories matching [query].
     */
    fun searchGgufModels(query: String, limit: Int = 15): List<HuggingFaceSearchResult> {
        if (query.isBlank()) return emptyList()
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$HF_API_BASE?search=$encodedQuery&filter=gguf&limit=$limit&full=true"

        val connection = (URL(searchUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "PrismLocalAndroid/1.0")
        }

        return try {
            if (connection.responseCode !in 200..299) return emptyList()
            val text = connection.inputStream.bufferedReader().use { it.readTextBounded() }
            val array = JSONArray(text)
            val results = mutableListOf<HuggingFaceSearchResult>()

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val repoId = item.optString("id")
                if (repoId.isBlank()) continue

                val downloads = item.optInt("downloads", 0)
                val likes = item.optInt("likes", 0)
                val siblings = item.optJSONArray("siblings") ?: JSONArray()
                val files = mutableListOf<HuggingFaceModelFile>()

                for (j in 0 until siblings.length()) {
                    val fileObj = siblings.optJSONObject(j) ?: continue
                    val rfilename = fileObj.optString("rfilename")
                    if (!rfilename.endsWith(".gguf", ignoreCase = true)) continue

                    val quant = extractQuantizationLabel(rfilename)
                    val lfs = fileObj.optJSONObject("lfs")
                    val size = lfs?.optLong("size", -1L)?.takeIf { it > 0 } ?: fileObj.optLong("size", -1L)
                    val downloadUrl = "https://huggingface.co/$repoId/resolve/main/$rfilename"

                    files.add(
                        HuggingFaceModelFile(
                            fileName = rfilename,
                            quantization = quant,
                            sizeBytes = size,
                            downloadUrl = downloadUrl,
                        )
                    )
                }

                if (files.isNotEmpty()) {
                    results.add(
                        HuggingFaceSearchResult(
                            repoId = repoId,
                            modelName = repoId.substringAfterLast('/'),
                            downloads = downloads,
                            likes = likes,
                            availableFiles = files,
                        )
                    )
                }
            }
            results
        } catch (e: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Converts a search result file into a downloadable [HuggingFaceModelEntry].
     */
    fun toModelEntry(repoId: String, file: HuggingFaceModelFile): HuggingFaceModelEntry {
        val cleanId = "${repoId.replace('/', '_')}_${file.quantization.lowercase(Locale.US)}"
        return HuggingFaceModelEntry(
            id = cleanId,
            name = "${repoId.substringAfterLast('/')} (${file.quantization})",
            repoId = repoId,
            fileName = file.fileName,
            expectedBytes = file.sizeBytes,
            expectedSha256 = null,
            license = "Other",
            parameters = "Unknown",
            quantization = file.quantization,
            notes = "Dynamic Hugging Face import from $repoId",
        )
    }

    private fun extractQuantizationLabel(fileName: String): String {
        val upper = fileName.uppercase(Locale.US)
        val quants = listOf(
            "Q2_K", "Q3_K_S", "Q3_K_M", "Q3_K_L", "Q4_0", "Q4_1",
            "Q4_K_S", "Q4_K_M", "Q5_0", "Q5_1", "Q5_K_S", "Q5_K_M",
            "Q6_K", "Q8_0", "F16", "F32", "IQ1_S", "IQ2_XXS", "IQ3_XXS"
        )
        for (q in quants) {
            if (upper.contains(q)) return q
        }
        return "GGUF"
    }
}
