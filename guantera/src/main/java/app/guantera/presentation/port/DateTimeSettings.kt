package app.guantera.presentation.port

/**
 * What the radio's clock settings say. Any null means "could not be read" on this ROM.
 * Read-only: Guantera never changes the date or time.
 */
data class DateTimeInfo(
    val autoTime: Boolean?,
    val autoTimeZone: Boolean?,
    /** Human text such as "America/Argentina/Buenos_Aires (GMT-03:00)", or null if unknown. */
    val timeZoneLabel: String?,
    /** False when this ROM has no date settings screen. */
    val settingsScreenAvailable: Boolean,
)

interface DateTimeSettings {
    /** Never throws. */
    fun read(): DateTimeInfo
}
