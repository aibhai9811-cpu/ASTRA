package com.astra.assistant.commands

import android.content.Context
import android.os.SystemClock
import com.astra.assistant.ai.AiOutcome
import com.astra.assistant.ai.AstraAi
import com.astra.assistant.data.AstraDatabase
import com.astra.assistant.data.repository.AstraCorrectionRepository
import com.astra.assistant.data.repository.AstraMemoryRepository
import com.astra.assistant.model.MessageSource
import com.astra.assistant.model.PendingAction
import com.astra.assistant.network.ResearchService
import com.astra.assistant.service.WakeWord
import kotlinx.coroutines.CoroutineScope
import java.util.Locale

data class EngineResult(
    val text: String,
    val source: MessageSource = MessageSource.LOCAL_ROUTER,
    val ignored: Boolean = false,
    val stopListening: Boolean = false,
    val clearChat: Boolean = false,
    val notice: String? = null
)

// The single brain for both the screen and the background service.
// Order: pending confirmation first, then local commands, then Gemini.
class AssistantEngine(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext
    val processor = CommandProcessor(appContext)
    private val database = AstraDatabase.getDatabase(appContext, scope)
    private val memoryRepository = AstraMemoryRepository(database.memoryDao())
    private val correctionRepository = AstraCorrectionRepository(database.correctionRuleDao())

    private var lastInput = ""
    private var lastInputAt = 0L
    private var shownSetupHint = false

    suspend fun handle(input: String): EngineResult {
        val text = input.trim()
        if (text.isEmpty()) return ignored()

        val pending = ConfirmationManager.current()
        if (pending != null) {
            val handled = handlePending(pending, text)
            if (handled != null) return handled
            // Second unclear answer: the old request was dropped, so treat this as a new command.
        } else if (ConfirmationManager.recentlyResolved() &&
            ConfirmationParser.parse(text) != ConfirmationParser.Answer.UNCLEAR
        ) {
            // A repeated yes or no right after a confirmation must never reach Gemini.
            return ignored()
        }

        val now = SystemClock.elapsedRealtime()
        val normalized = text.lowercase(Locale.ROOT)
        if (normalized == lastInput && now - lastInputAt < 2500) return ignored()
        lastInput = normalized
        lastInputAt = now

        return route(text)
    }

    suspend fun confirmPending(): EngineResult {
        val pending = ConfirmationManager.current()
            ?: return EngineResult("There is nothing waiting for confirmation.")
        if (pending.actionType == "CHOOSE_CONTACT") {
            return EngineResult("Please choose a contact first. Say the number.")
        }
        val action = ConfirmationManager.take()
            ?: return EngineResult("That request expired. Please ask again.")
        return execute(action)
    }

    fun cancelPending(): EngineResult {
        ConfirmationManager.clear()
        return EngineResult("Okay, I cancelled that.")
    }

    private fun ignored() = EngineResult("", ignored = true)

    private suspend fun handlePending(pending: PendingAction, text: String): EngineResult? {
        val spoken = WakeWord.strip(text) ?: text

        if (pending.actionType == "CHOOSE_CONTACT") {
            if (ConfirmationParser.parse(spoken) == ConfirmationParser.Answer.NO) {
                ConfirmationManager.clear()
                return EngineResult("Okay, I cancelled that.")
            }
            val labels = processor.choiceLabels(pending)
            val index = ChoiceParser.parse(spoken, labels)
            if (index != null) {
                ConfirmationManager.take()
                return fromResult(processor.continueWithContact(pending, index))
            }
            return if (ConfirmationManager.noteUnclear()) {
                EngineResult("Please say the number of the contact, like one or two. Or say cancel.")
            } else {
                ConfirmationManager.clear()
                null
            }
        }

        return when (ConfirmationParser.parse(spoken)) {
            ConfirmationParser.Answer.YES -> {
                val action = ConfirmationManager.take()
                if (action == null) {
                    EngineResult("That request expired. Please ask again.")
                } else {
                    execute(action)
                }
            }
            ConfirmationParser.Answer.NO -> {
                ConfirmationManager.clear()
                EngineResult("Okay, I cancelled that.")
            }
            ConfirmationParser.Answer.UNCLEAR -> {
                if (ConfirmationManager.noteUnclear()) {
                    EngineResult("Please say yes or no. Say cancel to drop it.")
                } else {
                    ConfirmationManager.clear()
                    null
                }
            }
        }
    }

    private suspend fun execute(action: PendingAction): EngineResult {
        val result: CommandExecutionResult = try {
            if (action.actionType == "AI_COMMAND") {
                AstraAi.runSuggested(processor, action.target)
            } else {
                processor.executeConfirmedAction(action)
            }
        } catch (e: Exception) {
            CommandExecutionResult.Error("I could not complete that: ${e.message ?: "unknown error"}")
        }
        val engineResult = fromResult(result)
        if (action.actionType == "CLEAR_HISTORY") {
            AstraAi.clearHistory()
            return engineResult.copy(clearChat = true)
        }
        return engineResult
    }

    private fun fromResult(result: CommandExecutionResult): EngineResult = when (result) {
        is CommandExecutionResult.Handled ->
            EngineResult(result.message, stopListening = result.actionType == "STOP_LISTENING")
        is CommandExecutionResult.RequiresConfirmation -> {
            val action = result.pendingAction
            ConfirmationManager.set(action)
            val tail = if (action.actionType == "CHOOSE_CONTACT") "" else " Say yes or no."
            EngineResult(action.prompt + tail)
        }
        is CommandExecutionResult.Error -> EngineResult(result.error)
        is CommandExecutionResult.RouteToAi -> EngineResult("Done.")
    }

    private suspend fun route(text: String): EngineResult {
        val rule = try {
            correctionRepository.findMatchingRule(text)
        } catch (e: Exception) {
            null
        }
        val result: CommandExecutionResult = if (rule != null) {
            val learned = processor.processCommand(rule.actionTarget)
            if (learned is CommandExecutionResult.RouteToAi || learned is CommandExecutionResult.Error) {
                CommandExecutionResult.Handled(
                    "Applied learned correction: ${rule.actionLabel}",
                    rule.actionLabel,
                    rule.actionType
                )
            } else {
                learned
            }
        } else {
            processor.processCommand(text)
        }
        return when (result) {
            is CommandExecutionResult.RouteToAi -> askAi(result.query)
            else -> fromResult(result)
        }
    }

    private suspend fun askAi(query: String): EngineResult {
        val outcome = AstraAi.ask(appContext, query) {
            memoryRepository.getApprovedContextSummary()
        }
        return when (outcome) {
            is AiOutcome.Reply -> {
                val notice = if (outcome.redactions > 0) {
                    "Private details were removed before sending to Gemini."
                } else {
                    null
                }
                val suggestion = outcome.suggestedCommand
                if (suggestion != null) {
                    val label = outcome.actionLabel ?: suggestion
                    ConfirmationManager.set(PendingAction("AI_COMMAND", suggestion, "Run: $label?", label))
                    EngineResult(
                        "${outcome.text} Should I run: $label? Say yes or no.",
                        MessageSource.ONLINE_AI,
                        notice = notice
                    )
                } else {
                    EngineResult(outcome.text, MessageSource.ONLINE_AI, notice = notice)
                }
            }
            is AiOutcome.Failure -> EngineResult(outcome.message)
            AiOutcome.Busy -> EngineResult("I am still working on your last question.")
            AiOutcome.Ignored -> ignored()
            AiOutcome.NotConfigured -> {
                val hint = if (!shownSetupHint) {
                    shownSetupHint = true
                    "Gemini is not set up yet. Open Settings to connect it."
                } else {
                    null
                }
                EngineResult(ResearchService.lookup(query), MessageSource.ONLINE_AI, notice = hint)
            }
        }
    }
}
