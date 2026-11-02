package com.universal.sleepapp.engine

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Standardized output stages. Ordinal order is relied upon by the HMM (AWAKE=0, LIGHT=1, DEEP=2, REM=3). */
enum class Stage { AWAKE, LIGHT, DEEP, REM }

/** Which cardiac stream the caller wants the engine to prefer. AUTO picks per epoch from the data present. */
enum class StreamType { AUTO, IBI, BPM, ACTIVITY_ONLY }

/** The processing path that was actually used for an epoch. */
enum class Route { HRV_IBI, BPM_DROP_DELTA, ACTIVITY_ONLY }

/**
 * All tunables in one place. The BPM drop-delta values encode the figures supplied in the brief
 * (-4.0 BPM deep boundary, 60..36 BPM sleeping range, deep/REM over-detection corrections).
 * They are hand-set priors, not values fitted against polysomnography.
 */
data class EngineConfig(
    val epochSeconds: Int = 30,

    // --- heart-rate baseline and plausibility
    val daytimeRestingBpm: Double = 60.0,
    val sleepBpmCeiling: Double = 60.0,
    val sleepBpmFloor: Double = 36.0,
    val bpmRejectBelow: Double = 30.0,
    val bpmRejectAbove: Double = 220.0,

    // --- actigraphy
    val gravityMedianWindowSec: Double = 1.0,
    val moveThresholdG: Double = 0.05,
    val moveTiltDeg: Double = 4.0,
    val stillResetMoveFraction: Double = 0.07,

    // --- IBI cleaning / HRV route
    val ibiMinMs: Double = 300.0,
    val ibiMaxMs: Double = 2000.0,
    val ibiRelTol: Double = 0.25,
    val minBeatsForHrv: Int = 15,

    // --- BPM Drop Delta route (Samsung-style BPM-only streams)
    val deepDropBpm: Double = -4.0,
    val deepBoundaryPenaltyPerBpm: Double = 1.5,
    val deepBias: Double = -0.8,
    val remBias: Double = -0.6,
    val awakeBias: Double = -0.5,

    // --- HMM
    val emissionBeta: Double = 0.7,
    val minRunEpochs: List<Int> = listOf(1, 1, 2, 3)
)

/** One 30-second block of raw data. accelXyz is interleaved x0,y0,z0,x1,y1,z1... (m/s^2 or g; auto-detected). */
class EpochData(
    val epochIndex: Int,
    val accelXyz: FloatArray,
    val ibiMs: DoubleArray,
    val bpm: DoubleArray,
    val streamOverride: StreamType = StreamType.AUTO
)

class EpochFeatures(
    val route: Route,
    val hasAccel: Boolean,
    val activityRmsG: Double,
    val moveFraction: Double,
    val stillSeconds: Double,
    val meanBpm: Double,
    val bpmDelta: Double,
    val rmssdMs: Double,
    val fluctuation: Double
)

class EpochResult(
    val stage: Stage,
    /** Log-likelihood per stage, ordered AWAKE, LIGHT, DEEP, REM. */
    val logEmission: DoubleArray,
    val features: EpochFeatures
)

// =====================================================================================
// Small statistics helpers
// =====================================================================================
internal object Stats {
    fun mean(a: DoubleArray): Double {
        if (a.isEmpty()) return Double.NaN
        var s = 0.0
        for (v in a) s += v
        return s / a.size
    }

    fun sd(a: DoubleArray): Double {
        if (a.size < 2) return Double.NaN
        val m = mean(a)
        var s = 0.0
        for (v in a) s += (v - m) * (v - m)
        return sqrt(s / (a.size - 1))
    }

    fun median(a: DoubleArray): Double {
        if (a.isEmpty()) return Double.NaN
        val c = a.copyOf()
        java.util.Arrays.sort(c)
        val n = c.size
        return if (n % 2 == 1) c[n / 2] else 0.5 * (c[n / 2 - 1] + c[n / 2])
    }

