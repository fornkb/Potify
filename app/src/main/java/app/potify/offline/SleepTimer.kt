package app.potify.offline

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared between the screen and the playback service, which pauses playback when it fires. */
object SleepTimer {
    sealed interface State {
        data object Off : State
        data class At(val deadline: Long) : State {
            fun remainingMillis() = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        }
        data object EndOfTrack : State
    }

    private val mutableState = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = mutableState

    fun startMinutes(minutes: Int) {
        mutableState.value = State.At(SystemClock.elapsedRealtime() + minutes * 60_000L)
    }

    fun endOfTrack() {
        mutableState.value = State.EndOfTrack
    }

    fun cancel() {
        mutableState.value = State.Off
    }
}
