package com.tjshea.neonreaper.audio

import com.tjshea.neonreaper.audio.Audio.Sfx
import com.tjshea.neonreaper.audio.Audio.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Plain JUnit (no Robolectric): renders the whole audio set once (into reaper/build/audio, where tools/audio_report.py picks it
 * up), prints the render time, and asserts levels, durations, click-free edges, loop seams and the CPU / memory budgets.
 */
class AudioQaTest {
    companion object {
        private val dir = File("build/audio")
        private lateinit var report: AudioRender.Report
        private var cpuMs = 0L
        private val sfx = HashMap<Sfx, Pcm>()
        private val layers = HashMap<String, Pcm>()

        @BeforeClass @JvmStatic fun renderEverything() {
            val c0 = threadCpuNanos()
            report = AudioRender.renderAllTo(dir)
            cpuMs = (threadCpuNanos() - c0) / 1_000_000
            println("AUDIO RENDER: ${report.files} files, wall ${report.ms} ms, thread cpu $cpuMs ms, ${"%.2f".format(report.bytes / 1048576.0)} MB")
            for (j in AudioRender.jobs()) {
                val pcm = Wav.read(File(dir, j.file)) ?: throw AssertionError("cannot read back ${j.file}")
                when (j) { is Job.OneShot -> sfx[j.sfx] = pcm; is Job.Layer -> layers["${j.track.name}_${j.index}"] = pcm }
            }
        }

        /** CPU time of the calling thread via reflection (the Android unit-test compile classpath has no java.lang.management); wall time as fallback. */
        private fun threadCpuNanos(): Long = try {
            val mx = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
            Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime").invoke(mx) as Long
        } catch (_: Throwable) { System.nanoTime() }

        internal fun db(x: Double) = 20 * log10(max(x, 1e-9))
        internal fun rmsDb(p: Pcm): Double {
            var s = 0.0
            for (v in p.l) s += (v / 32768.0) * (v / 32768.0)
            p.r?.let { for (v in it) s += (v / 32768.0) * (v / 32768.0) }
            return db(sqrt(s / (p.frames * p.channels)))
        }
        internal fun peakDb(p: Pcm): Double {
            var m = 0
            for (v in p.l) m = max(m, abs(v.toInt()))
            p.r?.let { for (v in it) m = max(m, abs(v.toInt())) }
            return db(m / 32768.0)
        }
        internal fun dc(p: Pcm): Double { var s = 0.0; for (v in p.l) s += v; return s / p.frames / 32768.0 }
    }

    @Test fun everySfxHasADesignedSound() {
        for (s in Sfx.values()) assertTrue("no recipe for $s", SfxBank.defs[s] != null)
        assertEquals(Sfx.values().size, sfx.size)
    }

