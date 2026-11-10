package app.guantera.presentation.port

/** Why P12 opened, in a sentence the screen can say (especificación, P12). */
enum class RecoveryCause {
    /** The database was damaged: it was set aside and no copy could be restored. */
    NoValidCopy,

    /** The damaged database could not even be moved out of the way. */
    DatabaseNotMoved,
    MigrationFailed,

    /** Three unexpected closes in a row when opening. */
    RepeatedCrashes,

    /** A copy was found but writing it back failed. */
    RestoreFailed,

    /** The check itself could not run. */
    CheckFailed,
}

/** A copy kept inside the radio, newest first in lists. */
data class CopyInfo(val id: String, val createdUtcMs: Long, val recordCount: Int)

/** What opening the app found, before anything else runs (CU-28). */
sealed interface StartupResult {
    /** The data is fine; the app may start. */
    data object Healthy : StartupResult

    /** The database was damaged and the newest valid copy was put back: the screen says how much came back. */
    data class Restored(val copy: CopyInfo) : StartupResult

    /** P12 opens. The data that was there was set aside, never deleted. */
    data class NeedsUser(val cause: RecoveryCause, val copies: List<CopyInfo>) : StartupResult
}

enum class RestoreOutcome { Restored, Unreadable, Damaged, NewerVersion, Failed }

/** The only things P12 may touch: no service, no presenters, no shared state with the rest of the app. */
interface RecoveryActions {
    suspend fun open(): StartupResult
    suspend fun restore(copyId: String): RestoreOutcome
    suspend fun importFromDrive(): ImportOutcome

    /** "Exportar lo que se pueda". */
    suspend fun export(): ExportOutcome

    /** "Empezar de cero": the old data is set aside, not deleted. False if it could not be moved. */
    suspend fun startFresh(): Boolean
}
