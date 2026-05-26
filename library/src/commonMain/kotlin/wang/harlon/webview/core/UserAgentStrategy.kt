package wang.harlon.webview.core

sealed interface UserAgentStrategy {
    object Default : UserAgentStrategy
    data class Append(val suffix: String) : UserAgentStrategy
    data class Prefix(val prefix: String) : UserAgentStrategy
    data class Override(val value: String) : UserAgentStrategy
}
