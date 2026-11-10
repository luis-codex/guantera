package app.guantera.presentation.maintenance

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
import app.guantera.presentation.port.MaintenanceLevel
import app.guantera.presentation.port.FormOutcome
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
import kotlinx.coroutines.launch
import java.util.TimeZone

/** One row of P5. The screen composes the sentences from these parts. */
data class MaintenanceRowUi(
    val id: Long,
    val name: String,
    val level: MaintenanceLevel,
    val axis: Axis?,
    /** Distance with the thousands dot, or the number of days, depending on [axis]. */
    val amountText: String,
    val amount: Int,
    val origin: DataOrigin,
    val lastKmText: String?,
    val lastDateText: String?,
)

enum class MaintenanceNotice { ServiceSaved, ServiceSavedWithExpense, ItemSaved, ItemDeleted, CatalogRestored, CatalogComplete, SaveFailed, CarMoving }

data class OverviewUi(
    val rows: List<MaintenanceRowUi>,
    val pageIndex: Int,
    val pageCount: Int,
    val totalCount: Int,
    val overdueCount: Int,
    val soonCount: Int,
    val notice: MaintenanceNotice?,
    val restoredCount: Int,
)

enum class ConfirmKind { ParkedCheck, DeleteItem, DeleteExpense }

data class ConfirmUi(val kind: ConfirmKind, val subject: String?)

sealed interface MaintenancePage {
    data class Overview(val ui: OverviewUi) : MaintenancePage

    /** P6. */
    data class ItemForm(
        val isNew: Boolean,
        val name: String,
        val fields: List<FieldUi>,
        val pad: PadExtra,
        val error: FormError?,
    ) : MaintenancePage

    /** P7. */
    data class ServiceForm(
        val itemName: String,
        val fields: List<FieldUi>,
        val pad: PadExtra,
        val error: FormError?,
        val note: String,
    ) : MaintenancePage
}

data class MaintenanceViewState(
    val page: MaintenancePage,
    val confirm: ConfirmUi?,
    val currency: String,
)

