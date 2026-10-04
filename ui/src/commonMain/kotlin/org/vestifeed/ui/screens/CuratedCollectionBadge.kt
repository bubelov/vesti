package org.vestifeed.ui.screens

import org.vestifeed.curated.CuratedCollection
import org.vestifeed.curated.CuratedGroup
import org.vestifeed.ui.icons.MaterialSymbols

/**
 * The leading badge for a curated collection: a Material Symbols glyph for a
 * topic, or the country's flag for a country collection.
 */
sealed interface CuratedCollectionBadge {
    /** A Material Symbols glyph, used for the topic collections. */
    data class Symbol(val glyph: String) : CuratedCollectionBadge

    /**
     * A country flag. Material Symbols has no country flags, so this is the one
     * place the app deliberately uses an emoji (see AGENTS.md, "Icons").
     */
    data class Flag(val emoji: String) : CuratedCollectionBadge
}

/**
 * The badge for [collection], or null when it has no mapping so the caller can
 * draw a generic feed icon.
 */
fun curatedCollectionBadge(collection: CuratedCollection): CuratedCollectionBadge? =
    when (collection.group) {
        CuratedGroup.Recommended ->
            topicIcon(collection.name)?.let(CuratedCollectionBadge::Symbol)

        CuratedGroup.Countries ->
            countryCode(collection.name)?.let { CuratedCollectionBadge.Flag(flagEmoji(it)) }
    }

/** The Material Symbols glyph for a known topic collection name. */
private fun topicIcon(name: String): String? = when (name) {
    "Android" -> MaterialSymbols.Android
    "Android Development" -> MaterialSymbols.Code
    "Animal & Wildlife" -> MaterialSymbols.Pets
    "Apple" -> MaterialSymbols.LaptopMac
    "Architecture" -> MaterialSymbols.Architecture
    "Beauty" -> MaterialSymbols.Spa
    "Books" -> MaterialSymbols.MenuBook
    "Business & Economy" -> MaterialSymbols.TrendingUp
    "Cars" -> MaterialSymbols.DirectionsCar
    "Chess" -> MaterialSymbols.Chess
    "Cricket" -> MaterialSymbols.SportsCricket
    "Cryptocurrency" -> MaterialSymbols.CurrencyBitcoin
    "Cyber security" -> MaterialSymbols.Security
    "DIY" -> MaterialSymbols.Handyman
    "Environment" -> MaterialSymbols.Eco
    "Fashion" -> MaterialSymbols.Checkroom
    "Food" -> MaterialSymbols.Restaurant
    "Football" -> MaterialSymbols.SportsSoccer
    "Funny" -> MaterialSymbols.SentimentVerySatisfied
    "Gaming" -> MaterialSymbols.SportsEsports
    "History" -> MaterialSymbols.HistoryEdu
    "Interior design" -> MaterialSymbols.Chair
    "iOS Development" -> MaterialSymbols.DesktopMac
    "Memes" -> MaterialSymbols.Mood
    "Movies" -> MaterialSymbols.Movie
    "Music" -> MaterialSymbols.MusicNote
    "Nature" -> MaterialSymbols.Park
    "News" -> MaterialSymbols.Newspaper
    "Personal finance" -> MaterialSymbols.Savings
    "Photography" -> MaterialSymbols.PhotoCamera
    "Programming" -> MaterialSymbols.Terminal
    "Science" -> MaterialSymbols.Science
    "Space" -> MaterialSymbols.RocketLaunch
    "Sports" -> MaterialSymbols.SportsBasketball
    "Startups" -> MaterialSymbols.Lightbulb
    "Tech" -> MaterialSymbols.Memory
    "Television" -> MaterialSymbols.Tv
    "Tennis" -> MaterialSymbols.SportsTennis
    "Travel" -> MaterialSymbols.Flight
    "UI - UX" -> MaterialSymbols.DesignServices
    "Web Development" -> MaterialSymbols.Web
    else -> null
}

/** The ISO 3166-1 alpha-2 code for a country collection name. */
private fun countryCode(name: String): String? = when (name) {
    "Australia" -> "AU"
    "Bangladesh" -> "BD"
    "Brazil" -> "BR"
    "Canada" -> "CA"
    "France" -> "FR"
    "Germany" -> "DE"
    "Hong Kong SAR China" -> "HK"
    "India" -> "IN"
    "Indonesia" -> "ID"
    "Iran" -> "IR"
    "Ireland" -> "IE"
    "Italy" -> "IT"
    "Japan" -> "JP"
    "Mexico" -> "MX"
    "Myanmar (Burma)" -> "MM"
    "Nigeria" -> "NG"
    "Pakistan" -> "PK"
    "Philippines" -> "PH"
    "Poland" -> "PL"
    "Russia" -> "RU"
    "South Africa" -> "ZA"
    "Spain" -> "ES"
    "Ukraine" -> "UA"
    "United Kingdom" -> "GB"
    "United States" -> "US"
    else -> null
}

/**
 * Builds the flag emoji for a two-letter ISO code from the regional indicator
 * symbols (U+1F1E6…U+1F1FF), so the flag data stays ASCII in source.
 */
private fun flagEmoji(iso2: String): String = buildString {
    val base = 0x1F1E6
    iso2.uppercase().forEach { letter ->
        appendCodePoint(base + (letter - 'A'))
    }
}

private fun StringBuilder.appendCodePoint(codePoint: Int) {
    if (codePoint <= 0xFFFF) {
        append(codePoint.toChar())
    } else {
        val offset = codePoint - 0x10000
        append(((offset shr 10) + 0xD800).toChar())
        append(((offset and 0x3FF) + 0xDC00).toChar())
    }
}
