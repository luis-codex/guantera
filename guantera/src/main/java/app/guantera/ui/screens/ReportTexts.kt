package app.guantera.ui.screens

import android.content.Context
import app.guantera.R
import app.guantera.presentation.port.ExportOutcome
import app.guantera.presentation.port.ImportOutcome
import app.guantera.presentation.common.DisplayFormat

/** What each result of exporting and importing says. Shared by P11, P0 and P12. */
fun Context.exportText(outcome: ExportOutcome): String = getString(
    when (outcome) {
        is ExportOutcome.Done -> R.string.rp_export_done
        ExportOutcome.CarMoving -> R.string.rp_export_car_moving
        ExportOutcome.DriveMissing -> R.string.rp_export_drive_missing
        ExportOutcome.DriveFull -> R.string.rp_export_drive_full
        ExportOutcome.Failed -> R.string.rp_export_failed
    },
)

fun Context.importText(outcome: ImportOutcome): String = when (outcome) {
    is ImportOutcome.Done -> getString(R.string.rp_import_done, DisplayFormat().whole(outcome.records))
    ImportOutcome.CarMoving -> getString(R.string.rp_import_car_moving)
    ImportOutcome.DriveMissing -> getString(R.string.rp_import_drive_missing)
    ImportOutcome.NoCopy -> getString(R.string.rp_import_no_copy)
    ImportOutcome.Unreadable -> getString(R.string.rp_import_unreadable)
    ImportOutcome.Damaged -> getString(R.string.rp_import_damaged)
    ImportOutcome.NewerVersion -> getString(R.string.rp_import_newer)
    ImportOutcome.SafetyCopyFailed -> getString(R.string.rp_import_safety_failed)
    ImportOutcome.StorageFailed -> getString(R.string.rp_import_storage_failed)
}
