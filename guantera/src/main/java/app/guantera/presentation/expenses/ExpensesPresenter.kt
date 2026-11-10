package app.guantera.presentation.expenses

import app.guantera.presentation.history.HistoryActions
import app.guantera.presentation.history.HistoryBuilder
import app.guantera.presentation.history.HistoryController
import app.guantera.presentation.history.HistoryEntry
import app.guantera.presentation.history.HistoryLayout
import app.guantera.presentation.history.HistoryRowUi
import app.guantera.presentation.history.HistoryState
import app.guantera.presentation.history.HistoryUi
import app.guantera.presentation.history.HistoryUnit
import app.guantera.presentation.history.MoneyNumbers
import app.guantera.presentation.history.PeriodKind
import app.guantera.presentation.history.RowText
import app.guantera.presentation.history.YearMonth
import app.guantera.presentation.history.historyIcon
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
import app.guantera.presentation.port.FormOutcome
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import java.util.TimeZone

/** R23 and R24. */
sealed interface CostPerKm {
    /** Cost of one kilometer, with two decimals and no currency sign. */
    data class Value(val text: String) : CostPerKm

    /** Fewer than 50 km in the period: not enough to divide by. */
    data object TooFewKm : CostPerKm

    /** The use case has not answered for this period yet. */
    data object Calculating : CostPerKm
}

enum class ExpenseNotice { Saved, Deleted, SaveFailed, CarMoving }

