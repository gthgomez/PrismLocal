package com.prismai.llmhost.service

internal suspend fun completeConfirmedSettingsUpdate(
    reloadModelId: String?,
    reload: suspend (String) -> Boolean,
    onReloadFailed: () -> Unit,
    finalizeDisabledAgent: () -> Unit,
): Boolean {
    if (reloadModelId != null) {
        val reloaded = try {
            reload(reloadModelId)
        } catch (failure: kotlinx.coroutines.CancellationException) {
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
