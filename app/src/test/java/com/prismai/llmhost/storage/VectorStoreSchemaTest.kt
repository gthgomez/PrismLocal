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
    fun createTableSql_declaresEncoderIdentityAndDimensionColumns() {
        assertTrue(VectorStore.CREATE_TABLE_SQL.contains(VectorStore.COL_ENCODER_IDENTITY))
        assertTrue(VectorStore.CREATE_TABLE_SQL.contains(VectorStore.COL_EMBEDDING_DIM))
    }

    @Test
    fun dbVersion_isBumpedForTheEncoderIdentityMigration() {
        assertEquals(3, VectorStore.DB_VERSION)
    }

    @Test
    fun identityToken_distinguishesEncoderArtifactAndRevision() {
        val a = EmbeddingIdentity("sha-a", VectorStore.EMBEDDING_REVISION)
        val sameA = EmbeddingIdentity("sha-a", VectorStore.EMBEDDING_REVISION)
        val otherModel = EmbeddingIdentity("sha-b", VectorStore.EMBEDDING_REVISION)
        val otherRevision = EmbeddingIdentity("sha-a", VectorStore.EMBEDDING_REVISION - 1)

        assertEquals(a.token, sameA.token)
        assertFalse(a.token == otherModel.token)
        assertFalse(a.token == otherRevision.token)
    }

    @Test
    fun isCompatibleIdentity_rejectsRowsFromOtherEncodersAndUnknownStamps() {
        val current = EmbeddingIdentity("sha-a", VectorStore.EMBEDDING_REVISION)

        assertTrue(VectorStore.isCompatibleIdentity(current.token, current))
        assertFalse(VectorStore.isCompatibleIdentity(EmbeddingIdentity("sha-b", VectorStore.EMBEDDING_REVISION).token, current))
        assertFalse(VectorStore.isCompatibleIdentity("", current))
        assertFalse(VectorStore.isCompatibleIdentity(null, current))
    }

    @Test
    fun unknownIdentity_of_buildsFromBlankEncoderId() {
        assertEquals(EmbeddingIdentity.UNKNOWN_ENCODER, EmbeddingIdentity.of(null, 2).encoderId)
        assertEquals(EmbeddingIdentity.UNKNOWN_ENCODER, EmbeddingIdentity.of("   ", 2).encoderId)
        assertEquals("sha-a", EmbeddingIdentity.of("sha-a", 2).encoderId)
    }

    @Test
    fun isUnrecoverableLegacy_identifiesLegacyMigratedAndObsoleteRows() {
        // Obsolete revision
        assertTrue(VectorStore.isUnrecoverableLegacy(1, "rev=2;enc=sha-a", 384))
        // Migrated from v2 to v3 with blank encoder identity and dimension 0
        assertTrue(VectorStore.isUnrecoverableLegacy(2, "", 0))
        assertTrue(VectorStore.isUnrecoverableLegacy(2, null, 384))
        assertTrue(VectorStore.isUnrecoverableLegacy(2, "rev=2;enc=sha-a", 0))
        assertTrue(VectorStore.isUnrecoverableLegacy(2, "   ", 384))

        // Fully valid chunk from another model or current model
        assertFalse(VectorStore.isUnrecoverableLegacy(2, "rev=2;enc=sha-a", 384))
    }
}
