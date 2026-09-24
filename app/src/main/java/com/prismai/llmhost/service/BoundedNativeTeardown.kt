package com.prismai.llmhost.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** Runs lifecycle cancellation under a bounded lock wait, then always invokes native teardown. */
internal suspend fun runBoundedNativeTeardown(
    lifecycleLock: Mutex,
    lockTimeoutMs: Long,
    cancelAndJoinGeneration: suspend () -> Unit,
    destroyNative: suspend () -> Unit,
    onLockTimeout: (Long) -> Unit,
): Boolean {
    require(lockTimeoutMs >= 0L) { "lockTimeoutMs must be non-negative" }
    val acquired = withTimeoutOrNull(lockTimeoutMs) {
        lifecycleLock.lock()
        true
    } == true
    var lifecycleFailure: Throwable? = null
    if (acquired) {
        try {
            cancelAndJoinGeneration()
        } catch (failure: Throwable) {
            lifecycleFailure = failure
        } finally {
            lifecycleLock.unlock()
        }
    } else {
        onLockTimeout(lockTimeoutMs)
    }
    try {
        destroyNative()
    } catch (destroyFailure: Throwable) {
        if (lifecycleFailure != null) {
            lifecycleFailure.addSuppressed(destroyFailure)
        } else {
            throw destroyFailure
        }
    }
    lifecycleFailure?.let { throw it }
    return acquired
}