    @Test fun oneShotsAreCleanAndEvenlyLoud() {
        val bad = ArrayList<String>()
        for ((s, p) in sfx) {
            val sp = SfxBank.defs[s]!!.spec
            val peak = peakDb(p); val rms = rmsDb(p)
            if (peak > -1.0) bad += "$s peak ${"%.2f".format(peak)} dBFS > -1"
            if (abs(rms - sp.rms) > 2.0) bad += "$s rms ${"%.1f".format(rms)} vs target ${sp.rms}"
            if (abs(dc(p)) > 0.002) bad += "$s dc ${dc(p)}"
            if (abs(p.l[0].toInt()) > 33 || abs(p.l[p.frames - 1].toInt()) > 33) bad += "$s edge click ${p.l[0]} / ${p.l[p.frames - 1]}"   // 33/32768 = -60 dBFS
            if (p.seconds < sp.minSec || p.seconds > sp.maxSec) bad += "$s duration ${"%.2f".format(p.seconds)} outside ${sp.minSec}..${sp.maxSec}"
            if (p.bytes > 1_000_000) bad += "$s decoded size ${p.bytes} > 1 MB (SoundPool limit)"
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun briefTimingsAndEscalation() {
        fun sec(s: Sfx) = sfx[s]!!.seconds
        assertTrue("WIN_SMALL 0.5-0.8 s", sec(Sfx.WIN_SMALL) in 0.5..0.8)
        val ladder = listOf(Sfx.WIN_SMALL, Sfx.WIN, Sfx.WIN_MED, Sfx.WIN_BIG, Sfx.WIN_MEGA, Sfx.WIN_EPIC, Sfx.WIN_APOCALYPSE)
        for (i in 1 until ladder.size) {
            assertTrue("${ladder[i]} must be longer than ${ladder[i - 1]}", sec(ladder[i]) > sec(ladder[i - 1]))
            assertTrue("${ladder[i]} must be at least as loud as ${ladder[i - 1]}", rmsDb(sfx[ladder[i]]!!) >= rmsDb(sfx[ladder[i - 1]]!!) - 0.01)
        }
        val jack = listOf(Sfx.JACKPOT_MINI, Sfx.JACKPOT_MINOR, Sfx.JACKPOT_MAJOR, Sfx.JACKPOT_GRAND)
        for (s in jack) assertTrue("$s 2-6 s", sec(s) in 2.0..6.05)
        for (i in 1 until jack.size) assertTrue("${jack[i]} escalates", sec(jack[i]) > sec(jack[i - 1]) && rmsDb(sfx[jack[i]]!!) >= rmsDb(sfx[jack[i - 1]]!!) - 0.01)
        assertTrue(sec(Sfx.COUNT_END) <= 1.0)
        assertTrue(sec(Sfx.SPIN_START) <= 0.6); assertTrue(sec(Sfx.STOP) <= 0.35); assertTrue(sec(Sfx.REEL_THUD) <= 0.5)
        assertTrue(sec(Sfx.SEANCE_START) in 4.0..5.0); assertTrue(sec(Sfx.SEANCE_END) in 2.5..3.5); assertTrue(sec(Sfx.COFFIN_OPEN) in 2.2..2.8)
        assertTrue(sec(Sfx.ANTICIPATE) in 2.2..2.8); assertTrue(sec(Sfx.VAULT_DOOR) in 3.2..3.8); assertTrue(sec(Sfx.COIN_SHOWER) in 2.7..3.3)
        for (s in listOf(Sfx.REAPER_WHISPER, Sfx.REAPER_LAUGH, Sfx.DOCTOR_RASP, Sfx.DEMON_ROAR, Sfx.SKULL_RATTLE, Sfx.RAVEN_FLOCK, Sfx.WRAITH_WAIL))
            assertTrue("$s voice 0.8-2.5 s", sec(s) in 0.8..2.5)
        assertTrue(sec(Sfx.WIN_TICK) <= 0.15)
        // stacked ticks (~10 per second, up to 3 overlapping) must never clip: single peak <= -9 dBFS leaves 3 x -9 dB < 0 dBFS
        assertTrue(peakDb(sfx[Sfx.WIN_TICK]!!) <= -9.0)
        for (s in listOf(Sfx.CLICK, Sfx.TICK, Sfx.WIN_TICK, Sfx.STAKE_CHANGE)) assertTrue("$s about -24 dBFS", abs(rmsDb(sfx[s]!!) + 24.0) <= 1.5)
        for (s in listOf(Sfx.WIN_BIG, Sfx.WIN_MEGA, Sfx.WIN_EPIC, Sfx.WIN_APOCALYPSE, Sfx.JACKPOT_MAJOR, Sfx.JACKPOT_GRAND, Sfx.SEANCE_START))
            assertTrue("$s big stinger about -14..-16 dBFS (was ${rmsDb(sfx[s]!!)})", rmsDb(sfx[s]!!) in -16.5..-13.0)
    }

    @Test fun musicStructureAndLevels() {
        for ((track, spec) in Scores.tracks) {
            assertEquals(spec.layers.size, layers.keys.count { it.startsWith(track.name + "_") })
            val sumL = FloatArray(spec.loopFrames); val sumR = FloatArray(spec.loopFrames)
            for (l in spec.layers) {
                val p = layers["${track.name}_${l.index}"]!!
                assertEquals("${track.name} L${l.index} frames", spec.loopFrames, p.frames)
                assertEquals("${track.name} L${l.index} stereo flag", l.stereo, p.channels == 2)
                assertTrue("${track.name} L${l.index} rms ${rmsDb(p)} vs ${l.rms}", abs(rmsDb(p) - l.rms) <= 1.2)
                assertTrue("${track.name} L${l.index} peak ${peakDb(p)}", peakDb(p) <= -3.0)
                assertTrue("${track.name} L${l.index} dc ${dc(p)}", abs(dc(p)) < 0.001)
                for (i in 0 until spec.loopFrames) { sumL[i] += p.l[i] / 32768f; sumR[i] += (p.r ?: p.l)[i] / 32768f }
            }
            val sumDb = db(Master.rms(sumL, sumR))
            println("${track.name}: ${spec.layers.size} layers, loop ${"%.2f".format(spec.loopSeconds)} s, all layers on RMS ${"%.1f".format(sumDb)} dBFS")
            assertTrue("${track.name} summed RMS $sumDb should be about -22", sumDb in -24.0..-20.0)
        }
        assertTrue("BASE loop >= 60 s", Scores.BASE.loopSeconds >= 60.0 && Scores.BASE.layers.size >= 4)
        assertTrue("SEANCE 84-96 BPM", Scores.SEANCE.bpm in 84.0..96.0)
        assertTrue("VAULT ~108 BPM, 8-16 bars", Scores.VAULT.bpm == 108.0 && Scores.VAULT.bars in 8..16)
        // tempo grids are exact in samples
        for (spec in Scores.tracks.values) {
            val beat = SR * 60.0 / spec.bpm
            assertEquals(spec.bars * spec.beatsPerBar * beat, spec.loopFrames.toDouble(), 0.6)
        }
    }

    @Test fun loopsAreSeamless() {
        val bad = ArrayList<String>()
        for ((track, spec) in Scores.tracks) for (l in spec.layers) {
            val p = layers["${track.name}_${l.index}"]!!
            val n = p.frames
            val chans = listOfNotNull(p.l, p.r)
            var step = 0; var local = 1
            for (c in chans) {
                step = max(step, abs(c[0] - c[n - 1]))
                val k = SR / 20
                for (i in 0 until k - 1) { local = max(local, abs(c[i + 1] - c[i])); local = max(local, abs(c[n - 1 - i] - c[n - 2 - i])) }
            }
            if (step > 1.5 * local) bad += "${track.name} L${l.index}: seam step $step vs local max step $local"
            // level continuity across the seam: sustained layers are compared over 250 ms windows, rhythmic layers (where the pulse
            // phase decides the short-window RMS) over two-bar windows, the phrase unit of every layer
            fun rmsAt(from: Int, w: Int): Double { var s = 0.0; for (c in chans) for (i in from until from + w) s += (c[i] / 32768.0).let { it * it }; return sqrt(s / (w * chans.size)) }
            val ws = SR / 4; val wb = (2 * spec.beatsPerBar * SR * 60.0 / spec.bpm).toInt()
            val dShort = db(rmsAt(0, ws)) - db(rmsAt(n - ws, ws)); val dBars = db(rmsAt(0, wb)) - db(rmsAt(n - wb, wb))
            if (abs(dShort) > 6.0 && abs(dBars) > 5.0) bad += "${track.name} L${l.index}: RMS after/before seam ${"%.1f".format(dShort)} dB (250 ms), ${"%.1f".format(dBars)} dB (2 bars)"
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun musicLayersHaveNoSilentHoles() {
        val bad = ArrayList<String>()
        val w = SR / 4
        for ((track, spec) in Scores.tracks) for (l in spec.layers) {
            val p = layers["${track.name}_${l.index}"]!!
            var worst = 0.0; var at = 0
            for (k in 0 until p.frames / w) {
                var s = 0.0
                for (i in k * w until (k + 1) * w) { s += (p.l[i] / 32768.0).let { it * it }; if (p.r != null) s += (p.r!![i] / 32768.0).let { it * it } }
                val d = db(sqrt(s / (w * p.channels)))
                if (k == 0 || d < worst) { worst = d; at = k }
            }
            if (worst < -55.0) bad += "${track.name} L${l.index}: quietest 250 ms window ${"%.1f".format(worst)} dBFS at ${"%.1f".format(at * 0.25)} s"
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun budgets() {
        val sfxBytes = sfx.values.sumOf { it.bytes }
        val musicBytes = layers.values.sumOf { it.bytes }
        println("AUDIO SIZE: sfx ${"%.2f".format(sfxBytes / 1048576.0)} MB, music ${"%.2f".format(musicBytes / 1048576.0)} MB")
        assertTrue("SFX files <= ~12 MB", sfxBytes <= 12L * 1048576)
        assertTrue("music <= ~40 MB", musicBytes <= 40L * 1048576)
        assertTrue("render CPU $cpuMs ms must stay <= 4000 ms", cpuMs <= 4000)
    }

    @Test fun renderingIsDeterministic() {
        for (s in listOf(Sfx.WIN_MED, Sfx.REAPER_LAUGH, Sfx.COIN_SHOWER)) {
            val a = SfxBank.render(s); val b = SfxBank.render(s)
            assertTrue("$s differs between runs", a.l.contentEquals(b.l) && (a.r == null || a.r!!.contentEquals(b.r!!)))
        }
        val x = Scores.renderLayer(Track.VAULT, 0); val y = Scores.renderLayer(Track.VAULT, 0)
        assertTrue(x.l.contentEquals(y.l))
    }
}
