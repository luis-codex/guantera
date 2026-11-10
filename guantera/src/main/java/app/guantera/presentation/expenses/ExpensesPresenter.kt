package app.guantera.presentation.expenses

import app.guantera.presentation.input.CalendarDate
import app.guantera.presentation.input.DateReading
import app.guantera.presentation.input.FieldUi
import app.guantera.presentation.input.FieldUnit
import app.guantera.presentation.input.FormDraft
import app.guantera.presentation.input.FormError
import app.guantera.presentation.input.FormField
import app.guantera.presentation.input.FormGate
import app.guantera.presentation.input.LedgerFormat
import app.guantera.presentation.input.PadEntry
import app.guantera.presentation.input.PadExtra
import app.guantera.presentation.input.PadKey
import app.guantera.presentation.input.PadSpec
import app.guantera.presentation.maintenance.ConfirmKind
import app.guantera.presentation.maintenance.ConfirmUi
import app.guantera.presentation.port.VehicleProfile
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.TimeZone

enum class ExpensePeriod { ThisMonth, TwelveMonths, All }

/** R23 and R24. */
sealed interface CostPerKm {
    /** Cost of one kilometer, with two decimals and no currency sign. */
    data class Value(val text: String) : CostPerKm

    /** Fewer than 50 km in the period: not enough to divide by. */
    data object TooFewKm : CostPerKm
}

data class ExpenseRowUi(
    val id: Long,
    val category: ExpenseCategory,
    val amountText: String,
    val dateText: String,
    val note: String?,
)

enum class ExpenseNotice { Saved, Deleted }

data class ExpensesOverviewUi(
    val period: ExpensePeriod,
    val costPerKm: CostPerKm,
    val totalText: String,
    val kmText: String,
    val rows: List<ExpenseRowUi>,
    val pageIndex: Int,
    val pageCount: Int,
    val isEmpty: Boolean,
    val notice: ExpenseNotice?,
)

sealed interface ExpensesPage {
    data class Overview(val ui: ExpensesOverviewUi) : ExpensesPage

    /** P9. */
    data class Form(
        val isNew: Boolean,
        val category: ExpenseCategory?,
        val fields: List<FieldUi>,
        val note: String,
        val pad: PadExtra,
        val error: FormError?,
        val linkedToService: Boolean,
    ) : ExpensesPage
}

data class ExpensesViewState(val page: ExpensesPage, val confirm: ConfirmUi?, val currency: String)

