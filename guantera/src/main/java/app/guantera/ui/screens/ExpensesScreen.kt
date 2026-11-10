package app.guantera.ui.screens

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import app.guantera.R
import app.guantera.databinding.PageExpenseFormBinding
import app.guantera.databinding.PageExpensesOverviewBinding
import app.guantera.databinding.ScreenExpensesBinding
import app.guantera.presentation.expenses.CostPerKm
import app.guantera.presentation.expenses.ExpenseCategory
import app.guantera.presentation.expenses.ExpenseNotice
import app.guantera.presentation.expenses.ExpensesOverviewUi
import app.guantera.presentation.expenses.ExpensesPage
import app.guantera.presentation.expenses.ExpensesPresenter
import app.guantera.presentation.expenses.ExpensesViewState
import app.guantera.presentation.input.FormField
import app.guantera.ui.Screen
import app.guantera.ui.components.ChoiceButton
import app.guantera.ui.components.hideSystemKeyboard

/** P8 "¿Cuánto me sale?", with P9 (nuevo o editar gasto) inside the same screen. */
class ExpensesScreen(
    parent: ViewGroup,
    private val presenter: ExpensesPresenter,
    private val onKeyboardClosed: () -> Unit,
) : Screen() {
    private val inflater = LayoutInflater.from(parent.context)
    private val binding = ScreenExpensesBinding.inflate(inflater, parent, false)
    private val context = parent.context
    override val view = binding.root

    private var overview: PageExpensesOverviewBinding? = null
    private var form: PageExpenseFormBinding? = null

    override suspend fun observe() = presenter.state.collect(::render)

    override fun onBack(): Boolean = presenter.onBack()

    override fun onLeave() = presenter.reset()

    private fun render(state: ExpensesViewState) {
        when (val page = state.page) {
            is ExpensesPage.Overview -> renderOverview(page.ui, state.currency)
            is ExpensesPage.Form -> renderForm(page, state.currency)
        }
        val confirm = state.confirm
        if (confirm == null) {
            binding.confirm.hide()
        } else {
            val texts = context.confirmTexts(
                // The delete question names the amount, which the presenter gives without currency.
                if (confirm.subject != null && confirm.kind == app.guantera.presentation.maintenance.ConfirmKind.DeleteExpense) {
                    confirm.copy(subject = context.money(state.currency, confirm.subject))
                } else {
                    confirm
                },
            )
            binding.confirm.show(texts.title, texts.body, texts.confirm, texts.cancel, presenter::confirmYes, presenter::confirmNo)
        }
    }

    private fun <T> host(current: T?, inflate: () -> T, root: (T) -> View, assign: (T) -> Unit, wire: (T) -> Unit): T {
        if (current != null && root(current).parent === binding.pageHost) return current
        val created = inflate()
        overview = null; form = null
        assign(created)
        wire(created)
        binding.pageHost.removeAllViews()
        binding.pageHost.addView(root(created))
        return created
    }

    // ---- P8 -------------------------------------------------------------------------------

    private fun renderOverview(ui: ExpensesOverviewUi, currency: String) {
        val page = host(
            overview, { PageExpensesOverviewBinding.inflate(inflater, binding.pageHost, false) },
            { it.root }, { overview = it },
        ) {
            it.add.setOnClickListener { presenter.newExpense() }
            it.controls.bind(presenter.historyActions)
            it.body.bind(presenter.historyActions)
        }
        page.hint.text = when (ui.notice) {
            ExpenseNotice.Saved -> context.getString(R.string.ex_notice_saved)
            ExpenseNotice.Deleted -> context.getString(R.string.ex_notice_deleted)
            ExpenseNotice.SaveFailed -> context.getString(R.string.er_save_failed)
            ExpenseNotice.CarMoving -> context.getString(R.string.er_car_moving)
            null -> ""
        }
        page.hint.isVisible = ui.notice != null
        when (val cost = ui.costPerKm) {
            is CostPerKm.Value -> page.costRow.setValue(context.money(currency, cost.text))
            CostPerKm.TooFewKm, CostPerKm.Calculating -> page.costRow.setValue(context.getString(R.string.lg_empty_value))
        }
        page.costNote.isVisible = ui.costPerKm == CostPerKm.TooFewKm
        page.costRow.emphasize()
        page.total.setValue(context.money(currency, ui.totalText))
        page.kilometers.setValue(context.getString(R.string.lg_unit_km, ui.kmText))
        page.controls.render(ui.history)
        page.body.render(ui.history, currency)
    }

    // ---- P9 -------------------------------------------------------------------------------

    private fun renderForm(state: ExpensesPage.Form, currency: String) {
        val page = host(
            form, { PageExpenseFormBinding.inflate(inflater, binding.pageHost, false) },
            { it.root }, { form = it },
        ) { f ->
            f.save.setOnClickListener { releaseText(f); presenter.save() }
            f.cancel.setOnClickListener { presenter.cancel() }
            f.delete.setOnClickListener { presenter.askDelete() }
            f.pad.setOnKey { key -> releaseText(f); presenter.press(key) }
            f.note.setOnTextChanged(presenter::setNote)
            f.note.setOnFocused(presenter::focusNote)
            f.note.setOnReleased(onKeyboardClosed)
            f.amount.setOnSelect { releaseText(f); presenter.selectField(FormField.Amount) }
            f.date.setOnSelect { releaseText(f); presenter.selectField(FormField.ExpenseDate) }
            categoryButtons(f).forEach { (category, button) ->
                button.setOnClickListener { releaseText(f); presenter.selectCategory(category) }
            }
        }
        page.title.setText(if (state.isNew) R.string.ex_form_new_title else R.string.ex_form_edit_title)
        page.delete.isVisible = !state.isNew
        val normal = context.getString(if (state.linkedToService) R.string.ex_linked_hint else R.string.ex_form_hint)
        val error = state.error
        page.hint.text = if (error != null) context.errorText(error) else normal
        page.hint.setTextColor(context.getColor(if (error != null) R.color.signal else R.color.ink_secondary))
        categoryButtons(page).forEach { (category, button) ->
            button.isSelected = category == state.category
        }
        page.note.setText(state.note)
        state.fields.forEach { field ->
            val cell = if (field.field == FormField.Amount) page.amount else page.date
            context.renderField(cell, field, currency)
        }
        page.pad.setExtra(state.pad)
    }

    private fun categoryButtons(f: PageExpenseFormBinding): List<Pair<ExpenseCategory, ChoiceButton>> = listOf(
        ExpenseCategory.Fuel to f.catFuel,
        ExpenseCategory.Maintenance to f.catMaintenance,
        ExpenseCategory.Insurance to f.catInsurance,
        ExpenseCategory.Tolls to f.catTolls,
        ExpenseCategory.Parking to f.catParking,
        ExpenseCategory.Washing to f.catWashing,
        ExpenseCategory.Other to f.catOther,
    )

    private fun releaseText(f: PageExpenseFormBinding) {
        f.note.releaseFocus()
        view.hideSystemKeyboard()
        onKeyboardClosed()
    }
}