class MaintenancePresenter(
    private val data: MaintenanceData,
    private val profile: StateFlow<VehicleProfile>,
    private val gate: FormGate,
    private val clock: Clock,
    private val format: LedgerFormat,
    ticks: Flow<Long>,
    private val scope: CoroutineScope,
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    private sealed interface Mode {
        data object Overview : Mode
        data class EditItem(val item: MaintenanceItem?, val draft: FormDraft) : Mode
        data class Service(val item: MaintenanceItem, val draft: FormDraft) : Mode
    }

    private data class Ui(
        val mode: Mode = Mode.Overview,
        val pageIndex: Int = 0,
        val confirm: ConfirmUi? = null,
        /** The form to open once the driver confirms the car is parked. */
        val pendingOpen: Mode? = null,
        val notice: MaintenanceNotice? = null,
        val restoredCount: Int = 0,
        /** What to run again once the driver says the car is parked (CU-04). */
        val retry: (() -> Unit)? = null,
    )

    private val ui = MutableStateFlow(Ui())

    val state: StateFlow<MaintenanceViewState> = combine(ui, data.items, profile, ticks) { current, items, vehicle, _ ->
        reduce(current, items, vehicle)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        reduce(ui.value, data.items.value, profile.value),
    )

    private fun today(): CalendarDate = CalendarDate.fromMillis(clock.nowMillis(), timeZone)

    // ---- P5 -------------------------------------------------------------------------------

    fun nextPage() = ui.update { it.copy(pageIndex = it.pageIndex + 1, notice = null) }

    fun previousPage() = ui.update { it.copy(pageIndex = (it.pageIndex - 1).coerceAtLeast(0), notice = null) }

    fun addItem() = open(Mode.EditItem(null, newItemDraft()))

    fun openItem(id: Long) {
        val item = data.items.value.firstOrNull { it.id == id } ?: return
        open(Mode.EditItem(item, itemDraft(item)))
    }

    /** "Hecho": opens P7 for the item. */
    fun markDone(id: Long) {
        val item = data.items.value.firstOrNull { it.id == id } ?: return
        open(Mode.Service(item, serviceDraft(item)))
    }

    fun restoreCatalog() {
        scope.launch {
            val result = data.restoreCatalog()
            if (result.outcome == FormOutcome.Saved) {
                ui.update {
                    it.copy(
                        notice = if (result.restored > 0) MaintenanceNotice.CatalogRestored else MaintenanceNotice.CatalogComplete,
                        restoredCount = result.restored,
                        pageIndex = 0,
                    )
                }
            } else {
                refused(result.outcome, ::restoreCatalog)
            }
        }
    }

    // ---- Forms ----------------------------------------------------------------------------

    fun selectField(field: FormField) = updateDraft { it.select(field) }

    fun press(key: PadKey) {
        val today = today()
        updateDraft { it.press(key, today) }
    }

    fun setText(field: FormField, text: String) = updateDraft { it.withText(field, text) }

    /** A text field took the system keyboard. */
    fun focusText() = updateDraft { it.focusText() }

    fun cancel() = ui.update { it.copy(mode = Mode.Overview, confirm = null, pendingOpen = null, retry = null) }

    fun save() {
        when (val mode = ui.value.mode) {
            is Mode.EditItem -> saveItem(mode)
            is Mode.Service -> saveService(mode)
            Mode.Overview -> Unit
        }
    }

    fun askDelete() {
        val mode = ui.value.mode as? Mode.EditItem ?: return
        val item = mode.item ?: return
        ui.update { it.copy(confirm = ConfirmUi(ConfirmKind.DeleteItem, item.name)) }
    }

    fun confirmYes() {
        val current = ui.value
        when (current.confirm?.kind) {
            ConfirmKind.ParkedCheck -> {
                gate.confirm()
                ui.update { it.copy(mode = it.pendingOpen ?: it.mode, pendingOpen = null, confirm = null, retry = null) }
                current.retry?.invoke()
            }
            ConfirmKind.DeleteItem -> {
                val item = (current.mode as? Mode.EditItem)?.item
                ui.update { it.copy(confirm = null) }
                if (item != null) {
                    submit({ ui.update { it.copy(mode = Mode.Overview, notice = MaintenanceNotice.ItemDeleted) } }) { data.delete(item.id) }
                }
            }
            else -> ui.update { it.copy(confirm = null) }
        }
    }

    fun confirmNo() = ui.update { it.copy(confirm = null, pendingOpen = null, retry = null) }

    /** True when the back press was consumed, which leaves a form for the list. */
    fun onBack(): Boolean {
        if (ui.value.confirm != null) {
            confirmNo()
            return true
        }
        if (ui.value.mode == Mode.Overview) return false
        cancel()
        return true
    }

    // ---- Internals ------------------------------------------------------------------------

    private fun open(mode: Mode) {
        if (gate.needsConfirmation()) {
            ui.update { it.copy(confirm = ConfirmUi(ConfirmKind.ParkedCheck, null), pendingOpen = mode, notice = null) }
        } else {
            ui.update { it.copy(mode = mode, notice = null) }
        }
    }

    private fun updateDraft(change: (FormDraft) -> FormDraft) = ui.update { current ->
        when (val mode = current.mode) {
            is Mode.EditItem -> current.copy(mode = mode.copy(draft = change(mode.draft)))
            is Mode.Service -> current.copy(mode = mode.copy(draft = change(mode.draft)))
            Mode.Overview -> current
        }
    }

    private fun newItemDraft() = FormDraft(
        entries = mapOf(
            FormField.IntervalKm to PadEntry(KM_SPEC),
            FormField.IntervalMonths to PadEntry(MONTHS_SPEC),
            FormField.FixedDue to PadEntry.ofDate(null),
            FormField.LastKm to PadEntry(KM_SPEC),
            FormField.LastDate to PadEntry.ofDate(null),
        ),
        active = FormField.IntervalKm,
    )

    private fun itemDraft(item: MaintenanceItem) = FormDraft(
        entries = mapOf(
            FormField.IntervalKm to PadEntry.ofInt(KM_SPEC, item.intervalKm),
            FormField.IntervalMonths to PadEntry.ofInt(MONTHS_SPEC, item.intervalMonths),
            FormField.FixedDue to PadEntry.ofDate(item.fixedDueDate),
            FormField.LastKm to PadEntry.ofInt(KM_SPEC, item.lastServiceKm),
            FormField.LastDate to PadEntry.ofDate(item.lastServiceDate),
        ),
        texts = mapOf(FormField.Name to item.name),
        active = FormField.IntervalKm,
    )

    private fun serviceDraft(item: MaintenanceItem) = FormDraft(
        entries = buildMap {
            put(FormField.ServiceDate, PadEntry.ofDate(today()))
            put(FormField.ServiceKm, PadEntry.ofInt(KM_SPEC, profile.value.odometerKm?.takeIf { it > 0 }))
            put(FormField.Cost, PadEntry(MONEY_SPEC))
            if (item.fixedDueDate != null) put(FormField.NewDue, PadEntry.ofDate(null))
        },
        active = FormField.Cost,
    )

    private fun saveItem(mode: Mode.EditItem) {
        val draft = mode.draft
        val today = today()
        val name = draft.text(FormField.Name).trim()
        if (name.isEmpty()) return fail(FormError.NameRequired, FormField.Name)

        val fixed = when (val reading = draft.date(FormField.FixedDue)) {
            DateReading.Invalid -> return fail(FormError.InvalidDate, FormField.FixedDue)
            DateReading.Empty -> null
            is DateReading.Valid -> reading.date
        }
        val last = when (val reading = draft.date(FormField.LastDate)) {
            DateReading.Invalid -> return fail(FormError.InvalidDate, FormField.LastDate)
            DateReading.Empty -> null
            is DateReading.Valid -> reading.date.also { if (it > today) return fail(FormError.FutureDate, FormField.LastDate) }
        }
        val intervalKm = draft.entry(FormField.IntervalKm).intValue()
        val intervalMonths = draft.entry(FormField.IntervalMonths).intValue()
        if (intervalKm == null && intervalMonths == null && fixed == null) return fail(FormError.NeedsRule, FormField.IntervalKm)

        val previous = mode.item
        val sameRule = previous != null && previous.intervalKm == intervalKm &&
            previous.intervalMonths == intervalMonths && previous.fixedDueDate == fixed
        val item = MaintenanceItem(
                id = previous?.id ?: 0L,
                name = name,
                intervalKm = intervalKm,
                intervalMonths = intervalMonths,
                fixedDueDate = fixed,
                lastServiceKm = draft.entry(FormField.LastKm).intValue(),
                lastServiceDate = last,
                origin = if (sameRule) previous!!.origin else DataOrigin.Own,
        )
        submit({ ui.update { it.copy(mode = Mode.Overview, notice = MaintenanceNotice.ItemSaved) } }) { data.save(item) }
    }

    private fun saveService(mode: Mode.Service) {
        val draft = mode.draft
        val item = mode.item
        val today = today()
        val date = (draft.date(FormField.ServiceDate) as? DateReading.Valid)?.date
            ?: return fail(FormError.InvalidDate, FormField.ServiceDate)
        if (date > today) return fail(FormError.FutureDate, FormField.ServiceDate)
        val km = draft.entry(FormField.ServiceKm).intValue()
        if (km == null && item.intervalKm != null) return fail(FormError.KmRequired, FormField.ServiceKm)

        var newDue: CalendarDate? = null
        if (FormField.NewDue in draft.entries) {
            when (val reading = draft.date(FormField.NewDue)) {
                DateReading.Empty -> if (item.needsNewDueDate) return fail(FormError.NewDueRequired, FormField.NewDue)
                DateReading.Invalid -> return fail(FormError.InvalidDate, FormField.NewDue)
                is DateReading.Valid -> {
                    if (reading.date <= date) return fail(FormError.NewDueTooEarly, FormField.NewDue)
                    newDue = reading.date
                }
            }
        }
        val note = draft.text(FormField.Note).trim().ifEmpty { null }
        val cost = draft.entry(FormField.Cost).minorValue()
        val registration = ServiceRegistration(item.id, date, km, cost, note, newDue)
        val saved = if (cost != null) MaintenanceNotice.ServiceSavedWithExpense else MaintenanceNotice.ServiceSaved
        submit({ ui.update { it.copy(mode = Mode.Overview, notice = saved) } }) { data.registerService(registration) }
    }

    private fun fail(error: FormError, field: FormField?) = updateDraft { it.fail(error, field) }

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
            FormOutcome.NeedsConfirmation ->
                ui.update { it.copy(confirm = ConfirmUi(ConfirmKind.ParkedCheck, null), retry = retry) }
            FormOutcome.CarMoving -> problem(FormError.CarMoving, MaintenanceNotice.CarMoving)
            FormOutcome.StorageFull -> problem(FormError.SaveFull, MaintenanceNotice.SaveFailed)
            FormOutcome.Rejected, FormOutcome.StorageFailed -> problem(FormError.SaveFailed, MaintenanceNotice.SaveFailed)
        }
    }

    /** Inside a form the sentence goes to the form; on the list it is the notice. */
    private fun problem(error: FormError, notice: MaintenanceNotice) {
        if (ui.value.mode == Mode.Overview) ui.update { it.copy(notice = notice) } else fail(error, null)
    }

    private fun reduce(current: Ui, items: List<MaintenanceItem>, vehicle: VehicleProfile): MaintenanceViewState {
        val page: MaintenancePage = when (val mode = current.mode) {
            Mode.Overview -> MaintenancePage.Overview(overview(current, items, vehicle.odometerKm?.takeIf { it > 0 }))
            is Mode.EditItem -> MaintenancePage.ItemForm(
                isNew = mode.item == null,
                name = mode.draft.text(FormField.Name),
                fields = listOf(
                    mode.draft.ui(FormField.IntervalKm, FieldUnit.Km),
                    mode.draft.ui(FormField.IntervalMonths, FieldUnit.Months),
                    mode.draft.ui(FormField.FixedDue, FieldUnit.None),
                    mode.draft.ui(FormField.LastKm, FieldUnit.Km),
                    mode.draft.ui(FormField.LastDate, FieldUnit.None),
                ),
                pad = mode.draft.activeExtra,
                error = mode.draft.error,
            )
            is Mode.Service -> MaintenancePage.ServiceForm(
                itemName = mode.item.name,
                fields = buildList {
                    add(mode.draft.ui(FormField.ServiceDate, FieldUnit.None))
                    add(mode.draft.ui(FormField.ServiceKm, FieldUnit.Km))
                    add(mode.draft.ui(FormField.Cost, FieldUnit.Money))
                    if (FormField.NewDue in mode.draft.entries) add(mode.draft.ui(FormField.NewDue, FieldUnit.None))
                },
                pad = mode.draft.activeExtra,
                error = mode.draft.error,
                note = mode.draft.text(FormField.Note),
            )
        }
        return MaintenanceViewState(page, current.confirm, vehicle.currency)
    }

    private fun overview(current: Ui, items: List<MaintenanceItem>, odometer: Int?): OverviewUi {
        val today = today()
        val ranked = items.map { it to MaintenanceRules.evaluate(it, odometer, today) }
            .sortedWith(
                compareBy<Pair<MaintenanceItem, ItemStatus>> { it.second.level.ordinal }
                    .thenBy { it.second.urgency }
                    .thenBy { it.first.name },
            )
        val pageCount = ((ranked.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val pageIndex = current.pageIndex.coerceIn(0, pageCount - 1)
        val rows = ranked.drop(pageIndex * PAGE_SIZE).take(PAGE_SIZE).map { (item, status) ->
            MaintenanceRowUi(
                id = item.id,
                name = item.name,
                level = status.level,
                axis = status.axis,
                amountText = if (status.axis == Axis.Km) format.whole(status.amount) else status.amount.toString(),
                amount = status.amount,
                origin = item.origin,
                lastKmText = item.lastServiceKm?.let(format::whole),
                lastDateText = item.lastServiceDate?.let(format::date),
            )
        }
        return OverviewUi(
            rows = rows,
            pageIndex = pageIndex,
            pageCount = pageCount,
            totalCount = ranked.size,
            overdueCount = ranked.count { it.second.level == MaintenanceLevel.Overdue },
            soonCount = ranked.count { it.second.level == MaintenanceLevel.Soon },
            notice = current.notice,
            restoredCount = current.restoredCount,
        )
    }

    companion object {
        const val PAGE_SIZE = 3
        private val KM_SPEC = PadSpec.Integer(maxDigits = 7)
        private val MONTHS_SPEC = PadSpec.Integer(maxDigits = 3)
        private val MONEY_SPEC = PadSpec.Decimal(maxIntDigits = 9, decimals = 2)
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
