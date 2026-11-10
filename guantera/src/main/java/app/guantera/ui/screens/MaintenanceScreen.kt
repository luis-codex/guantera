package app.guantera.ui.screens

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import app.guantera.R
import app.guantera.databinding.ItemMaintenanceRowBinding
import app.guantera.databinding.PageItemFormBinding
import app.guantera.databinding.PageMaintenanceOverviewBinding
import app.guantera.databinding.PageServiceFormBinding
import app.guantera.databinding.ScreenMaintenanceBinding
import app.guantera.presentation.input.FormError
import app.guantera.presentation.input.FormField
import app.guantera.presentation.maintenance.Axis
import app.guantera.presentation.maintenance.MaintenanceNotice
import app.guantera.presentation.maintenance.MaintenancePage
import app.guantera.presentation.maintenance.MaintenancePresenter
import app.guantera.presentation.maintenance.MaintenanceRowUi
import app.guantera.presentation.maintenance.MaintenanceViewState
import app.guantera.presentation.maintenance.OverviewUi
import app.guantera.presentation.port.MaintenanceLevel
import app.guantera.ui.Screen
import app.guantera.ui.components.hideSystemKeyboard

/** P5 "¿Qué toca hacer?", with its forms P6 (editar ítem) and P7 (registrar service) inside the same screen. */
class MaintenanceScreen(
    parent: ViewGroup,
    private val presenter: MaintenancePresenter,
    private val onKeyboardClosed: () -> Unit,
) : Screen() {
    private val inflater = LayoutInflater.from(parent.context)
    private val binding = ScreenMaintenanceBinding.inflate(inflater, parent, false)
    private val context = parent.context
    override val view = binding.root

    private var overview: PageMaintenanceOverviewBinding? = null
    private var itemForm: PageItemFormBinding? = null
    private var serviceForm: PageServiceFormBinding? = null

    override suspend fun observe() = presenter.state.collect(::render)

    override fun onBack(): Boolean = presenter.onBack()

    override fun onLeave() {
        presenter.cancel()
    }

    private fun render(state: MaintenanceViewState) {
        when (val page = state.page) {
            is MaintenancePage.Overview -> renderOverview(page.ui)
            is MaintenancePage.ItemForm -> renderItemForm(page, state.currency)
            is MaintenancePage.ServiceForm -> renderServiceForm(page, state.currency)
        }
        val confirm = state.confirm
        if (confirm == null) {
            binding.confirm.hide()
        } else {
            val texts = context.confirmTexts(confirm)
            binding.confirm.show(texts.title, texts.body, texts.confirm, texts.cancel, presenter::confirmYes, presenter::confirmNo)
        }
    }

    /** Shows the page in the host, creating and wiring it only when it is not the one on screen. */
    private fun <T> host(current: T?, inflate: () -> T, root: (T) -> View, assign: (T) -> Unit, wire: (T) -> Unit): T {
        if (current != null && root(current).parent === binding.pageHost) return current
        val created = inflate()
        overview = null; itemForm = null; serviceForm = null
        assign(created)
        wire(created)
        binding.pageHost.removeAllViews()
        binding.pageHost.addView(root(created))
        return created
    }

    // ---- P5 -------------------------------------------------------------------------------

    private fun renderOverview(ui: OverviewUi) {
        val page = host(
            overview, { PageMaintenanceOverviewBinding.inflate(inflater, binding.pageHost, false) },
            { it.root }, { overview = it },
        ) {
            it.add.setOnClickListener { presenter.addItem() }
            it.restore.setOnClickListener { presenter.restoreCatalog() }
            it.pager.setActions(presenter::previousPage, presenter::nextPage)
        }
        page.hint.text = hintText(ui)
        page.empty.isVisible = ui.totalCount == 0
        page.list.removeAllViews()
        ui.rows.forEachIndexed { index, row ->
            val item = ItemMaintenanceRowBinding.inflate(inflater, page.list, false)
            bindRow(item, row)
            val params = item.root.layoutParams as ViewGroup.MarginLayoutParams
            if (index > 0) params.topMargin = context.resources.getDimensionPixelSize(R.dimen.touch_gap)
            page.list.addView(item.root)
        }
        page.pager.render(ui.pageIndex, ui.pageCount)
    }

    private fun bindRow(item: ItemMaintenanceRowBinding, row: MaintenanceRowUi) {
        item.name.text = row.name
        item.detail.text = detailText(row)
        item.card.setOnClickListener { presenter.openItem(row.id) }
        item.card.contentDescription = context.getString(R.string.mt_row_status, row.name, detailText(row))
        item.done.setOnClickListener { presenter.markDone(row.id) }
    }

    private fun hintText(ui: OverviewUi): String {
        ui.notice?.let { return noticeText(it, ui.restoredCount) }
        val parts = buildList {
            if (ui.overdueCount > 0) add(context.resources.getQuantityString(R.plurals.mt_overdue_count, ui.overdueCount, ui.overdueCount))
            if (ui.soonCount > 0) add(context.resources.getQuantityString(R.plurals.mt_soon_count, ui.soonCount, ui.soonCount))
        }
        return when (parts.size) {
            0 -> if (ui.totalCount > 0) context.getString(R.string.mt_all_ok) else ""
            1 -> parts[0]
            else -> context.getString(R.string.mt_summary_join, parts[0], parts[1])
        }
    }

    private fun noticeText(notice: MaintenanceNotice, restored: Int): String = when (notice) {
        MaintenanceNotice.ServiceSaved -> context.getString(R.string.mt_notice_service_saved)
        MaintenanceNotice.ServiceSavedWithExpense -> context.getString(R.string.mt_notice_service_expense)
        MaintenanceNotice.ItemSaved -> context.getString(R.string.mt_notice_item_saved)
        MaintenanceNotice.ItemDeleted -> context.getString(R.string.mt_notice_item_deleted)
        MaintenanceNotice.CatalogRestored -> context.resources.getQuantityString(R.plurals.mt_notice_catalog_restored, restored, restored)
        MaintenanceNotice.CatalogComplete -> context.getString(R.string.mt_notice_catalog_complete)
    }

    private fun detailText(row: MaintenanceRowUi): CharSequence {
        val level = context.levelText(row.level)
        val remaining = remainingText(row)
        val origin = context.originText(row.origin)
        val urgent = SpannableStringBuilder(context.getString(R.string.mt_row_detail, level, remaining))
        if (row.level == MaintenanceLevel.Overdue || row.level == MaintenanceLevel.Soon) {
            val color = context.getColor(if (row.level == MaintenanceLevel.Overdue) R.color.signal else R.color.ink)
            urgent.setSpan(ForegroundColorSpan(color), 0, urgent.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return urgent.append(context.getString(R.string.mt_row_detail, "", origin))
    }

    private fun remainingText(row: MaintenanceRowUi): String {
        val overdue = row.level == MaintenanceLevel.Overdue
        val res = context.resources
        return when (row.axis) {
            Axis.Km -> when {
                overdue -> context.getString(R.string.mt_km_over, row.amountText)
                row.amount == 0 -> context.getString(R.string.mt_now)
                else -> context.getString(R.string.mt_km_left, row.amountText)
            }
            Axis.Days -> when {
                overdue -> res.getQuantityString(R.plurals.mt_days_over, row.amount, row.amount)
                row.amount == 0 -> context.getString(R.string.mt_today)
                else -> res.getQuantityString(R.plurals.mt_days_left, row.amount, row.amount)
            }
            null -> context.getString(R.string.mt_no_data_hint)
        }
    }

    // ---- P6 -------------------------------------------------------------------------------

    private fun renderItemForm(page: MaintenancePage.ItemForm, currency: String) {
        val form = host(
            itemForm, { PageItemFormBinding.inflate(inflater, binding.pageHost, false) },
            { it.root }, { itemForm = it },
        ) { form ->
            form.save.setOnClickListener { releaseText(form.name::releaseFocus); presenter.save() }
            form.cancel.setOnClickListener { presenter.cancel() }
            form.delete.setOnClickListener { presenter.askDelete() }
            form.pad.setOnKey { key -> releaseText(form.name::releaseFocus); presenter.press(key) }
            form.name.setOnTextChanged { presenter.setText(FormField.Name, it) }
            form.name.setOnFocused(presenter::focusText)
            form.name.setOnReleased(onKeyboardClosed)
            mapOf(
                FormField.IntervalKm to form.intervalKm, FormField.IntervalMonths to form.intervalMonths,
                FormField.FixedDue to form.fixedDue, FormField.LastKm to form.lastKm, FormField.LastDate to form.lastDate,
            ).forEach { (field, cell) -> cell.setOnSelect { releaseText(form.name::releaseFocus); presenter.selectField(field) } }
        }
        form.title.setText(if (page.isNew) R.string.mt_form_new_title else R.string.mt_form_edit_title)
        form.delete.isVisible = !page.isNew
        showHint(form.hint, page.error, context.getString(R.string.mt_form_hint))
        form.name.setText(page.name)
        form.name.setError(page.error == FormError.NameRequired)
        page.fields.forEach { field ->
            val cell = when (field.field) {
                FormField.IntervalKm -> form.intervalKm
                FormField.IntervalMonths -> form.intervalMonths
                FormField.FixedDue -> form.fixedDue
                FormField.LastKm -> form.lastKm
                else -> form.lastDate
            }
            context.renderField(cell, field, currency)
        }
        form.pad.setExtra(page.pad)
    }

    // ---- P7 -------------------------------------------------------------------------------

    private fun renderServiceForm(page: MaintenancePage.ServiceForm, currency: String) {
        val form = host(
            serviceForm, { PageServiceFormBinding.inflate(inflater, binding.pageHost, false) },
            { it.root }, { serviceForm = it },
        ) { form ->
            form.save.setOnClickListener { releaseText(form.note::releaseFocus); presenter.save() }
            form.cancel.setOnClickListener { presenter.cancel() }
            form.pad.setOnKey { key -> releaseText(form.note::releaseFocus); presenter.press(key) }
            form.note.setOnTextChanged { presenter.setText(FormField.Note, it) }
            form.note.setOnFocused(presenter::focusText)
            form.note.setOnReleased(onKeyboardClosed)
            mapOf(
                FormField.ServiceDate to form.serviceDate, FormField.ServiceKm to form.serviceKm,
                FormField.Cost to form.cost, FormField.NewDue to form.newDue,
            ).forEach { (field, cell) -> cell.setOnSelect { releaseText(form.note::releaseFocus); presenter.selectField(field) } }
        }
        form.title.setText(R.string.mt_service_title)
        showHint(form.hint, page.error, page.itemName)
        form.note.setText(page.note)
        form.newDue.isVisible = page.fields.any { it.field == FormField.NewDue }
        page.fields.forEach { field ->
            val cell = when (field.field) {
                FormField.ServiceDate -> form.serviceDate
                FormField.ServiceKm -> form.serviceKm
                FormField.Cost -> form.cost
                else -> form.newDue
            }
            context.renderField(cell, field, currency)
        }
        form.pad.setExtra(page.pad)
    }

    private fun releaseText(release: () -> Unit) {
        release()
        view.hideSystemKeyboard()
        onKeyboardClosed()
    }

    private fun showHint(hint: android.widget.TextView, error: FormError?, normal: String) {
        hint.text = if (error != null) context.errorText(error) else normal
        hint.setTextColor(context.getColor(if (error != null) R.color.signal else R.color.ink_secondary))
    }
}
