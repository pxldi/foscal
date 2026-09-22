package app.foscal.ui.feedback

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-line messages for the snackbar, posted by whatever learned that a write failed.
 *
 * A singleton because the screen that asked for a write is often gone by the time the answer
 * arrives. A channel rather than a shared flow, so a message posted while no screen is collecting
 * (mid-rotation, say) waits for the next one instead of being dropped.
 */
@Singleton
class UserMessages @Inject constructor() {

    private val channel = Channel<String>(Channel.BUFFERED)

    val messages: Flow<String> = channel.receiveAsFlow()

    fun post(text: String) {
        channel.trySend(text)
    }
}