    /** Log of a Gaussian pdf with the z-score clamped so one wild feature cannot dominate. */
    fun gaussLog(x: Double, mu: Double, sd: Double): Double {
        val z = ((x - mu) / sd).coerceIn(-5.0, 5.0)
        return -0.5 * z * z - ln(sd)
    }
}

internal class RollingMedian(private val capacity: Int) {
    private val values = ArrayDeque<Double>()
    val size: Int get() = values.size

    fun add(v: Double) {
        values.addLast(v)
        if (values.size > capacity) values.removeFirst()
    }

    fun median(): Double = Stats.median(values.toDoubleArray())
}

// =====================================================================================
// Actigraphy: RMS vector magnitude after a high-pass median filter
// =====================================================================================
object Actigraphy {

    class Result(
        val hasData: Boolean,
        val rmsG: Double,
        val maxG: Double,
        val moveFraction: Double
    )

    /**
     * 1. Convert to g (auto-detects m/s^2 when the median magnitude is > 5).
     * 2. Vector magnitude |a| per sample.
     * 3. High-pass: subtract a sliding median of |a| (window ~windowSec). The median tracks the static 1 g
     *    gravity component and is immune to short movement bursts, leaving the dynamic component.
     * 4. RMS of the high-passed signal = RMS Vector Magnitude for the epoch.
     * 5. Movement fraction = share of 1-second bins whose peak high-passed magnitude exceeds moveThresholdG,
     *    OR whose mean gravity direction rotated by more than moveTiltDeg versus the previous bin
     *    (catches slow wrist rotations that leave |a| almost unchanged).
     */
    fun process(
        xyz: FloatArray,
        epochSeconds: Int,
        windowSec: Double,
        moveThresholdG: Double,
        moveTiltDeg: Double
    ): Result {
        val n = xyz.size / 3
        if (n < 8) return Result(false, 0.0, 0.0, 0.0)

        val vx = DoubleArray(n)
        val vy = DoubleArray(n)
        val vz = DoubleArray(n)
        val mag = DoubleArray(n)
        for (i in 0 until n) {
            val x = xyz[3 * i].toDouble()
            val y = xyz[3 * i + 1].toDouble()
            val z = xyz[3 * i + 2].toDouble()
            vx[i] = x
            vy[i] = y
            vz[i] = z
            mag[i] = sqrt(x * x + y * y + z * z)
        }
        val scale = if (Stats.median(mag) > 5.0) 1.0 / 9.80665 else 1.0
        for (i in 0 until n) {
            vx[i] *= scale
            vy[i] *= scale
            vz[i] *= scale
            mag[i] *= scale
        }

        val rate = n.toDouble() / epochSeconds
        var w = (rate * windowSec).roundToInt().coerceIn(3, 101)
        if (w % 2 == 0) w += 1
        val half = w / 2
        val tmp = DoubleArray(w)
        val hp = DoubleArray(n)
        for (i in 0 until n) {
            val lo = max(0, i - half)
            val hi = min(n - 1, i + half)
            val cnt = hi - lo + 1
            System.arraycopy(mag, lo, tmp, 0, cnt)
            java.util.Arrays.sort(tmp, 0, cnt)
            hp[i] = mag[i] - tmp[cnt / 2]
        }

        var sumSq = 0.0
        var peak = 0.0
        for (v in hp) {
            sumSq += v * v
            if (abs(v) > peak) peak = abs(v)
        }
        val rms = sqrt(sumSq / n)

        val binSize = max(1, rate.roundToInt())
        val bins = max(1, n / binSize)
        var moving = 0
        var prevX = 0.0
        var prevY = 0.0
        var prevZ = 0.0
        var havePrev = false
        for (b in 0 until bins) {
            val start = b * binSize
            val end = if (b == bins - 1) n else start + binSize
            var binPeak = 0.0
            var mx = 0.0
            var my = 0.0
            var mz = 0.0
            for (i in start until end) {
                if (abs(hp[i]) > binPeak) binPeak = abs(hp[i])
                mx += vx[i]
                my += vy[i]
                mz += vz[i]
            }
            val c = (end - start).toDouble()
            mx /= c
            my /= c
            mz /= c
            var tiltDeg = 0.0
            if (havePrev) {
                val na = sqrt(mx * mx + my * my + mz * mz)
                val nb = sqrt(prevX * prevX + prevY * prevY + prevZ * prevZ)
                if (na > 1e-6 && nb > 1e-6) {
                    val cosv = ((mx * prevX + my * prevY + mz * prevZ) / (na * nb)).coerceIn(-1.0, 1.0)
                    tiltDeg = Math.toDegrees(acos(cosv))
                }
            }
            if (binPeak > moveThresholdG || tiltDeg > moveTiltDeg) moving++
            prevX = mx
            prevY = my
            prevZ = mz
            havePrev = true
        }
        return Result(true, rms, peak, moving.toDouble() / bins)
    }
}

