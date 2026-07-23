package com.prismai.llmhost

/**
 * Tool definitions for Phase 7a Background Agent Execution.
 *
 * These tools allow the model to queue background tasks that continue
 * running after the app is backgrounded or the screen is locked.
 * Results are delivered as Android notifications.
 */
object BackgroundAgentToolDefinitions {
    val RUN_IN_BACKGROUND = AgentToolDefinition(
        name = "run_in_background",
        description = "Queue a task to continue running in the background after you close the app or lock the screen. The agent will work on the task and deliver results as a notification.",
        risk = AgentToolRisk.CONFIRM,
        argumentSchema = """{"type":"object","properties":{"prompt":{"type":"string","description":"What to work on in the background"}},"required":["prompt"]}""",
        requiredArguments = setOf("prompt"),
        maxStringLengths = mapOf("prompt" to 2000),
    )

    val CHECK_BACKGROUND_TASKS = AgentToolDefinition(
        name = "check_background_tasks",
        description = "Check the status of queued and completed background tasks.",
        risk = AgentToolRisk.SAFE,
        argumentSchema = """{"type":"object","properties":{}}""",
    )

    val CANCEL_BACKGROUND_TASK = AgentToolDefinition(
        name = "cancel_background_task",
        description = "Cancel a queued background task by ID.",
        risk = AgentToolRisk.CONFIRM,
        argumentSchema = """{"type":"object","properties":{"task_id":{"type":"string"}},"required":["task_id"]}""",
        requiredArguments = setOf("task_id"),
        maxStringLengths = mapOf("task_id" to 50),
    )

    val ALL = listOf(RUN_IN_BACKGROUND, CHECK_BACKGROUND_TASKS, CANCEL_BACKGROUND_TASK)
}
