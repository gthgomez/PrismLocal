package com.prismai.llmhost.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure coverage for the embedding-revision guard and the cosine length
 * guard. Deliberately avoids constructing [VectorStore], which needs an
 * Android [android.content.Context] and a real SQLite database.
 */
class VectorStoreSchemaTest {

    @Test
    fun cosineSimilarity_returnsZeroOnDimensionMismatch() {
        val a = FloatArray(4) { 1f }
        val b = FloatArray(8) { 1f }
        assertEquals(0f, VectorStore.cosineSimilarity(a, b), 0.0001f)
    }

    @Test
    fun cosineSimilarity_returnsZeroWhenEitherVectorIsEmpty() {
        assertEquals(0f, VectorStore.cosineSimilarity(FloatArray(0), FloatArray(0)), 0.0001f)
        assertEquals(0f, VectorStore.cosineSimilarity(FloatArray(0), FloatArray(4) { 1f }), 0.0001f)
        assertEquals(0f, VectorStore.cosineSimilarity(FloatArray(4) { 1f }, FloatArray(0)), 0.0001f)
    }

    @Test
    fun isCurrentRevision_acceptsRowsWrittenByThisBuild() {
        assertTrue(VectorStore.isCurrentRevision(VectorStore.EMBEDDING_REVISION))
    }

    @Test
    fun isCurrentRevision_rejectsEarlierRevisions() {
        assertFalse(VectorStore.isCurrentRevision(VectorStore.EMBEDDING_REVISION - 1))
    }

    @Test
    fun createTableSql_declaresEmbeddingRevisionColumn() {
        assertTrue(VectorStore.CREATE_TABLE_SQL.contains(VectorStore.COL_EMBEDDING_REVISION))
    }

    @Test
    fun dbVersion_isBumpedForTheRevisionMigration() {
        assertEquals(2, VectorStore.DB_VERSION)
    }
}
