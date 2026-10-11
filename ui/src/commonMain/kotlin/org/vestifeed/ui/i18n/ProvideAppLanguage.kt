@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.vestifeed.ui.i18n

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.compose.resources.ComposeEnvironment
import org.jetbrains.compose.resources.DensityQualifier
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.LanguageQualifier
import org.jetbrains.compose.resources.LocalComposeEnvironment
import org.jetbrains.compose.resources.RegionQualifier
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.ScriptQualifier
import org.jetbrains.compose.resources.ThemeQualifier

/**
 * Runs [content] with Compose Resources resolving against [language] — a
 * `values-<code>` qualifier such as `"ru"` — or against the system locale when
 * [language] is null.
 *
 * Compose Resources picks a translation in `rememberResourceEnvironment()`,
 * which reads [LocalComposeEnvironment]: providing it is the only way to change
 * the language at runtime. As of Compose Multiplatform 1.13.0-alpha02 that
 * composition local, [ComposeEnvironment], [ResourceEnvironment] and its
 * qualifiers are all `internal`, and the older public `LocalAppLocale` is gone;
 * the `@Suppress`/`@OptIn` is confined to this file. If a public API lands,
 * replace the body here and drop them.
 */
@OptIn(InternalResourceApi::class)
@Composable
fun ProvideAppLanguage(language: String?, content: @Composable () -> Unit) {
    if (language == null) {
        // Follow the system locale: leave the default environment untouched.
        content()
        return
    }
    val environment = remember(language) { OverrideResourceEnvironment(language) }
    val direction = if (SupportedLanguages.isRtl(language)) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(
        LocalComposeEnvironment provides environment,
        // Compose Resources only picks the translation; an RTL language also
        // needs mirrored layout (and vice versa when overriding an RTL system).
        LocalLayoutDirection provides direction,
    ) {
        content()
    }
}

/**
 * A [ComposeEnvironment] that pins the language while mirroring the default
 * environment's theme and density, so theme/density-qualified resources (none
 * today, but future ones) keep resolving as they normally would.
 */
@OptIn(InternalResourceApi::class)
private class OverrideResourceEnvironment(private val language: String) : ComposeEnvironment {

    @Composable
    override fun rememberEnvironment(): ResourceEnvironment {
        val dark = isSystemInDarkTheme()
        val density = LocalDensity.current
        return remember(language, dark, density) {
            ResourceEnvironment(
                LanguageQualifier(language),
                ScriptQualifier(""),
                RegionQualifier(""),
                ThemeQualifier.selectByValue(dark),
                DensityQualifier.selectByDensity(density.density),
            )
        }
    }
}
