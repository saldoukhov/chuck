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
                if (question.startsWith('@')) {
                    val temperature = question.substring(1..3).toDoubleOrNull()
                    if (temperature != null && temperature in 0.0..2.0) {
                        chuck.setTemperature(temperature)
                        return@run
                    }
                }
                chuck.addAnswer(getChuckAnswer(this, chuck, question))
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

fun getChuckAnswer(session: Session, chuck: Chuck, question: String): String {
    with(session) {
        var answer = ""
        section {
        }.runUntilSignal {
            var cancelled = false
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    chuck.processQuestion(question)
                        .takeWhile { !cancelled }
                        .collect {
                            answer += it
                            print(it)
                        }
                } catch (_: Exception) {
                }
                println('\n')
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
        return answer
    }
}

fun printHelp(session: Session) {
    with(session) {
        section {
            red { text("\tsys") }; textLine("  to set the system message")
            red { text("\tesc") }; textLine("  to stop the answer before completion")
            red { text("\t??") }; textLine("   to see the current conversation system message and questions")
            red { text("\tok") }; textLine("   to start a new conversation")
            red { text("\t@#.#") }; textLine(" to set a temperature. #.# is a number between 0.0 and 2.0. Default value 1.0. Higher - more random answers.")
            red { text("\tbye") }; textLine("  to exit")
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
    private var temperature: Double? = null

    fun processQuestion(question: String): Flow<String> {
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
            messages = messages,
            temperature = temperature
        )
        val completion: Flow<ChatCompletionChunk> = service.chatCompletions(chatCompletionRequest)
        return completion.map { it.choices[0].delta?.content }.filterNotNull()
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

    fun setTemperature(value: Double) {
        temperature = value
    }

    fun clear() {
        systemMessage = null
        temperature = null
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
        if (temperature != null) {
            state.add(0, "🌡️️  " + temperature.toString())
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