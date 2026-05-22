package wang.harlon.webview.core

sealed interface LoadingState {
    object Idle : LoadingState
    object Loading : LoadingState
    object Finished : LoadingState

    data class Error(
        val code: Int,
        val description: String,
        val failingUrl: String?,
    ) : LoadingState
}
