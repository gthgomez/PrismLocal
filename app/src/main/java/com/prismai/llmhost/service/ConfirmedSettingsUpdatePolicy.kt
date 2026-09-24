package com.prismai.llmhost.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun completeConfirmedSettingsUpdate(
    reloadModelId: String?,
    reload: suspend (String) -> Boolean,
    onReloadFailed: () -> Unit,
    finalizeDisabledAgent: () -> Unit,
): Boolean {
    if (reloadModelId != null) {
        val reloaded = try {
            reload(reloadModelId)
        } catch (failure: CancellationException) {
            try {
                withContext(NonCancellable) { onReloadFailed() }
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        } catch (_: Throwable) {
            false
        }
        if (!reloaded) {
            onReloadFailed()
            return false
        }
    }
    finalizeDisabledAgent()
    return true
}
