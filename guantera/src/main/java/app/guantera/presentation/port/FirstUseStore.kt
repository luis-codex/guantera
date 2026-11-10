package app.guantera.presentation.port

import kotlinx.coroutines.flow.StateFlow

/** What P0 reads and writes. Every write goes through the domain, so the car's movement is checked there. */
interface FirstUseStore {
    /** True once first use was completed; null until the settings were read. */
    val isComplete: StateFlow<Boolean?>

    suspend fun saveOdometer(km: Int): FormOutcome
    suspend fun saveCurrency(currency: String): FormOutcome

    /** CU-14: loads the catalog if there are no items at all. */
    suspend fun loadCatalog(): FormOutcome

    suspend fun markComplete(): FormOutcome

    /** Answers "¿El auto está estacionado?" with yes (CU-04). */
    fun confirmStopped()
}
