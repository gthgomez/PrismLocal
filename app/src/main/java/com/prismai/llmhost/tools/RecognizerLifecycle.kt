package com.prismai.llmhost.tools

/**
 * Tracks whether a SpeechRecognizer is live and requests release exactly once.
 *
 * VoiceIoManager assigned a recognizer in startListening, then onResults and
 * onError both set isListening = false without destroying it. Because
 * stopListening() returns early when !isListening, neither stopListening() nor
 * shutdown() could release it - the recognizer leaked on the ordinary success
 * path, not only on abandonment.
 *
 * A restart replaces the active recognizer while the previous instance may
 * still deliver a late callback. [onRecognizerCreated] records the new instance
 * as the active token, and [onTerminated] ignores a termination whose token is
 * no longer active, so an old recognizer cannot release the current one.
 */
class RecognizerLifecycle {
    var isCreated: Boolean = false
        private set
    var releaseRequested: Boolean = false
        private set
    var releaseCount: Int = 0
        private set

    private var activeToken: Any? = null

    fun onRecognizerCreated(token: Any? = null) {
        activeToken = token
        isCreated = true
        releaseRequested = false
    }

    /**
     * A session ended (results or error); release the recognizer.
     *
     * @param token the recognizer that terminated. Defaults to the active token
     *   so existing callers keep their behavior. A token that no longer matches
     *   the active recognizer is a late callback from a superseded instance and
     *   is ignored.
     * @return true when this call requested the release (at most once per
     *   created recognizer).
     */
    fun onTerminated(token: Any? = activeToken): Boolean {
        if (token !== activeToken) return false
        if (isCreated && !releaseRequested) {
            releaseRequested = true
            releaseCount++
            return true
        }
        return false
    }

    fun shutdown() {
        onTerminated(activeToken)
        isCreated = false
        activeToken = null
    }
}
