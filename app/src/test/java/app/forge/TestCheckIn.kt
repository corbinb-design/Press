package app.forge

/**
 * Context layer: a fresh lifting session opens with the readiness check-in sheet over it, then the coach's banner at
 * the top. UI tests about other things answer both the way a user would (Skip, then Ignore), offline, so the session
 * underneath can be driven and nothing calls Gemini.
 */
fun AppViewModel.skipCheckIn() {
    Gemini.offline = true
    val id = data.value.active?.id ?: return
    saveCheckIn(id, null, null, null)
    prepareCoach(id)
    ignoreTweaks(id)
}
