import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.varabyte.kotter.foundation.input.*
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.Session
import kotlinx.cinterop.toKString
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import platform.posix.getenv

fun main(args: Array<String>) {
    val apiKey = getenv("OPENAI_API_KEY")?.toKString()
    val token = requireNotNull(apiKey) { "OPENAI_API_KEY environment variable must be set." }
    val parser = ArgParser("./chuck.kexe %Model Id%")
    val model by parser.argument(ArgType.String, description = "Model Id to use (gpt-4, gpt-3.5-turbo, etc.)")
    val oks = listOf("ok", "reset", "new")
    val byes = listOf("bye", "exit", "quit", "q")
    parser.parse(args)
    val chuck = Chuck(OpenAI(OpenAIConfig(token, LogLevel.None)), model)
    session {
        run {
            section {
                textLine(
                    "\nHello, I'm Chuck, your OpenAI assistant. Ask me questions, or type ? for help\n"
                )
            }
        }.run()
        var systemMode = false
        while (true) {
            run {
                val question = getQuestion(this, chuck, systemMode)
                when (question.lowercase()) {
                    "sys" -> {
                        systemMode = true
                        return@run
                    }

                    "?" -> {
                        printHelp(this)
                        return@run
                    }

                    "??" -> {
                        printState(this, chuck.getState())
                        return@run
                    }

                    in oks -> {
                        chuck.clear()
                        systemMode = false
                        return@run
                    }

                    in byes -> return@session
                }
                if (systemMode) {
                    systemMode = false
                    chuck.setSystemMessage(question)
                    return@run
                }
                chuck.addAnswer(getChuckAnswerAsBuffer(this, chuck, question))
            }
        }
    }
}

fun getQuestion(session: Session, chuck: Chuck, systemMode: Boolean): String {
    with(session) {
        var question by liveVarOf("")
        section {
            text(if (systemMode) "⚙️  " else "🤖 ")
            input(initialText = if (systemMode) chuck.getSystemMessage() else "")
        }.runUntilSignal {
            onInputEntered {
                question = input.trim()
                if (question.isNotEmpty() || systemMode) {
                    signal()
                }
            }
            onKeyPressed {
                when (key) {
                    Keys.UP -> {
                        setInput(chuck.getHistory(true))
                    }

                    Keys.DOWN -> {
                        setInput(chuck.getHistory(false))
                    }
                }
            }
        }
        return question
    }
}

fun getChuckAnswerAsBuffer(session: Session, chuck: Chuck, question: String): String {
    val bufferSize = 30
    with(session) {
        var currentAnswer: Chuck.Answer = Chuck.Answer()
        var completed = false
        section {
            val lines = if (completed || currentAnswer.lines.count() <= bufferSize)
                currentAnswer.lines
            else
                listOf("^".repeat(64)) + currentAnswer.lines.takeLast(bufferSize)
            for (line in lines) {
                textLine(line)
            }
            val cl = currentAnswer.currentLine
            if (cl != null) {
                text(cl)
            }
        }.runUntilSignal {
            var cancelled = false
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    chuck.processQuestionAsBuffer(question)
                        .takeWhile { !cancelled }
                        .collect {
                            currentAnswer = it
                            rerender()
                        }
                    completed = true
                } catch (_: Exception) {
                }
                rerender()
                signal()
            }

            onKeyPressed {
                when (key) {
                    Keys.ESC -> {
                        cancelled = true
                    }
                }
            }
        }
        return currentAnswer.lines.joinToString(separator = "\n")
    }
}

fun printHelp(session: Session) {
    with(session) {
        section {
            red { text("\tsys") }; textLine(" to set the system message")
            red { text("\tesc") }; textLine(" to stop the answer before completion")
            red { text("\t??") }; textLine("  to see the current conversation system message and questions")
            red { text("\tok") }; textLine("  to start a new conversation")
            red { text("\tbye") }; textLine(" to exit")
        }.run()
    }
}

fun printState(session: Session, state: List<String>) {
    with(session) {
        section {
            state.forEach {
                textLine(it)
            }
        }.run()
    }
}

@OptIn(BetaOpenAI::class)
class Chuck(private val service: OpenAI, private val model: String) {
    private val conversation: MutableList<ChatMessage> = mutableListOf()
    private val history: MutableList<String> = mutableListOf()
    private var historyIdx: Int = -1
    private var systemMessage: ChatMessage? = null

    private fun processQuestion(question: String): Flow<String> {
        history.add(0, question)
        conversation.add(
            ChatMessage(
                role = ChatRole.User,
                content = question
            )
        )
        val messages = if (systemMessage == null) conversation else listOf(systemMessage!!, *conversation.toTypedArray())
        val chatCompletionRequest = ChatCompletionRequest(
            model = ModelId(model),
            messages = messages
        )
        val completion: Flow<ChatCompletionChunk> = service.chatCompletions(chatCompletionRequest)
        return completion.map { it.choices[0].delta?.content }.filterNotNull()
    }

    data class Answer(val lines: MutableList<String> = mutableListOf(), var currentLine: String? = null)

    fun processQuestionAsBuffer(question: String): Flow<Answer> {
        val answer = Answer()
        return processQuestion(question).transform { chunk ->
            val cl = (answer.currentLine ?: "") + chunk
            if (cl.endsWith('\n')) {
                answer.lines.add(cl.trimEnd('\n'))
                answer.currentLine = null
            } else {
                answer.currentLine = cl
            }
            emit(answer)
        }
    }

    fun getSystemMessage(): String {
        return systemMessage?.content ?: ""
    }

    fun setSystemMessage(question: String) {
        systemMessage = if (question.isEmpty()) {
            null
        } else {
            history.add(0, question)
            ChatMessage(
                role = ChatRole.System,
                content = question
            )
        }
    }

    fun clear() {
        systemMessage = null
        conversation.clear()
    }

    fun getHistory(prev: Boolean): String {
        if (history.size == 0) {
            return ""
        }
        if (prev) {
            historyIdx += 1
            historyIdx = historyIdx.coerceAtMost(history.size - 1)
        } else {
            historyIdx -= 1
            historyIdx = historyIdx.coerceAtLeast(-1)
            if (historyIdx < 0) {
                return ""
            }
        }
        return history[historyIdx]
    }

    fun getState(): List<String> {
        val state = conversation.filter { it.role != ChatRole.Assistant }.map { it.content }.toMutableList()
        val systemMsg = systemMessage
        if (systemMsg != null) {
            state.add(0, "⚙️  " + systemMsg.content)
        }
        return state
    }

    fun addAnswer(answer: String) {
        conversation.add(
            ChatMessage(
                role = ChatRole.Assistant,
                content = answer
            )
        )
    }
}