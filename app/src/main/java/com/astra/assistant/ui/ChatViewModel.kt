package com.astra.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.astra.assistant.ai.AiOutcome
import com.astra.assistant.ai.AstraAi
import com.astra.assistant.commands.CommandExecutionResult
import com.astra.assistant.commands.CommandProcessor
import com.astra.assistant.data.AstraDatabase
import com.astra.assistant.data.entity.CorrectionRuleEntity
import com.astra.assistant.data.entity.KnowledgeEntity
import com.astra.assistant.data.entity.MemoryEntity
import com.astra.assistant.data.repository.AstraCorrectionRepository
import com.astra.assistant.data.repository.AstraKnowledgeRepository
import com.astra.assistant.data.repository.AstraMemoryRepository
import com.astra.assistant.model.AssistantState
import com.astra.assistant.model.ChatMessage
import com.astra.assistant.model.MessageSource
import com.astra.assistant.model.PendingAction
import com.astra.assistant.model.Sender
import com.astra.assistant.model.UserPreferences
import com.astra.assistant.network.ResearchService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AstraDatabase.getDatabase(application, viewModelScope)
    private val memoryRepository = AstraMemoryRepository(database.memoryDao())
    private val correctionRepository = AstraCorrectionRepository(database.correctionRuleDao())
    private val knowledgeRepository = AstraKnowledgeRepository(database.knowledgeDao())

    private var shownSetupHint = false

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _assistantState = MutableStateFlow(AssistantState.IDLE)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _rms = MutableStateFlow(0f)
    val rms: StateFlow<Float> = _rms.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _pendingAction = MutableStateFlow<PendingAction?>(null)
    val pendingAction: StateFlow<PendingAction?> = _pendingAction.asStateFlow()

    private val _preferences = MutableStateFlow(UserPreferences())
    val preferences: StateFlow<UserPreferences> = _preferences.asStateFlow()

    val memories: StateFlow<List<MemoryEntity>> = memoryRepository.allMemories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rules: StateFlow<List<CorrectionRuleEntity>> = correctionRepository.allRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val knowledge: StateFlow<List<KnowledgeEntity>> = knowledgeRepository.allKnowledge
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- Simple state setters used by MainActivity ----

    fun setAssistantState(state: AssistantState) {
        _assistantState.value = state
    }

    fun updatePartialTranscript(text: String) {
        _partialTranscript.value = text
    }

    fun clearPartialTranscript() {
        _partialTranscript.value = ""
    }

    fun updateRms(value: Float) {
        _rms.value = value
    }

    fun setNotice(message: String) {
        _notice.value = message
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun savePreferences(prefs: UserPreferences) {
        _preferences.value = prefs
    }

    // Shows a conversation that was handled by the background listening service.
    fun addExchange(userText: String, reply: String) {
        addMessage(userText, Sender.USER, MessageSource.USER)
        addMessage(reply, Sender.ASSISTANT, MessageSource.LOCAL_ROUTER)
    }

    // ---- Commands ----

    fun executeCommand(
        command: String,
        commandProcessor: CommandProcessor,
        speakCallback: (String) -> Unit
    ) {
        addMessage(command, Sender.USER, MessageSource.USER)
        _assistantState.value = AssistantState.PROCESSING
        _partialTranscript.value = ""

        viewModelScope.launch {
            try {
                val rule = correctionRepository.findMatchingRule(command)
                val result: CommandExecutionResult = if (rule != null) {
                    val learned = commandProcessor.processCommand(rule.actionTarget)
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
                    commandProcessor.processCommand(command)
                }
                handleResult(result, speakCallback)
            } catch (e: Exception) {
                deliver(
                    "Something went wrong: ${e.message ?: "unknown error"}",
                    MessageSource.LOCAL_ROUTER,
                    speakCallback
                )
            }
        }
    }

    fun confirmPendingAction(
        commandProcessor: CommandProcessor,
        speakCallback: (String) -> Unit
    ) {
        val action = _pendingAction.value ?: return
        _pendingAction.value = null
        viewModelScope.launch {
            try {
                val result = if (action.actionType == "AI_COMMAND") {
                    AstraAi.runSuggested(commandProcessor, action.target)
                } else {
                    commandProcessor.executeConfirmedAction(action)
                }
                if (action.actionType == "CLEAR_HISTORY") {
                    _messages.value = emptyList()
                    AstraAi.clearHistory()
                }
                val text = when (result) {
                    is CommandExecutionResult.Handled -> result.message
                    is CommandExecutionResult.Error -> result.error
                    is CommandExecutionResult.RequiresConfirmation -> {
                        _pendingAction.value = result.pendingAction
                        result.pendingAction.prompt
                    }
                    is CommandExecutionResult.RouteToAi -> "Done."
                }
                deliver(text, MessageSource.LOCAL_ROUTER, speakCallback)
            } catch (e: Exception) {
                deliver(
                    "Could not complete the action: ${e.message ?: "unknown error"}",
                    MessageSource.LOCAL_ROUTER,
                    speakCallback
                )
            }
        }
    }

    fun cancelPendingAction(speakCallback: (String) -> Unit) {
        _pendingAction.value = null
        deliver("Action cancelled.", MessageSource.LOCAL_ROUTER, speakCallback)
    }

    // ---- Memory, corrections, knowledge ----

    fun addMemory(key: String, value: String, category: String) {
        viewModelScope.launch {
            val result = memoryRepository.saveMemory(key, value, category)
            val error = result.exceptionOrNull()
            if (error != null) {
                _notice.value = error.message ?: "Could not save memory."
            }
        }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch { memoryRepository.deleteMemory(id) }
    }

    fun clearAllMemories() {
        viewModelScope.launch { memoryRepository.clearAll() }
    }

    fun addRule(trigger: String, actionType: String, target: String, label: String) {
        viewModelScope.launch { correctionRepository.saveRule(trigger, actionType, target, label) }
    }

    fun deleteRule(id: String) {
        viewModelScope.launch { correctionRepository.deleteRule(id) }
    }

    fun addKnowledge(
        title: String,
        content: String,
        sourceType: String,
        sourceName: String?,
        tags: String
    ) {
        viewModelScope.launch {
            knowledgeRepository.addKnowledge(title, content, sourceType, sourceName, null, tags)
        }
    }

    fun deleteKnowledge(id: String) {
        viewModelScope.launch { knowledgeRepository.deleteKnowledge(id) }
    }

    // ---- Internals ----

    private suspend fun handleResult(result: CommandExecutionResult, speak: (String) -> Unit) {
        when (result) {
            is CommandExecutionResult.Handled ->
                deliver(result.message, MessageSource.LOCAL_ROUTER, speak)
            is CommandExecutionResult.RequiresConfirmation -> {
                _pendingAction.value = result.pendingAction
                deliver(result.pendingAction.prompt, MessageSource.LOCAL_ROUTER, speak)
            }
            is CommandExecutionResult.RouteToAi -> routeToAi(result.query, speak)
            is CommandExecutionResult.Error ->
                deliver(result.error, MessageSource.LOCAL_ROUTER, speak)
        }
    }

    private suspend fun routeToAi(query: String, speak: (String) -> Unit) {
        val outcome = AstraAi.ask(getApplication<Application>(), query) {
            memoryRepository.getApprovedContextSummary()
        }
        when (outcome) {
            is AiOutcome.Reply -> {
                if (outcome.redactions > 0) {
                    _notice.value = "Private details were removed before sending to Gemini."
                }
                val suggestion = outcome.suggestedCommand
                if (suggestion != null) {
                    val label = outcome.actionLabel ?: suggestion
                    _pendingAction.value = PendingAction("AI_COMMAND", suggestion, "Run: $label?", label)
                    deliver("${outcome.text} Should I run: $label?", MessageSource.ONLINE_AI, speak)
                } else {
                    deliver(outcome.text, MessageSource.ONLINE_AI, speak)
                }
            }
            is AiOutcome.Failure -> deliver(outcome.message, MessageSource.LOCAL_ROUTER, speak)
            AiOutcome.Busy ->
                deliver("I am still working on your last question.", MessageSource.LOCAL_ROUTER, speak)
            AiOutcome.Ignored -> _assistantState.value = AssistantState.IDLE
            AiOutcome.NotConfigured -> {
                if (!shownSetupHint) {
                    shownSetupHint = true
                    _notice.value = "Gemini is not set up yet. Open Settings to connect it."
                }
                deliver(ResearchService.lookup(query), MessageSource.ONLINE_AI, speak)
            }
        }
    }

    private fun deliver(text: String, source: MessageSource, speak: (String) -> Unit) {
        addMessage(text, Sender.ASSISTANT, source)
        _assistantState.value = if (_preferences.value.autoSpeakResponses) {
            AssistantState.SPEAKING
        } else {
            AssistantState.IDLE
        }
        speak(text)
    }

    private fun addMessage(text: String, sender: Sender, source: MessageSource) {
        _messages.value = _messages.value + ChatMessage(text = text, sender = sender, source = source)
    }
}
