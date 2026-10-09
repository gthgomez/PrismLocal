package com.prismai.llmhost.generation

import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.tools.AgentToolProtocol

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

    /**
     * Acceptance for a chat turn, deriving the agent instruction block from
     * [settings]. The screen pre-flight and the orchestrator both call this with
     * the same prompt and memory context, so their inputs (including whether the
     * agent instruction block counts) cannot drift apart.
     *
     * [enforceBudget] is false for benchmark presets, which were never
     * budget-checked and must not be newly refused; the model check always runs.
     */
    fun forChat(
        currentModel: String?,
        settings: GenerationSettings,
        prompt: String,
        memoryContext: String,
        enforceBudget: Boolean = true,
    ): Result = evaluate(
        currentModel = currentModel,
        contextLength = settings.contextLength,
        maxTokens = settings.maxTokens,
        prompt = prompt,
        memoryContext = memoryContext,
        instructionText = if (settings.agentEnabled) AgentToolProtocol.instructionBlock() else "",
        enforceBudget = enforceBudget,
    )

    fun evaluate(
        currentModel: String?,
        contextLength: Int,
        maxTokens: Int,
        prompt: String,
        memoryContext: String = "",
        instructionText: String = "",
        enforceBudget: Boolean = true,
    ): Result {
        if (currentModel.isNullOrBlank()) {
            return Result(false, "Select a model before sending a prompt")
        }
        if (enforceBudget && !GenerationBudget.userTurnFits(
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
