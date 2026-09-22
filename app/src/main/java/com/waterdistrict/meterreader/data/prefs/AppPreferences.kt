package com.waterdistrict.meterreader.data.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Which palette the app uses. */
enum class ThemeChoice {
    /** The default: dark text on white is what stays legible in direct sun. */
    LIGHT,
    DARK,
    /** Follow the phone's own light/dark setting. */
    SYSTEM,
}

/**
 * How this phone is set up for the person carrying it. Kept on the phone, not
 * on the server: it is about the device and the reader's eyes, and nothing in
 * it changes what anyone is billed.
 */
data class ReaderPreferences(
    val theme: ThemeChoice = ThemeChoice.LIGHT,
    /** Everything a little larger — for bright sun, or reading glasses left at home. */
    val largeText: Boolean = false,
    /** Stop the screen switching off mid-household while taking readings. */
    val keepScreenOn: Boolean = true,
)

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("reader_preferences", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<ReaderPreferences> = _state.asStateFlow()

    fun update(transform: (ReaderPreferences) -> ReaderPreferences) {
        val next = transform(_state.value)
        prefs.edit()
            .putString(KEY_THEME, next.theme.name)
            .putBoolean(KEY_LARGE_TEXT, next.largeText)
            .putBoolean(KEY_KEEP_SCREEN_ON, next.keepScreenOn)
            .apply()
        _state.value = next
    }

    private fun load(): ReaderPreferences {
        val defaults = ReaderPreferences()
        return ReaderPreferences(
            theme = prefs.getString(KEY_THEME, null)
                ?.let { name -> ThemeChoice.entries.firstOrNull { it.name == name } }
                ?: defaults.theme,
            largeText = prefs.getBoolean(KEY_LARGE_TEXT, defaults.largeText),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, defaults.keepScreenOn),
        )
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_LARGE_TEXT = "large_text"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    }
}
