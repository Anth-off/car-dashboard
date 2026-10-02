package fr.cockpit.dashboard.navigation

/** Alternatives share a departure; they are only interchangeable near that departure. */
internal object RouteOptionSelection {
    fun needsRefresh(origin: GeoPoint?, current: GeoPoint): Boolean =
        origin == null || RouteProgressEngine.distanceMeters(origin, current) > 100.0
}
