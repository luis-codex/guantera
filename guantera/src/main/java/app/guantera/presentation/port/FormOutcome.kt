package app.guantera.presentation.port

/**
 * What a form write ended in, as the screens need to tell it. The domain decides (R10, R22...);
 * the screen never guesses whether the car is parked.
 */
sealed interface FormOutcome {
    data object Saved : FormOutcome

    /** The state is unknown: ask "¿El auto está estacionado?" and, on yes, try again. */
    data object NeedsConfirmation : FormOutcome

    /** The car moves. Nothing was written. */
    data object CarMoving : FormOutcome

    /** The domain refused it (for example the item no longer exists). Nothing was written. */
    data object Rejected : FormOutcome
    data object StorageFull : FormOutcome
    data object StorageFailed : FormOutcome
}
