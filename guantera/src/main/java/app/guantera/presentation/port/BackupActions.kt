package app.guantera.presentation.port

/** CU-26 as the screens tell it. Nothing here is marked as done until the pendrive confirmed it. */
sealed interface ExportOutcome {
    data class Done(val location: String) : ExportOutcome
    data object CarMoving : ExportOutcome
    data object DriveMissing : ExportOutcome
    data object DriveFull : ExportOutcome
    data object Failed : ExportOutcome
}

/** CU-27 and R35: a copy that cannot be trusted never replaces anything. */
sealed interface ImportOutcome {
    data class Done(val records: Int) : ImportOutcome
    data object CarMoving : ImportOutcome

    /** No pendrive in the radio. */
    data object DriveMissing : ImportOutcome

    /** There is a pendrive but no copy of Guantera on it. */
    data object NoCopy : ImportOutcome
    data object Unreadable : ImportOutcome
    data object Damaged : ImportOutcome
    data object NewerVersion : ImportOutcome

    /** Without a copy of what is there now, nothing was replaced. */
    data object SafetyCopyFailed : ImportOutcome
    data object StorageFailed : ImportOutcome
}

/** The copies of P11 and P0: export to the pendrive, import from it, and when the last copy was made. */
interface BackupActions {
    suspend fun export(): ExportOutcome

    /** Imports the newest copy on the pendrive. */
    suspend fun importFromDrive(): ImportOutcome

    /** The moment of the newest copy kept inside the radio, or null when there is none. Never throws. */
    suspend fun lastCopyUtcMs(): Long?
}