data class ExpensesOverviewUi(
    /** Period, layout and records, the same component as Viajes. */
    val history: HistoryUi,
    /** Everything below answers to the period in [history]. */
    val costPerKm: CostPerKm,
    val totalText: String,
    val kmText: String,
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
    private val scope: CoroutineScope,
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    private data class Editing(val existing: Expense?, val category: ExpenseCategory?, val draft: FormDraft)

    private data class Ui(
        val editing: Editing? = null,
        val pendingOpen: Editing? = null,
        val confirm: ConfirmUi? = null,
        val notice: ExpenseNotice? = null,
        val retry: (() -> Unit)? = null,
    )

    private val ui = MutableStateFlow(Ui())
    private val history = HistoryController(::today)
    private val numbers = MoneyNumbers(format)

    /** The answer of CU-18 for the period on screen; null until the use case replies. */
    private val cost = MutableStateFlow<PeriodCost?>(null)

    init {
        scope.launch {
            var shown: Pair<CalendarDate, CalendarDate>? = null
            combine(history.state, data.expenses) { view, _ -> periodOf(view) }.collectLatest { period ->
                // A new period shows "calculating" until the use case answers; the same period just refreshes.
                if (period != shown) cost.value = null
                shown = period
                cost.value = period?.let { (from, to) -> data.costPerKm(from, to) } ?: PeriodCost(0, 0.0, null)
            }
        }
    }

    private val context = combine(profile, ticks, cost) { vehicle, _, pricing -> vehicle to pricing }

    val state: StateFlow<ExpensesViewState> = combine(ui, history.state, data.expenses, context) { current, view, _, (vehicle, pricing) ->
        reduce(current, view, vehicle, pricing)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        reduce(ui.value, history.state.value, profile.value, cost.value),
    )

    /** Nothing is dated in the future (P9 refuses it), so a period that reaches past today ends today. */
    private fun periodOf(view: HistoryState): Pair<CalendarDate, CalendarDate>? {
        val span = view.nav.span
        val until = minOf(span.to, today())
        return if (span.from > until) null else span.from to until
    }

    private fun today(): CalendarDate = CalendarDate.fromMillis(clock.nowMillis(), timeZone)

    // ---- P8 -------------------------------------------------------------------------------

    /** Period, layout and day moves. Any of them also dismisses the last "guardado" notice. */
    val historyActions: HistoryActions = object : HistoryActions {
        override fun selectKind(kind: PeriodKind) = moved { history.selectKind(kind) }
        override fun selectLayout(layout: HistoryLayout) = moved { history.selectLayout(layout) }
        override fun previousPeriod() = moved { history.previous() }
        override fun nextPeriod() = moved { history.next() }
        override fun openDay(date: CalendarDate) = moved { history.openDay(date) }
        override fun closeDay() = moved { history.closeDay() }
        override fun openMonth(month: YearMonth) = moved { history.openMonth(month) }
        override fun stepRangeFrom(delta: Int) = moved { history.stepRangeFrom(delta) }
        override fun stepRangeTo(delta: Int) = moved { history.stepRangeTo(delta) }
        override fun openRow(id: Long) = openExpense(id)

        private fun moved(change: () -> Unit) {
            change()
            ui.update { it.copy(notice = null) }
        }
    }

    /** Called when the user leaves the screen: the next visit starts from the current month. */
    fun reset() {
        cancel()
        history.reset()
    }

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

    fun cancel() = ui.update { it.copy(editing = null, confirm = null, pendingOpen = null, retry = null) }

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
        val expense = Expense(
            id = editing.existing?.id ?: 0L,
            category = category,
            amountMinor = amount,
            date = date,
            note = draft.text(FormField.Note).trim().ifEmpty { null },
            serviceRecordId = editing.existing?.serviceRecordId,
        )
        submit({ ui.update { it.copy(editing = null, notice = ExpenseNotice.Saved) } }) { data.save(expense) }
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
                ui.update { it.copy(editing = it.pendingOpen ?: it.editing, pendingOpen = null, confirm = null, retry = null) }
                current.retry?.invoke()
            }
            ConfirmKind.DeleteExpense -> {
                val existing = current.editing?.existing
                ui.update { it.copy(confirm = null) }
                if (existing != null) {
                    submit({ ui.update { it.copy(editing = null, notice = ExpenseNotice.Deleted) } }) { data.delete(existing.id) }
                }
            }
            else -> ui.update { it.copy(confirm = null) }
        }
    }

    fun confirmNo() = ui.update { it.copy(confirm = null, pendingOpen = null, retry = null) }

    /** True when the back press was consumed: it leaves the form for the list, or an open day for the calendar. */
    fun onBack(): Boolean {
        if (ui.value.confirm != null) {
            confirmNo()
            return true
        }
        if (ui.value.editing == null) return history.closeDay()
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

    /** Writes through the domain and acts on how it ended. A form that failed keeps its draft, so saving again retries. */
    private fun submit(onSaved: () -> Unit, write: suspend () -> FormOutcome) {
        scope.launch {
            val outcome = write()
            if (outcome == FormOutcome.Saved) onSaved() else refused(outcome) { submit(onSaved, write) }
        }
    }

    private fun refused(outcome: FormOutcome, retry: () -> Unit) {
        when (outcome) {
            FormOutcome.Saved -> Unit
            FormOutcome.NeedsConfirmation -> ui.update { it.copy(confirm = ConfirmUi(ConfirmKind.ParkedCheck, null), retry = retry) }
            FormOutcome.CarMoving -> problem(FormError.CarMoving, ExpenseNotice.CarMoving)
            FormOutcome.StorageFull -> problem(FormError.SaveFull, ExpenseNotice.SaveFailed)
            FormOutcome.Rejected, FormOutcome.StorageFailed -> problem(FormError.SaveFailed, ExpenseNotice.SaveFailed)
        }
    }

    private fun problem(error: FormError, notice: ExpenseNotice) {
        if (ui.value.editing == null) ui.update { it.copy(notice = notice) } else failWith(error, null)
    }

    private fun reduce(current: Ui, view: HistoryState, vehicle: VehicleProfile, pricing: PeriodCost?): ExpensesViewState {
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
            ExpensesPage.Overview(overview(current, view, pricing))
        }
        return ExpensesViewState(page, current.confirm, vehicle.currency)
    }

    private fun overview(current: Ui, view: HistoryState, pricing: PeriodCost?): ExpensesOverviewUi {
        val today = today()
        val span = view.nav.span
        // Nothing is dated in the future (P9 refuses it), so a period that reaches past today ends today.
        val until = minOf(span.to, today)
        val inPeriod = if (span.from > until) {
            emptyList()
        } else {
            data.expensesBetween(span.from, until)
                .sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.id })
        }
        val entries = inPeriod.map { expense ->
            HistoryEntry(
                date = expense.date,
                value = expense.amountMinor.toDouble(),
                row = HistoryRowUi(
                    id = expense.id,
                    icon = expense.category.historyIcon(),
                    title = RowText.Category(expense.category),
                    meta = expense.note,
                    valueText = format.money(expense.amountMinor),
                ),
            )
        }
        // The figures come from the same range as the list; until the use case answers, the list's own sum stands in.
        val total = pricing?.totalMinor ?: inPeriod.sumOf { it.amountMinor }
        val km = pricing?.km ?: 0.0
        val cost = when {
            pricing == null -> CostPerKm.Calculating
            pricing.minorPerKm == null -> CostPerKm.TooFewKm
            else -> CostPerKm.Value(format.moneyExact(pricing.minorPerKm / 100.0))
        }
        return ExpensesOverviewUi(
            history = HistoryBuilder.build(view, today, entries, HistoryUnit.Money, numbers),
            costPerKm = cost,
            totalText = format.money(total),
            kmText = format.kilometers(km),
            notice = current.notice,
        )
    }

    companion object {
        private val MONEY_SPEC = PadSpec.Decimal(maxIntDigits = 9, decimals = 2)
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
