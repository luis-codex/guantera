package app.guantera.ui.screens

import android.content.Context
import app.guantera.R
import app.guantera.presentation.expenses.ExpenseCategory
import app.guantera.presentation.input.FieldUi
import app.guantera.presentation.input.FieldUnit
import app.guantera.presentation.input.FormError
import app.guantera.presentation.maintenance.ConfirmKind
import app.guantera.presentation.maintenance.ConfirmUi
import app.guantera.presentation.maintenance.DataOrigin
import app.guantera.ui.components.FieldCellView

fun Context.errorText(error: FormError): String = getString(
    when (error) {
        FormError.NameRequired -> R.string.er_name
        FormError.NeedsRule -> R.string.er_needs_rule
        FormError.InvalidDate -> R.string.er_invalid_date
        FormError.FutureDate -> R.string.er_future_date
        FormError.KmRequired -> R.string.er_km_required
        FormError.NewDueRequired -> R.string.er_new_due_required
        FormError.NewDueTooEarly -> R.string.er_new_due_early
        FormError.CategoryRequired -> R.string.er_category
        FormError.AmountRequired -> R.string.er_amount
        FormError.SaveFailed -> R.string.er_save_failed
        FormError.SaveFull -> R.string.er_save_full
        FormError.CarMoving -> R.string.er_car_moving
    },
)

fun Context.categoryText(category: ExpenseCategory): String = getString(
    when (category) {
        ExpenseCategory.Fuel -> R.string.ex_cat_fuel
        ExpenseCategory.Maintenance -> R.string.ex_cat_maintenance
        ExpenseCategory.Insurance -> R.string.ex_cat_insurance
        ExpenseCategory.Tolls -> R.string.ex_cat_tolls
        ExpenseCategory.Parking -> R.string.ex_cat_parking
        ExpenseCategory.Washing -> R.string.ex_cat_washing
        ExpenseCategory.Other -> R.string.ex_cat_other
    },
)

fun Context.originText(origin: DataOrigin): String = getString(
    when (origin) {
        DataOrigin.Verified -> R.string.mt_origin_verified
        DataOrigin.OwnCriterion -> R.string.mt_origin_criterion
        DataOrigin.Own -> R.string.mt_origin_own
    },
)

fun Context.money(currency: String, amountText: String): String = getString(R.string.lg_money, currency, amountText)

/** Draws one pad field: the value with its unit, or a dash while empty. */
fun Context.renderField(cell: FieldCellView, field: FieldUi, currency: String) {
    val value = when (field.unit) {
        FieldUnit.None -> field.valueText
        FieldUnit.Km -> getString(R.string.lg_unit_km, field.valueText)
        FieldUnit.Months -> getString(R.string.lg_unit_months, field.valueText)
        FieldUnit.Money -> money(currency, field.valueText)
    }
    // An active empty date shows its blanks; every other empty field shows a dash.
    val empty = field.valueText.isEmpty()
    cell.setValue(value, empty)
    cell.isActivated = field.isActive
    cell.isSelected = field.hasError
}

/** Title, body and button texts for a confirmation. */
class ConfirmTexts(val title: String, val body: String, val confirm: String, val cancel: String)

fun Context.confirmTexts(confirm: ConfirmUi): ConfirmTexts = when (confirm.kind) {
    ConfirmKind.ParkedCheck -> ConfirmTexts(
        getString(R.string.lg_confirm_parked_title), getString(R.string.lg_confirm_parked_body),
        getString(R.string.lg_yes), getString(R.string.lg_no),
    )
    ConfirmKind.DeleteItem -> ConfirmTexts(
        getString(R.string.lg_confirm_delete_item_title, confirm.subject.orEmpty()),
        getString(R.string.lg_confirm_delete_item_body),
        getString(R.string.lg_delete), getString(R.string.lg_back),
    )
    ConfirmKind.DeleteExpense -> ConfirmTexts(
        getString(R.string.lg_confirm_delete_expense_title, confirm.subject.orEmpty()),
        getString(R.string.lg_confirm_delete_expense_body),
        getString(R.string.lg_delete), getString(R.string.lg_back),
    )
}
