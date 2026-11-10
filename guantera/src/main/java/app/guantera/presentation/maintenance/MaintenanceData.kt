package app.guantera.presentation.maintenance

import kotlinx.coroutines.flow.StateFlow

/** Boundary for P5, P6 and P7: the list of items and the actions on it (CU-09 to CU-14). */
interface MaintenanceData {
    val items: StateFlow<List<MaintenanceItem>>

    /** Creates the item when its id is 0, otherwise replaces it (CU-10). */
    fun save(item: MaintenanceItem)

    /** CU-11. Removing an item never touches the expenses it created. */
    fun delete(id: Long)

    /** CU-14: brings back the catalog items that are missing, without touching the rest. Returns how many came back. */
    fun restoreCatalog(): Int

    /**
     * CU-12, R21: updates the last service, replaces or clears the fixed due date and, with a cost,
     * creates the expense, all in one step. False when the item no longer exists.
     */
    fun registerService(registration: ServiceRegistration): Boolean
}
