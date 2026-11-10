package app.guantera.presentation.maintenance

import app.guantera.presentation.port.FormOutcome
import kotlinx.coroutines.flow.StateFlow

/** [restored] counts the catalog items that came back; it is 0 unless [outcome] is Saved. */
data class CatalogResult(val outcome: FormOutcome, val restored: Int)

/** Boundary for P5, P6 and P7: the list of items and the actions on it (CU-09 to CU-14). */
interface MaintenanceData {
    val items: StateFlow<List<MaintenanceItem>>

    /** Creates the item when its id is 0, otherwise replaces it (CU-10). */
    suspend fun save(item: MaintenanceItem): FormOutcome

    /** CU-11. Removing an item never touches the expenses it created. */
    suspend fun delete(id: Long): FormOutcome

    /** CU-14: brings back the catalog items that are missing, without touching the rest. Returns how many came back. */
    suspend fun restoreCatalog(): CatalogResult

    /**
     * CU-12, R21: updates the last service, replaces or clears the fixed due date and, with a cost,
     * creates the expense, all in one step. Rejected when the item no longer exists.
     */
    suspend fun registerService(registration: ServiceRegistration): FormOutcome
}
