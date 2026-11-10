package app.guantera.presentation.input

import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.port.MotionStatus
import app.guantera.presentation.port.VehicleStatus
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Every field a form can show. The screens bind each cell to one of these. */
enum class FormField {
    Name, IntervalKm, IntervalMonths, FixedDue, LastKm, LastDate,
    ServiceDate, ServiceKm, Cost, NewDue, Note,
    Amount, ExpenseDate,
}

enum class FieldUnit { None, Km, Months, Money }

/** One cell of a form, ready to draw. [valueText] is empty when the field has no value yet. */
data class FieldUi(
    val field: FormField,
    val valueText: String,
    val unit: FieldUnit,
    val isActive: Boolean,
    val hasError: Boolean,
)

/**
 * "¿El auto está estacionado?" (CU-04). With an unknown driving state, forms open only after the
 * driver confirms. The confirmation lasts ten minutes or until movement is detected.
 */
class FormGate(
    private val status: StateFlow<VehicleStatus>,
    private val clock: Clock,
    scope: CoroutineScope,
) {
    private var confirmedAt: Long? = null

    init {
        scope.launch { status.collect { if (it.motion == MotionStatus.Moving) confirmedAt = null } }
    }

    fun needsConfirmation(): Boolean {
        if (status.value.motion != MotionStatus.Unknown) return false
        val at = confirmedAt ?: return true
        return clock.elapsedRealtimeMillis() - at >= VALID_MILLIS
    }

    fun confirm() {
        confirmedAt = clock.elapsedRealtimeMillis()
    }

    private companion object {
        const val VALID_MILLIS = 10 * 60_000L
    }
}

/** Dates, money and counts as the car shows them. Used only from the main thread. */
class LedgerFormat(private val display: DisplayFormat) {
    private val locale = Locale("es", "AR")
    private val symbols = DecimalFormatSymbols(locale)
    private val wholeMoney = DecimalFormat("#,##0", symbols)
    private val cents = DecimalFormat("#,##0.00", symbols)

    fun whole(value: Int): String = display.whole(value)

    fun date(date: CalendarDate): String = String.format(Locale.ROOT, "%02d/%02d/%04d", date.day, date.month, date.year)

    /** "18.500", or "18.500,50" when there are centavos. */
    fun money(minor: Long): String =
        if (minor % 100 == 0L) wholeMoney.format(minor / 100) else cents.format(minor / 100.0)

    /** Always two decimals, as cost per kilometer needs them. */
    fun moneyExact(value: Double): String = cents.format(value)

    fun kilometers(value: Double): String = wholeMoney.format(Math.round(value))
}

/** Why a form refused to save. The screen turns each into a sentence that also says how to go on. */
enum class FormError {
    NameRequired, NeedsRule, InvalidDate, FutureDate, KmRequired, NewDueRequired, NewDueTooEarly,
    CategoryRequired, AmountRequired,
}

/**
 * The values of one open form: numbers and dates typed on the pad, plus free texts. Immutable.
 * Only the field in [active] receives pad keys.
 */
data class FormDraft(
    val entries: Map<FormField, PadEntry>,
    val texts: Map<FormField, String> = emptyMap(),
    val active: FormField,
    val error: FormError? = null,
    val errorField: FormField? = null,
    /** True while a text field has the system keyboard, so no pad field wears the active border. */
    val textFocus: Boolean = false,
) {
    fun entry(field: FormField): PadEntry = entries.getValue(field)

    fun text(field: FormField): String = texts[field].orEmpty()

    /** Pad fields can be selected; text fields take the system keyboard and ignore this. */
    fun select(field: FormField): FormDraft = if (field in entries) copy(active = field, textFocus = false) else this

    fun focusText(): FormDraft = if (textFocus) this else copy(textFocus = true)

    fun press(key: PadKey, today: CalendarDate): FormDraft =
        copy(
            entries = entries + (active to entry(active).press(key, today)),
            error = null,
            errorField = null,
            textFocus = false,
        )

    fun withText(field: FormField, text: String): FormDraft = copy(texts = texts + (field to text), error = null, errorField = null)

    fun fail(error: FormError, field: FormField?): FormDraft {
        val padField = field?.takeIf { it in entries }
        return copy(
            error = error,
            errorField = field,
            active = padField ?: active,
            textFocus = textFocus && padField == null,
        )
    }

    val activeExtra: PadExtra get() = entry(active).extra

    fun ui(field: FormField, unit: FieldUnit): FieldUi {
        val entry = entry(field)
        val isActive = field == active
        return FieldUi(
            field = field,
            valueText = if (isActive) entry.displayOrBlank() else entry.display(),
            unit = unit,
            isActive = isActive && !textFocus,
            hasError = field == errorField,
        )
    }

    /** A complete real day, null when empty, and a failure when something was typed that is not a day. */
    fun date(field: FormField): DateReading {
        val entry = entry(field)
        return when {
            entry.isEmpty -> DateReading.Empty
            else -> entry.dateValue()?.let { DateReading.Valid(it) } ?: DateReading.Invalid
        }
    }
}

sealed interface DateReading {
    data object Empty : DateReading
    data object Invalid : DateReading
    data class Valid(val date: CalendarDate) : DateReading
}
