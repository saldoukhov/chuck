import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.exception.OpenAIAPIException
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.varabyte.kotter.foundation.input.*
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.timer.addTimer
import com.varabyte.kotter.runtime.Session
import kotlinx.cinterop.toKString
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import platform.posix.getenv
import kotlin.time.Duration.Companion.seconds

@OptIn(BetaOpenAI::class)
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
                    "Hello, I'm Chuck, your OpenAI assistant. Ask me questions, " +
                            "or type: \n'bye' to exit \n'ok' to start a new conversation \n'sys' to set the system message"
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
                val answer = getAnswer(this, chuck, question)
                section {
                    textLine(answer)
                }.run()
            }
        }
    }
}

fun getQuestion(session: Session, chuck: Chuck, systemMode: Boolean): String {
    with(session) {
        var question by liveVarOf("")
        section {
            text(if (systemMode) "⚙️  " else "🤖 ")
            input()
        }.runUntilSignal {
            onInputEntered {
                question = input
                signal()
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

fun getAnswer(session: Session, chuck: Chuck, question: String): String {
    with(session) {
        var counter by liveVarOf(0)
        var answer by liveVarOf("")
        section {
            scopedState {
                text("💬".repeat(counter))
            }
        }.run {
            addTimer(1.seconds, repeat = true) { counter += 1 }
            answer = chuck.processQuestion(question)
        }
        return answer
    }
}

@OptIn(BetaOpenAI::class)
class Chuck
constructor(private val service: OpenAI, private val model: String) {
    private val conversation: MutableList<ChatMessage> = mutableListOf()
    private val history: MutableList<String> = mutableListOf()
    private var historyIdx: Int = -1
    private var systemMessage: ChatMessage? = null

    suspend fun processQuestion(question: String): String {
        history.add(0, question)
        conversation.add(
            ChatMessage(
                role = ChatRole.User,
                content = question
            )
        )
        var answer = ""
        try {
            val messages = if (systemMessage == null) conversation else listOf(systemMessage!!, *conversation.toTypedArray())
            val chatCompletionRequest = ChatCompletionRequest(
                model = ModelId(model),
                messages = messages
            )
            val completion: ChatCompletion = service.chatCompletion(chatCompletionRequest)
            val response = completion.choices[0].message?.content
            if (response != null) {
                answer = response
                conversation.add(
                    ChatMessage(
                        role = ChatRole.Assistant,
                        content = response
                    )
                )
            }
        } catch (e: Exception) {
            val openAIAPIException = e.cause as? OpenAIAPIException
            answer = (openAIAPIException ?: e).toString()
        }
        return answer
    }

    fun setSystemMessage(question: String) {
        systemMessage = ChatMessage(
            role = ChatRole.System,
            content = question
        )
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
}