// =====================================================================================
// HRV from raw inter-beat intervals
// =====================================================================================
object Hrv {

    /** consecutive[j] is true when beat j directly follows beat j-1 in the raw stream (no rejected beat between). */
    class Clean(val ibi: DoubleArray, val consecutive: BooleanArray)

    fun cleanIbi(raw: DoubleArray, config: EngineConfig): Clean {
        val n = raw.size
        val keptValues = ArrayList<Double>(n)
        val keptConsecutive = ArrayList<Boolean>(n)
        var lastKeptIndex = -2
        val win = DoubleArray(5)
        for (i in 0 until n) {
            val x = raw[i]
            if (!x.isFinite() || x < config.ibiMinMs || x > config.ibiMaxMs) continue
            var cnt = 0
            for (j in max(0, i - 2)..min(n - 1, i + 2)) {
                val v = raw[j]
                if (v.isFinite() && v >= config.ibiMinMs && v <= config.ibiMaxMs) {
                    win[cnt++] = v
                }
            }
            java.util.Arrays.sort(win, 0, cnt)
            val med = win[cnt / 2]
            if (abs(x - med) > config.ibiRelTol * med) continue
            keptValues.add(x)
            keptConsecutive.add(lastKeptIndex == i - 1)
            lastKeptIndex = i
        }
        return Clean(keptValues.toDoubleArray(), keptConsecutive.toBooleanArray())
    }

    /** Root mean square of successive differences (ms). NaN when fewer than 8 valid adjacent pairs exist. */
    fun rmssd(c: Clean): Double {
        var sum = 0.0
        var pairs = 0
        for (j in 1 until c.ibi.size) {
            if (c.consecutive[j]) {
                val d = c.ibi[j] - c.ibi[j - 1]
                sum += d * d
                pairs++
            }
        }
        return if (pairs >= 8) sqrt(sum / pairs) else Double.NaN
    }
}

// =====================================================================================
// The staging engine
// =====================================================================================
class SleepEngine(private val config: EngineConfig = EngineConfig()) {

    // Personal daytime resting baseline, bounded so a bad setting cannot push thresholds out of physiology.
    private val baselineBpm: Double = config.daytimeRestingBpm.coerceIn(45.0, 80.0)

    // Deep boundary: at least |deepDropBpm| below baseline AND never above the 60 BPM sleeping ceiling.
    private val deepThresholdBpm: Double = min(baselineBpm + config.deepDropBpm, config.sleepBpmCeiling)
    private val deepDeltaLimit: Double = deepThresholdBpm - baselineBpm

    private val rmssdLogHistory = RollingMedian(240)
    private val epochMeanBpm = ArrayDeque<Double>()
    private var stillSeconds = 0.0
    private val emissionHistory = ArrayList<DoubleArray>()

