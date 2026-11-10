package app.guantera.tracking

/**
 * What the service does when it starts, by boot or by the app. It records only on the one real graph
 * (Room); without it the data waits for the owner in P12 and the service neither opens Room nor writes.
 */
object TrackingStartPolicy {
    enum class Mode { RECORD, WARN_AND_STOP }

    fun decide(graph: TrackingGraph?): Mode = if (graph == null) Mode.WARN_AND_STOP else Mode.RECORD
}
