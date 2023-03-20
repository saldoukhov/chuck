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
import kotlinx.cinterop.toKString
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.stdout
import kotlin.system.exitProcess

@OptIn(BetaOpenAI::class)
fun main(args: Array<String>) {
    val apiKey = getenv("OPENAI_API_KEY")?.toKString()
    val token = requireNotNull(apiKey) { "OPENAI_API_KEY environment variable must be set." }
    val parser = ArgParser("./chuck.kexe %Model Id%")
    val model by parser.argument(ArgType.String, description = "Model Id to use (gpt-4, gpt-3.5-turbo, etc.)")
    parser.parse(args)
    println("Hello, I'm Chuck, your OpenAI assistant")
    runBlocking {
        val openAI = OpenAI(OpenAIConfig(token, LogLevel.None))
        val conversation = mutableListOf<ChatMessage>()
        while (true) {
            print("🤖 ")
            val input = readln()
            if (input == "exit") {
                break
            }
            conversation.add(
                ChatMessage(
                role = ChatRole.User,
                content = input
            ))
            val progress = launch {
                while (true) {
                    print("💬")
                    delay(2000)
                }
            }
            hideCursor()
            try {
                val chatCompletionRequest = ChatCompletionRequest(
                    model = ModelId(model),
                    messages = conversation
                )
                val completion: ChatCompletion = openAI.chatCompletion(chatCompletionRequest)
                val response = completion.choices[0].message?.content ?: break
                println(response)
                conversation.add(
                    ChatMessage(
                    role = ChatRole.Assistant,
                    content = response
                ))
            } catch (e: Exception) {
                val openAIAPIException = e.cause as? OpenAIAPIException
                println(openAIAPIException ?: e.message)
                break
            } finally {
                progress.cancelAndJoin()
                showCursor()
            }
        }
    }
    exitProcess(0)
}

fun hideCursor() {
    fputs("\u001b[?25l", stdout)
    fflush(stdout)
}

fun showCursor() {
    fputs("\u001b[?25h", stdout)
    fflush(stdout)
}