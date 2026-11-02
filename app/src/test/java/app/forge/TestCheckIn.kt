package app.forge

/**
 * Context layer: a fresh lifting session opens with the readiness check-in sheet over it. UI tests about other
 * things answer it the way a user would, with Skip, so the session underneath can be driven.
 */
fun AppViewModel.skipCheckIn() {
    data.value.active?.let { saveCheckIn(it.id, null, null, null) }
}
