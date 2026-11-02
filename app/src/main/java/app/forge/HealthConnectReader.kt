package app.forge

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.forge.data.ContextDay
import app.forge.data.HealthSnapshot
import app.forge.data.LifeContext
import app.forge.data.OutsideSession
import app.forge.data.OutsideType
import app.forge.data.Weeks
import java.time.Instant
import java.time.LocalDate
import java.time.Period

/** Where the Health Connect read stands. Every unhappy path has its own state so the screen can say so plainly. */
sealed interface HealthState {
    data object Idle : HealthState
    data object Loading : HealthState
    /** Not on this phone, or (Android 13 and earlier) the app needs installing or updating. */
    data class Unavailable(val updateRequired: Boolean) : HealthState
    data object Denied : HealthState
    /** Connected, but nothing recorded in the window. */
    data object NoData : HealthState
    data class Failed(val message: String) : HealthState
    /** [demo]: loaded from a saved snapshot file, not read just now. */
    data class Ready(val snapshot: HealthSnapshot, val demo: Boolean = false) : HealthState
}

/**
 * Reads the last [LifeContext.DAYS] days of outside activity from Health Connect, read-only. Load comes from
 * here (what you did); how you feel comes from the check-in, so nothing overnight is required. Reads only what
 * was granted: sleep is optional, and a missing permission just leaves that part out.
 */
class HealthConnectReader(private val ctx: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ElevationGainedRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    fun status(): Int = HealthConnectClient.getSdkStatus(ctx)

    val available: Boolean get() = status() == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(): Set<String> =
        if (!available) emptySet() else HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().intersect(permissions)

    suspend fun read(today: LocalDate = Weeks.today()): HealthState = runCatching {
        when (status()) {
            HealthConnectClient.SDK_UNAVAILABLE -> return HealthState.Unavailable(updateRequired = false)
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> return HealthState.Unavailable(updateRequired = true)
        }
        val client = HealthConnectClient.getOrCreate(ctx)
        val granted = client.permissionController.getGrantedPermissions()
        fun has(c: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>) = HealthPermission.getReadPermission(c) in granted
        if (permissions.none { it in granted }) return HealthState.Denied

        val zone = Weeks.zone
        val from = today.minusDays(LifeContext.DAYS - 1).atStartOfDay(zone).toInstant()
        val stepsFrom = today.minusDays(LifeContext.STEP_DAYS - 1)
        val to = today.plusDays(1).atStartOfDay(zone).toInstant()

        val sessions = if (!has(ExerciseSessionRecord::class)) emptyList() else client
            .readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(from, to))).records
            .mapNotNull { r ->
                val type = typeOf(r.exerciseType) ?: return@mapNotNull null
                val minutes = ((r.endTime.toEpochMilli() - r.startTime.toEpochMilli()) / 60_000).toInt()
                if (!LifeContext.keep(type, minutes)) return@mapNotNull null
                // Distance and climbing are their own records: whatever was recorded during the session.
                val metrics = buildSet {
                    if (has(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
                    if (has(ElevationGainedRecord::class)) add(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)
                }
                val totals = if (metrics.isEmpty()) null else client.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(r.startTime, r.endTime)))
                OutsideSession(
                    id = r.metadata.id, type = type, start = r.startTime.toEpochMilli(), end = r.endTime.toEpochMilli(), title = r.title,
                    distanceM = totals?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
                    elevationGainM = totals?.get(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)?.inMeters,
                    source = r.metadata.dataOrigin.packageName,
                )
            }

        // Steps and distance per day, aggregated so the phone and the watch aren't counted twice.
        val dailyMetrics = buildSet {
            if (has(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            if (has(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
        }
        val daily = if (dailyMetrics.isEmpty()) emptyMap() else client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(dailyMetrics, TimeRangeFilter.between(stepsFrom.atStartOfDay(), today.plusDays(1).atStartOfDay()), Period.ofDays(1)),
        ).associate { it.startTime.toLocalDate() to it.result }

        // Sleep only if the watch recorded it; it counts for the night ending that morning.
        val sleep = if (!has(SleepSessionRecord::class)) emptyMap() else client
            .readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, to))).records
            .groupBy { it.endTime.atZone(zone).toLocalDate() }
            .mapValues { (_, v) -> v.sumOf { (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000 }.toInt() }

        val byDay = sessions.groupBy { Weeks.date(it.start) }
        val days = (LifeContext.STEP_DAYS - 1 downTo 0).map { today.minusDays(it) }.map { d ->
            ContextDay(
                date = d.toString(),
                steps = daily[d]?.get(StepsRecord.COUNT_TOTAL),
                distanceM = daily[d]?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
                sleepMinutes = sleep[d],
                outsideSessions = byDay[d].orEmpty().sortedBy { it.start },
            )
        }
        if (days.none { it.outsideSessions.isNotEmpty() || (it.steps ?: 0) > 0 }) HealthState.NoData
        else HealthState.Ready(HealthSnapshot(Instant.now().toEpochMilli(), days))
    }.getOrElse {
        Log.w("HealthConnect", "Read failed", it)
        if (it is SecurityException) HealthState.Denied else HealthState.Failed(it.message ?: "Health Connect couldn't be read")
    }

    companion object {
        fun permissionContract() = PermissionController.createRequestPermissionResultContract()

        /**
         * Health Connect's exercise type as an outside type. Gym work (strength, machines, treadmill, indoor bike) is
         * null: Press logs that itself. Anything else unrecognised is "Other", kept only if it's long enough.
         */
        fun typeOf(t: Int): OutsideType? = when (t) {
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> OutsideType.HIKING
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> OutsideType.WALKING
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> OutsideType.RUNNING
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> OutsideType.CYCLING
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> OutsideType.SWIMMING
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY, ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL,
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE,
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES, ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
            ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> null
            else -> OutsideType.OTHER
        }
    }
}