    @Synchronized
    fun processEpoch(data: EpochData): EpochResult {
        val act = Actigraphy.process(
            data.accelXyz,
            config.epochSeconds,
            config.gravityMedianWindowSec,
            config.moveThresholdG,
            config.moveTiltDeg
        )
        if (act.hasData) {
            if (act.moveFraction >= config.stillResetMoveFraction) stillSeconds = 0.0
            else stillSeconds += config.epochSeconds
        }

        val clean = if (data.ibiMs.isEmpty()) Hrv.Clean(DoubleArray(0), BooleanArray(0))
        else Hrv.cleanIbi(data.ibiMs, config)
        val bpmSamples = sanitizeBpm(data.bpm)
        val hrvReady = clean.ibi.size >= config.minBeatsForHrv

        val route = when (data.streamOverride) {
            StreamType.IBI ->
                if (hrvReady) Route.HRV_IBI
                else if (bpmSamples.isNotEmpty()) Route.BPM_DROP_DELTA
                else Route.ACTIVITY_ONLY
            StreamType.BPM ->
                if (bpmSamples.isNotEmpty()) Route.BPM_DROP_DELTA else Route.ACTIVITY_ONLY
            StreamType.ACTIVITY_ONLY -> Route.ACTIVITY_ONLY
            StreamType.AUTO ->
                if (hrvReady) Route.HRV_IBI
                else if (bpmSamples.isNotEmpty()) Route.BPM_DROP_DELTA
                else Route.ACTIVITY_ONLY
        }

        val emission: DoubleArray
        val features: EpochFeatures

        when (route) {
            Route.HRV_IBI -> {
                val meanIbi = Stats.mean(clean.ibi)
                val meanBpm = 60000.0 / meanIbi
                val rmssd = Hrv.rmssd(clean)
                val cv = Stats.sd(clean.ibi) / meanIbi
                var rr = 0.0
                var hasRr = false
                if (rmssd.isFinite() && rmssd > 0.0) {
                    val lnR = ln(rmssd)
                    if (rmssdLogHistory.size >= 10) {
                        rr = (lnR - rmssdLogHistory.median()).coerceIn(-2.0, 2.0)
                        hasRr = true
                    }
                    if (!act.hasData || act.moveFraction < config.stillResetMoveFraction) {
                        rmssdLogHistory.add(lnR)
                    }
                }
                pushMeanBpm(meanBpm)
                val dHr = meanBpm - baselineBpm
                emission = hrvEmission(act, dHr, rr, hasRr, if (cv.isFinite()) cv else Double.NaN)
                features = EpochFeatures(
                    route, act.hasData, act.rmsG, act.moveFraction, stillSeconds,
                    meanBpm, dHr, rmssd, Double.NaN
                )
            }

            Route.BPM_DROP_DELTA -> {
                // HRV is deliberately bypassed on this route: no RMSSD, no spectral work.
                val rawMean = Stats.mean(bpmSamples)
                val effectiveBpm = max(rawMean, config.sleepBpmFloor)
                val dHr = effectiveBpm - baselineBpm
                val within = if (bpmSamples.size >= 3) Stats.sd(bpmSamples) else Double.NaN
                pushMeanBpm(effectiveBpm)
                val rolling = if (epochMeanBpm.size >= 4) Stats.sd(epochMeanBpm.toDoubleArray()) else Double.NaN
                val fluct = when {
                    within.isFinite() && rolling.isFinite() -> 0.5 * (within + rolling)
                    within.isFinite() -> within
                    rolling.isFinite() -> rolling
                    else -> Double.NaN
                }
                emission = bpmDropEmission(act, dHr, fluct)
                features = EpochFeatures(
                    route, act.hasData, act.rmsG, act.moveFraction, stillSeconds,
                    effectiveBpm, dHr, Double.NaN, fluct
                )
            }

            Route.ACTIVITY_ONLY -> {
                emission = activityEmission(act)
                features = EpochFeatures(
                    route, act.hasData, act.rmsG, act.moveFraction, stillSeconds,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN
                )
            }
        }

        emissionHistory.add(emission)
        var best = 0
        for (s in 1 until 4) if (emission[s] > emission[best]) best = s
        return EpochResult(Stage.values()[best], emission, features)
    }

