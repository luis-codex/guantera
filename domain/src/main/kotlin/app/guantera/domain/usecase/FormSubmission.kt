package app.guantera.domain.usecase

import app.guantera.domain.motion.DrivingState
import app.guantera.domain.motion.ManualConfirmation
import app.guantera.domain.port.Clock
import app.guantera.domain.port.StorageException
import app.guantera.domain.port.StorageFailure

enum class FormBlock { CAR_MOVING, NEEDS_CONFIRMATION }

enum class FormError {
    AMOUNT_NOT_POSITIVE,
    NAME_BLANK,
    INVALID_RULE,
    ITEM_NOT_FOUND,
    EXPENSE_NOT_FOUND,
    NEW_DUE_DATE_REQUIRED,
    ODOMETER_NEGATIVE,
    CURRENCY_BLANK,
    NO_VALID_FIX,
    NO_PARKING_SPOT,
}

sealed interface FormResult<out T> {
    data class Saved<T>(val value: T) : FormResult<T>
    data class Blocked(val reason: FormBlock) : FormResult<Nothing>
    data class Invalid(val error: FormError) : FormResult<Nothing>
    data class StorageFailed(val failure: StorageFailure) : FormResult<Nothing>
}

/** R10. */
class FormGate(
    private val driving: ObserveDrivingState,
    private val confirmation: ManualConfirmation,
    private val clock: Clock,
) {
    fun check(): FormBlock? = when (driving.current()) {
        DrivingState.DETENIDO -> null
        DrivingState.EN_MOVIMIENTO -> FormBlock.CAR_MOVING
        DrivingState.DESCONOCIDO -> if (confirmation.isValid(clock.monoMs())) null else FormBlock.NEEDS_CONFIRMATION
    }
}

/** The path of every form: allowed right now, written, and followed by a copy (resiliencia.md, level 2). */
class FormSubmission(val gate: FormGate, private val backup: CreateAutomaticBackup) {
    suspend fun <T> submit(write: suspend () -> T): FormResult<T> {
        gate.check()?.let { return FormResult.Blocked(it) }
        val value = try {
            write()
        } catch (e: StorageException) {
            return FormResult.StorageFailed(e.failure)
        }
        backup.run()
        return FormResult.Saved(value)
    }
}
