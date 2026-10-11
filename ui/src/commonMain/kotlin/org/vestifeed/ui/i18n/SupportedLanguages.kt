package org.vestifeed.ui.i18n

/** A UI language the app ships translations for. */
data class AppLanguage(
    /** The BCP-47/Android language code, matching a `values-<code>` directory. */
    val code: String,
    /** The language's name in its own script, shown in the picker. */
    val displayName: String,
)

/**
 * Every language that has a `composeResources/values-<code>` translation, plus
 * English (which lives in the `values` default, so its code is `en`). This list
 * is maintained by hand and is in sync with the directories under
 * `ui/src/commonMain/composeResources`.
 */
object SupportedLanguages {
    val all: List<AppLanguage> = listOf(
        AppLanguage("en", "English"),
        AppLanguage("af", "Afrikaans"),
        AppLanguage("ar", "العربية"),
        AppLanguage("ca", "Català"),
        AppLanguage("cs", "Čeština"),
        AppLanguage("da", "Dansk"),
        AppLanguage("de", "Deutsch"),
        AppLanguage("el", "Ελληνικά"),
        AppLanguage("es", "Español"),
        AppLanguage("fa", "فارسی"),
        AppLanguage("fi", "Suomi"),
        AppLanguage("fr", "Français"),
        AppLanguage("iw", "עברית"),
        AppLanguage("hu", "Magyar"),
        AppLanguage("it", "Italiano"),
        AppLanguage("ja", "日本語"),
        AppLanguage("ko", "한국어"),
        AppLanguage("nl", "Nederlands"),
        AppLanguage("no", "Norsk"),
        AppLanguage("pl", "Polski"),
        AppLanguage("pt", "Português"),
        AppLanguage("ro", "Română"),
        AppLanguage("ru", "Русский"),
        AppLanguage("sr", "Српски"),
        AppLanguage("sv", "Svenska"),
        AppLanguage("tr", "Türkçe"),
        AppLanguage("uk", "Українська"),
        AppLanguage("vi", "Tiếng Việt"),
        AppLanguage("zh", "中文"),
    )

    /** The display name for [code], or null when it is not a supported language. */
    fun displayName(code: String): String? = all.firstOrNull { it.code == code }?.displayName

    /** Whether [code] is written right-to-left, so its layout must mirror. */
    fun isRtl(code: String): Boolean = code in RTL_CODES

    private val RTL_CODES = setOf("ar", "fa", "he", "iw", "ur")
}