class ExpensesPresenter(
    private val data: ExpenseData,
    private val profile: StateFlow<VehicleProfile>,
    private val gate: FormGate,
    private val clock: Clock,
    private val format: LedgerFormat,
    ticks: Flow<Long>,
    scope: CoroutineScope,
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    private data class Editing(val existing: Expense?, val category: ExpenseCategory?, val draft: FormDraft)

    private data class Ui(
        val period: ExpensePeriod = ExpensePeriod.TwelveMonths,
        val pageIndex: Int = 0,
        val editing: Editing? = null,
        val pendingOpen: Editing? = null,
        val confirm: ConfirmUi? = null,
        val notice: ExpenseNotice? = null,
    )

    private val ui = MutableStateFlow(Ui())

    val state: StateFlow<ExpensesViewState> = combine(ui, data.expenses, profile, ticks) { current, expenses, vehicle, _ ->
        reduce(current, expenses, vehicle)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        reduce(ui.value, data.expenses.value, profile.value),
    )

    private fun today(): CalendarDate = CalendarDate.fromMillis(clock.nowMillis(), timeZone)

    // ---- P8 -------------------------------------------------------------------------------

    fun selectPeriod(period: ExpensePeriod) = ui.update { it.copy(period = period, pageIndex = 0, notice = null) }

    fun nextPage() = ui.update { it.copy(pageIndex = it.pageIndex + 1, notice = null) }

    fun previousPage() = ui.update { it.copy(pageIndex = (it.pageIndex - 1).coerceAtLeast(0), notice = null) }

    fun newExpense() = open(Editing(null, null, blankDraft()))

    fun openExpense(id: Long) {
        val expense = data.expenses.value.firstOrNull { it.id == id } ?: return
        open(
            Editing(
                existing = expense,
                category = expense.category,
                draft = FormDraft(
                    entries = mapOf(
                        FormField.Amount to PadEntry.ofMinor(MONEY_SPEC, expense.amountMinor),
                        FormField.ExpenseDate to PadEntry.ofDate(expense.date),
                    ),
                    texts = mapOf(FormField.Note to expense.note.orEmpty()),
                    active = FormField.Amount,
                ),
            ),
        )
    }

    // ---- P9 -------------------------------------------------------------------------------

    fun selectCategory(category: ExpenseCategory) = updateEditing { it.copy(category = category, draft = it.draft.copy(error = null, errorField = null)) }

    fun selectField(field: FormField) = updateEditing { it.copy(draft = it.draft.select(field)) }

    fun press(key: PadKey) {
        val today = today()
        updateEditing { it.copy(draft = it.draft.press(key, today)) }
    }

    fun focusNote() = updateEditing { it.copy(draft = it.draft.focusText()) }

    fun setNote(text: String) = updateEditing { it.copy(draft = it.draft.withText(FormField.Note, text)) }

    fun cancel() = ui.update { it.copy(editing = null, confirm = null, pendingOpen = null) }

    fun save() {
        val editing = ui.value.editing ?: return
        val draft = editing.draft
        val category = editing.category ?: return failWith(FormError.CategoryRequired, null)
        val amount = draft.entry(FormField.Amount).minorValue() ?: return failWith(FormError.AmountRequired, FormField.Amount)
        val date = when (val reading = draft.date(FormField.ExpenseDate)) {
            is DateReading.Valid -> reading.date
            else -> return failWith(FormError.InvalidDate, FormField.ExpenseDate)
        }
        if (date > today()) return failWith(FormError.FutureDate, FormField.ExpenseDate)
        data.save(
            Expense(
                id = editing.existing?.id ?: 0L,
                category = category,
                amountMinor = amount,
                date = date,
                note = draft.text(FormField.Note).trim().ifEmpty { null },
                serviceRecordId = editing.existing?.serviceRecordId,
            ),
        )
        ui.update { it.copy(editing = null, notice = ExpenseNotice.Saved, pageIndex = 0) }
    }

    fun askDelete() {
        val existing = ui.value.editing?.existing ?: return
        ui.update { it.copy(confirm = ConfirmUi(ConfirmKind.DeleteExpense, format.money(existing.amountMinor))) }
    }

    fun confirmYes() {
        val current = ui.value
        when (current.confirm?.kind) {
            ConfirmKind.ParkedCheck -> {
                gate.confirm()
                ui.update { it.copy(editing = it.pendingOpen, pendingOpen = null, confirm = null) }
            }
            ConfirmKind.DeleteExpense -> {
                current.editing?.existing?.let { data.delete(it.id) }
                ui.update { it.copy(editing = null, confirm = null, notice = ExpenseNotice.Deleted) }
            }
            else -> ui.update { it.copy(confirm = null) }
        }
    }

    fun confirmNo() = ui.update { it.copy(confirm = null, pendingOpen = null) }

    /** True when the back press was consumed, which leaves the form for the list. */
    fun onBack(): Boolean {
        if (ui.value.confirm != null) {
            confirmNo()
            return true
        }
        if (ui.value.editing == null) return false
        cancel()
        return true
    }

    // ---- Internals ------------------------------------------------------------------------

    private fun blankDraft() = FormDraft(
        entries = mapOf(
            FormField.Amount to PadEntry(MONEY_SPEC),
            FormField.ExpenseDate to PadEntry.ofDate(today()),
        ),
        active = FormField.Amount,
    )

    private fun open(editing: Editing) {
        if (gate.needsConfirmation()) {
            ui.update { it.copy(confirm = ConfirmUi(ConfirmKind.ParkedCheck, null), pendingOpen = editing, notice = null) }
        } else {
            ui.update { it.copy(editing = editing, notice = null) }
        }
    }

    private fun updateEditing(change: (Editing) -> Editing) = ui.update { current ->
        current.copy(editing = current.editing?.let(change))
    }

    private fun failWith(error: FormError, field: FormField?) = updateEditing { it.copy(draft = it.draft.fail(error, field)) }

    private fun reduce(current: Ui, expenses: List<Expense>, vehicle: VehicleProfile): ExpensesViewState {
        val editing = current.editing
        val page: ExpensesPage = if (editing != null) {
            ExpensesPage.Form(
                isNew = editing.existing == null,
                category = editing.category,
                fields = listOf(
                    editing.draft.ui(FormField.Amount, FieldUnit.Money),
                    editing.draft.ui(FormField.ExpenseDate, FieldUnit.None),
                ),
                note = editing.draft.text(FormField.Note),
                pad = editing.draft.activeExtra,
                error = editing.draft.error,
                linkedToService = editing.existing?.serviceRecordId != null,
            )
        } else {
            ExpensesPage.Overview(overview(current, expenses))
        }
        return ExpensesViewState(page, current.confirm, vehicle.currency)
    }

    private fun overview(current: Ui, expenses: List<Expense>): ExpensesOverviewUi {
        val today = today()
        val from = when (current.period) {
            ExpensePeriod.ThisMonth -> CalendarDate(today.year, today.month, 1)
            ExpensePeriod.TwelveMonths -> today.plusMonths(-12).plusDays(1)
            ExpensePeriod.All -> null
        }
        val inPeriod = expenses.filter { (from == null || it.date >= from) && it.date <= today }
            .sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.id })
        val total = inPeriod.sumOf { it.amountMinor }
        val km = data.tripKilometers(from, today)
        val cost = if (km < MIN_KM_FOR_COST) {
            CostPerKm.TooFewKm
        } else {
            CostPerKm.Value(format.moneyExact(total / 100.0 / km))
        }
        val pageCount = ((inPeriod.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val pageIndex = current.pageIndex.coerceIn(0, pageCount - 1)
        return ExpensesOverviewUi(
            period = current.period,
            costPerKm = cost,
            totalText = format.money(total),
            kmText = format.kilometers(km),
            rows = inPeriod.drop(pageIndex * PAGE_SIZE).take(PAGE_SIZE).map {
                ExpenseRowUi(it.id, it.category, format.money(it.amountMinor), format.date(it.date), it.note)
            },
            pageIndex = pageIndex,
            pageCount = pageCount,
            isEmpty = inPeriod.isEmpty(),
            notice = current.notice,
        )
    }

    companion object {
        const val PAGE_SIZE = 3
        const val MIN_KM_FOR_COST = 50.0
        private val MONEY_SPEC = PadSpec.Decimal(maxIntDigits = 9, decimals = 2)
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
