package com.prismai.llmhost.generation

/**
 * The single rule deciding whether a send is accepted.
 *
 * The screen used to clear the draft and fire generateSafely, and only then
 * did the orchestrator discover there was no model or that the turn did not fit
 * - so the composed text was destroyed by a refusal the user never intended.
 *
 * Both the screen and the orchestrator consult this, so the pre-flight answer
 * and the actual refusal cannot drift apart.
 */
object SendAcceptance {

    data class Result(val accepted: Boolean, val reason: String?)

    fun evaluate(
        currentModel: String?,
        contextLength: Int,
        maxTokens: Int,
        prompt: String,
        memoryContext: String = "",
        instructionText: String = "",
    ): Result {
        if (currentModel.isNullOrBlank()) {
            return Result(false, "Select a model before sending a prompt")
        }
        if (!GenerationBudget.userTurnFits(
                contextLength = contextLength,
                maxTokens = maxTokens,
                userPrompt = prompt,
                memoryContext = memoryContext,
                instructionText = instructionText,
            )
        ) {
            return Result(false, "This message is too long for the context window")
        }
        return Result(true, null)
    }
}