    /** Runs the full-night Viterbi/HMM pass over every epoch seen so far. */
    @Synchronized
    fun smoothNight(): List<Stage> = smooth(ArrayList(emissionHistory), config)

    @Synchronized
    fun epochCount(): Int = emissionHistory.size

    @Synchronized
    fun reset() {
        emissionHistory.clear()
        epochMeanBpm.clear()
        stillSeconds = 0.0
    }

    // ---------------------------------------------------------------------------------
    // Emission models (log-likelihood per stage; order AWAKE, LIGHT, DEEP, REM)
    // ---------------------------------------------------------------------------------
    private fun mvTerm(act: Actigraphy.Result, mu: Double, sd: Double): Double =
        if (act.hasData) Stats.gaussLog(act.moveFraction, mu, sd) else 0.0

    private fun hrvEmission(
        act: Actigraphy.Result,
        dHr: Double,
        rr: Double,
        hasRr: Boolean,
        cv: Double
    ): DoubleArray {
        val still = stillSeconds
        fun rrT(mu: Double, sd: Double) = if (hasRr) Stats.gaussLog(rr, mu, sd) else 0.0
        fun cvT(mu: Double, sd: Double) = if (cv.isFinite()) Stats.gaussLog(cv, mu, sd) else 0.0

        val e = DoubleArray(4)
        e[0] = mvTerm(act, 0.45, 0.30) + Stats.gaussLog(dHr, 6.0, 7.0) + rrT(-0.10, 0.60) +
                cvT(0.070, 0.040) - 0.8 * min(still / 300.0, 1.0) + config.awakeBias
        e[1] = mvTerm(act, 0.04, 0.08) + Stats.gaussLog(dHr, -1.0, 4.0) + rrT(0.0, 0.50) +
                cvT(0.050, 0.020)
        e[2] = mvTerm(act, 0.00, 0.03) + Stats.gaussLog(dHr, -6.0, 3.5) + rrT(0.35, 0.40) +
                cvT(0.035, 0.015) + 0.4 * min(still / 600.0, 1.0)
        e[3] = mvTerm(act, 0.02, 0.05) + Stats.gaussLog(dHr, 0.0, 4.0) + rrT(-0.25, 0.45) +
                cvT(0.060, 0.025) + 0.15 * min(still / 600.0, 1.0)
        return e
    }

    /**
     * Heart Rate Drop Delta model for BPM-only streams.
     *  - Deep needs an absolute drop of at least |deepDropBpm| (-4.0) below the daytime resting baseline,
     *    capped at the 60 BPM sleeping ceiling, plus stillness. Crossing the boundary is penalised linearly.
     *  - REM expects higher cardiorespiratory fluctuation (BPM variability inside the epoch and across the last
     *    ~5 minutes) instead of RMSSD, with a heart rate sitting between deep and light.
     *  - deepBias / remBias / awakeBias counter the reported deep and REM over-detection of BPM-only wearables
     *    and the over-sensitive awake counting seen in the screenshots.
     */
    private fun bpmDropEmission(act: Actigraphy.Result, dHr: Double, fluct: Double): DoubleArray {
        val still = stillSeconds
        fun flT(mu: Double, sd: Double) = if (fluct.isFinite()) Stats.gaussLog(fluct, mu, sd) else 0.0

        val e = DoubleArray(4)
        e[0] = mvTerm(act, 0.45, 0.30) + Stats.gaussLog(dHr, 7.0, 8.0) + flT(3.0, 2.0) -
                0.8 * min(still / 300.0, 1.0) + config.awakeBias
        e[1] = mvTerm(act, 0.04, 0.08) + Stats.gaussLog(dHr, -1.5, 4.0) + flT(1.4, 0.9)

        var deep = mvTerm(act, 0.00, 0.03) + Stats.gaussLog(dHr, deepDeltaLimit - 2.5, 2.5) +
                flT(0.9, 0.7) + 0.4 * min(still / 600.0, 1.0) + config.deepBias
        if (dHr > deepDeltaLimit) deep -= config.deepBoundaryPenaltyPerBpm * (dHr - deepDeltaLimit)
        e[2] = deep

        e[3] = mvTerm(act, 0.02, 0.05) + Stats.gaussLog(dHr, -2.5, 3.5) + flT(2.2, 1.0) +
                0.15 * min(still / 600.0, 1.0) + config.remBias
        return e
    }

