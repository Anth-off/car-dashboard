package fr.cockpit.dashboard.destinations

/** A user-saved place, independent of dashboard telemetry and trip recording. */
data class Destination(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String = "",
) {
    init {
        require(id.isNotBlank()) { "La destination doit avoir un identifiant." }
        require(name.isNotBlank()) { "Donnez un nom à cette destination." }
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "La latitude doit être comprise entre −90 et 90."
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "La longitude doit être comprise entre −180 et 180."
        }
    }
}
