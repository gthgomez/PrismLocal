package com.example.llmhost

/**
 * Splits text into overlapping chunks for embedding and RAG retrieval.
 *
 * Strategy: recursive character splitting that prefers natural boundaries:
 * 1. Paragraph breaks (\n\n)
 * 2. Line breaks (\n)
 * 3. Sentence endings (. ! ?)
 * 4. Word boundaries (last space before chunk size)
 * 5. Hard split at chunkSize if no boundary found
 */
object DocumentChunker {

    data class Chunk(
        val text: String,
        val index: Int,
    )

    /**
     * Split [text] into overlapping chunks.
     *
     * @param chunkSize target character count per chunk (default 512)
     * @param overlap   characters of overlap between consecutive chunks (default 64)
     * @return list of chunks in document order
     */
    fun chunk(text: String, chunkSize: Int = 512, overlap: Int = 64): List<Chunk> {
        if (text.isBlank()) return emptyList()
        val safeChunkSize = chunkSize.coerceAtLeast(64)
        val safeOverlap = overlap.coerceIn(0, safeChunkSize / 2)

        val result = mutableListOf<Chunk>()
        var start = 0
        var index = 0

        while (start < text.length) {
            val end = findChunkEnd(text, start, safeChunkSize)
            val chunkText = text.substring(start, end).trim()
            if (chunkText.isNotBlank()) {
                result.add(Chunk(text = chunkText, index = index))
                index++
            }
            // Advance start, accounting for overlap
            val nextStart = if (safeOverlap > 0 && end - safeOverlap > start) {
                end - safeOverlap
            } else {
                end
            }
            // Safety: must make forward progress
            start = if (nextStart <= start) end else nextStart
        }

        return result
    }

    /**
     * Estimate how many chunks a text will produce without actually chunking.
     */
    fun estimateChunkCount(text: String, chunkSize: Int = 512): Int {
        if (text.isBlank()) return 0
        val safeChunkSize = chunkSize.coerceAtLeast(64)
        val estimated = text.length / (safeChunkSize * 3 / 4) // account for overlap waste
        return estimated.coerceAtLeast(1)
    }

    /**
     * Find the end position for a chunk starting at [start] with target size [chunkSize].
     *
     * Tries boundaries in this order:
     * 1. Paragraph break (\n\n) in the search window
     * 2. Line break (\n)
     * 3. Sentence end (. ! ? followed by space or end)
     * 4. Last space within the window (word boundary)
     * 5. Hard cut at start + chunkSize
     */
    private fun findChunkEnd(text: String, start: Int, chunkSize: Int): Int {
        if (start >= text.length) return text.length

        val end = (start + chunkSize).coerceAtMost(text.length)
        if (end >= text.length) return text.length

        // Search backwards from end for natural boundaries
        val searchStart = (start + chunkSize * 2 / 3).coerceAtLeast(start + 1)

        // 1. Paragraph break (\n\n)
        val paraBreak = text.lastIndexOf("\n\n", end - 1)
        if (paraBreak > searchStart) return paraBreak

        // 2. Line break (\n) — but not a double \n\n (already checked)
        val lineBreak = text.lastIndexOf('\n', end - 1)
        if (lineBreak > searchStart) return lineBreak

        // 3. Sentence end (. ! ? followed by space or end of chunk)
        for (pos in end - 1 downTo searchStart) {
            val c = text[pos]
            if ((c == '.' || c == '!' || c == '?') &&
                (pos + 1 >= end || text[pos + 1] == ' ')
            ) {
                return pos + 1
            }
        }

        // 4. Word boundary (space)
        val space = text.lastIndexOf(' ', end - 1)
        if (space > searchStart) return space

        // 5. Hard cut
        return end
    }
}