    /** No usable cardiac data: movement decides AWAKE vs asleep; deep/REM stay below light. */
    private fun activityEmission(act: Actigraphy.Result): DoubleArray {
        val e = DoubleArray(4)
        val still = stillSeconds
        e[0] = mvTerm(act, 0.45, 0.30) - 0.8 * min(still / 300.0, 1.0) + config.awakeBias
        e[1] = mvTerm(act, 0.04, 0.08)
        e[2] = e[1] - 0.9 + 0.4 * min(still / 600.0, 1.0)
        e[3] = e[1] - 1.1
        return e
    }

    private fun sanitizeBpm(raw: DoubleArray): DoubleArray {
        val out = ArrayList<Double>(raw.size)
        for (v in raw) {
            if (v.isFinite() && v >= config.bpmRejectBelow && v <= config.bpmRejectAbove) out.add(v)
        }
        return out.toDoubleArray()
    }

    private fun pushMeanBpm(v: Double) {
        epochMeanBpm.addLast(v)
        if (epochMeanBpm.size > 10) epochMeanBpm.removeFirst()
    }

    // ---------------------------------------------------------------------------------
    // HMM / Viterbi smoothing layer
    // ---------------------------------------------------------------------------------
    companion object {
        private const val AWAKE_I = 0
        private const val LIGHT_I = 1
        private const val DEEP_I = 2
        private const val REM_I = 3

        /**
         * Per-epoch (30 s) transition probabilities, rows = from, columns = to (AWAKE, LIGHT, DEEP, REM).
         * Zeros are hard biological rules: no Deep<->REM, no Awake->Deep, no Awake->REM.
         */
        val TRANSITIONS: Array<DoubleArray> = arrayOf(
            doubleArrayOf(0.880, 0.120, 0.000, 0.000),
            doubleArrayOf(0.008, 0.965, 0.017, 0.010),
            doubleArrayOf(0.004, 0.036, 0.960, 0.000),
            doubleArrayOf(0.010, 0.022, 0.000, 0.968)
        )

        private val ALLOWED: Array<BooleanArray> = Array(4) { i ->
            BooleanArray(4) { j -> TRANSITIONS[i][j] > 0.0 }
        }

        /** Full-night decode: tempered emissions + time-of-night priors + Viterbi + minimum-run cleanup. */
        fun smooth(emissions: List<DoubleArray>, config: EngineConfig = EngineConfig()): List<Stage> {
            val t = emissions.size
            if (t == 0) return emptyList()
            val k = 4

            val logA = Array(k) { i ->
                DoubleArray(k) { j ->
                    val p = TRANSITIONS[i][j]
                    if (p > 0.0) ln(p) else Double.NEGATIVE_INFINITY
                }
            }
            val logPi = doubleArrayOf(ln(0.70), ln(0.30), ln(1e-6), ln(1e-6))

            val onset = estimateOnset(emissions)
            val logB = Array(t) { ti ->
                DoubleArray(k) { s ->
                    config.emissionBeta * emissions[ti][s] + timePrior(ti, s, onset, config.epochSeconds)
                }
            }

            val dp = Array(t) { DoubleArray(k) }
            val bp = Array(t) { IntArray(k) }
            for (s in 0 until k) dp[0][s] = logPi[s] + logB[0][s]
            for (ti in 1 until t) {
                for (j in 0 until k) {
                    var best = Double.NEGATIVE_INFINITY
                    var arg = 0
                    for (i in 0 until k) {
                        val v = dp[ti - 1][i] + logA[i][j]
                        if (v > best) {
                            best = v
                            arg = i
                        }
                    }
                    dp[ti][j] = best + logB[ti][j]
                    bp[ti][j] = arg
                }
            }

            val path = IntArray(t)
            var last = 0
            for (s in 1 until k) if (dp[t - 1][s] > dp[t - 1][last]) last = s
            path[t - 1] = last
            for (ti in t - 1 downTo 1) path[ti - 1] = bp[ti][path[ti]]

            val cleaned = enforceMinRuns(path, emissions, config)
            val all = Stage.values()
            return List(t) { all[cleaned[it]] }
        }

        /** Minutes spent per stage for a list of epochs. */
        fun summarize(stages: List<Stage>, epochSeconds: Int): Map<Stage, Double> {
            val out = LinkedHashMap<Stage, Double>()
            for (s in Stage.values()) out[s] = 0.0
            for (s in stages) out[s] = out.getValue(s) + epochSeconds / 60.0
            return out
        }

        private fun estimateOnset(emissions: List<DoubleArray>): Int {
            val t = emissions.size
            val arg = IntArray(t) { ti ->
                var b = 0
                for (s in 1 until 4) if (emissions[ti][s] > emissions[ti][b]) b = s
                b
            }
            var i = 0
            while (i + 10 <= t) {
                var asleep = 0
                for (j in i until i + 10) if (arg[j] != AWAKE_I) asleep++
                if (asleep >= 8) return i
                i++
            }
            return 0
        }

        private fun timePrior(t: Int, s: Int, onset: Int, epochSeconds: Int): Double {
            val hours = max(0.0, (t - onset) * epochSeconds / 3600.0)
            var p = 0.0
            when (s) {
                DEEP_I -> p = ln(0.4 + 1.6 * exp(-hours / 2.2))
                REM_I -> p = ln(0.5 + 0.8 * min(hours / 6.0, 1.0)) - 3.0 * max(0.0, 1.0 - hours / 0.75)
            }
            if (t < onset) {
                when (s) {
                    AWAKE_I -> p += 1.5
                    DEEP_I, REM_I -> p -= 3.0
                }
            }
            return p
        }

        /** Re-labels runs shorter than the stage minimum using only legal neighbour transitions. */
        private fun enforceMinRuns(path: IntArray, emissions: List<DoubleArray>, config: EngineConfig): IntArray {
            val p = path.copyOf()
            var pass = 0
            while (pass < 20) {
                pass++
                var changed = false
                var s = 0
                while (s < p.size) {
                    var e = s
                    while (e < p.size && p[e] == p[s]) e++
                    val lab = p[s]
                    if (e - s < config.minRunEpochs[lab]) {
                        val left = if (s > 0) p[s - 1] else -1
                        val right = if (e < p.size) p[e] else -1
                        var best = -1
                        var bestScore = Double.NEGATIVE_INFINITY
                        for (cand in intArrayOf(left, right)) {
                            if (cand < 0 || cand == lab) continue
                            val okLeft = left < 0 || left == cand || ALLOWED[left][cand]
                            val okRight = right < 0 || right == cand || ALLOWED[cand][right]
                            if (!okLeft || !okRight) continue
                            var sc = 0.0
                            for (ti in s until e) sc += emissions[ti][cand]
                            if (sc > bestScore) {
                                bestScore = sc
                                best = cand
                            }
                        }
                        if (best >= 0) {
                            for (ti in s until e) p[ti] = best
                            changed = true
                            break
                        }
                    }
                    s = e
                }
                if (!changed) break
            }
            return p
        }
    }
}
