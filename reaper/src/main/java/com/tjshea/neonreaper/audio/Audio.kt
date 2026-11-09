package com.tjshea.neonreaper.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import java.io.File
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.pow

/**
 * "DREAD" sound engine facade. Everything is synthesized in code (nothing ships in the APK): one-shots are rendered lazily on a
 * background thread (core effects first, then the BASE score, then the rest), cached as WAVs under cacheDir/audio_v4 and played by a
 * SoundPool; the music is a set of sample-aligned loop layers mixed by a streaming AudioTrack thread (adaptive intensity, ducking,
 * cross-fades). Anything not rendered yet is skipped silently; any failure leaves the game running silently, never crashing.
 *
 * See docs/AUDIO.md for the sound list, the music structure, the adaptive rules and the budgets.
 */
class Audio internal constructor(context: Context, autoRender: Boolean) {
    /** Normal constructor: renders in the background on a real device, stays idle under JVM unit tests. */
    constructor(context: Context) : this(context, onDevice())

    /**
     * AUDIO CONTRACT v3 ("DREAD"). The UI calls these names; the sound designer owns what they sound like.
     * Direction: dark, adult horror-cinematic, premium-slot punch (sub thump + body + high sparkle), tonal centre D minor.
     * NO music-box, giggles, cartoon boings or cute chimes. `rate` (0.5..2) is used by the UI to pitch a sound up a scale step
     * (ticks, scatter landings, multiplier ups, orbs), so those sounds must stay musical when pitched.
     */
    enum class Sfx {
        // ---- legacy names (still called; re-voice them darker) ----
        CLICK, SPIN, STOP, WIN, WIN_MED, BIG_WIN, THUNDER, RELIC, VAULT, ORB, HEARTBEAT, JACKPOT, GROWL, SLASH, TICK,
        ERROR, BELL, LAUGH /* deep reaper laugh */, CACKLE /* low demonic chuckle */, GIGGLE /* faint ghostly whisper-laugh */,
        CAW, CHATTER, WHOOSH, COINS, FANFARE, COUNT_END, PRESS,
        // ---- v3: UI / reels ----
        UI_OPEN, UI_CLOSE,            // dialog open / close (stone + soft whoosh)
        STAKE_CHANGE,                 // stake button: short mechanical tick (rate = stake step)
        REEL_THUD,                    // heavy reel stop used for wild / scatter / big symbols
        SPIN_START,                   // lever + chain + reels spinning up (~0.5 s)
        // ---- v3: wins (escalating). WIN_SMALL < WIN_BIG (SOUL REAPED 10x) < WIN_MEGA (DEATH'S TOLL 25x) < WIN_EPIC (HELLFIRE 50x) < WIN_APOCALYPSE (100x)
        WIN_SMALL, WIN_TICK /* count-up tick, pitched by rate */, WIN_BIG, WIN_MEGA, WIN_EPIC, WIN_APOCALYPSE,
        COIN_SHOWER,                  // ~3 s shower of soul-coins, loop-free
        // ---- v3: coffin scatter + THE SÉANCE free spins ----
        SCATTER_LAND,                 // a coffin lands on a reel (rate rises 1st/2nd/3rd...)
        COFFIN_OPEN,                  // lid creaks open + choir hit + whoosh of souls
        SEANCE_START,                 // big ritual stinger (~4 s) when free spins begin
        SEANCE_MULT_UP,               // multiplier step (rate rises with the multiplier)
        SEANCE_RETRIGGER,             // extra spins awarded
        SEANCE_END,                   // free spins finished stinger
        // ---- v3: tension ----
        ANTICIPATE,                   // ~2.5 s rising dread riser while the last reels still spin with 2 scatters/relics showing
        ANTICIPATE_HIT,               // the tension pays off (boom + choir)
        ANTICIPATE_MISS,              // the tension dies (deflating sub drop, short)
        // ---- v3: characters & atmosphere ----
        REAPER_WHISPER, REAPER_LAUGH, DOCTOR_RASP /* plague doctor breath */, DEMON_ROAR, SKULL_RATTLE, RAVEN_FLOCK /* several caws + wings */,
        WRAITH_WAIL, BONE_CRACK, CHAIN_RATTLE, LIGHTNING_CRACK, DOOR_SLAM, WIND_GUST, CANDLE_WHOOSH,
        // ---- v3: features ----
        WILD_LAND,                    // wild symbol lands (blade slash + impact)
        MINI_INTRO,                   // mini-feature character appears
        ORB_LAND,                     // soul vault orb lands (rate = pitch step)
        VAULT_DOOR,                   // vault opening: stone grind + chains + deep boom
        JACKPOT_MINI, JACKPOT_MINOR, JACKPOT_MAJOR, JACKPOT_GRAND,
        RELIC_FLY,                    // relic streaks to the meter
        BUY_CONFIRM,                  // feature bought: heavy coin drop + ritual hit
        // ---- v3: meta ----
        RANK_UP, ACHIEVEMENT, REALITY_CHECK /* gentle low bell */
    }

    /** BASE = idle/spinning dread; SEANCE = free-spin ritual; VAULT = hold&spin. BASE and SEANCE react to [setIntensity]. */
    enum class Track { BASE, VAULT, SEANCE }

    var sfxOn = true
    var musicOn = true
        set(v) { field = v; if (v) { applyWant(); resumeMusic() } else pauseMusic() }
    /** 0..1 master levels for the settings dialog. */
    var sfxVolume = 1f
    var musicVolume = 1f
        set(v) { field = v; mixer.volume = v.coerceIn(0f, 1f) }
    /** When true (default) long stingers duck the music by themselves (see [Sfx] durations in docs/AUDIO.md). */
    var autoDuck = true

    /**
     * Adaptive music intensity, 0..1 (smoothed by the engine, layer fades have time constants of 1.3 s up / 2.2 s down):
     * 0 calm idle, ~0.35 reels spinning, ~0.7 feature / anticipation, 1.0 big win. Only BASE and SEANCE react.
     */
    fun setIntensity(level: Float) { mixer.intensity = if (level.isNaN()) 0f else level.coerceIn(0f, 1f) }

    /** Temporarily lowers the music to [to] (fraction of normal) for [seconds]: ~150 ms attack, ~600 ms release. */
    fun duck(seconds: Float, to: Float = 0.35f) { try { mixer.duck(seconds, to) } catch (_: Throwable) {} }

    // ------------------------------------------------------------------------------------------------ state
    private val mixer = MusicMixer()
    private val lock = Any()
    private val parkLock = Object()
    private val tracks = EnumMap<Track, MixTrack>(Track::class.java)
    @Volatile private var released = false
    @Volatile private var wantTrack: Track = Track.BASE
    @Volatile private var musicThread: Thread? = null
    private val cacheRoot: File? = try { context.cacheDir } catch (_: Throwable) { null }
    private var cacheDir: File? = null
    private val sfxCount = Sfx.values().size
    private val pool: SoundPool? = try {
        SoundPool.Builder().setMaxStreams(12).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
    } catch (_: Throwable) { null }
    private val ids = IntArray(sfxCount)
    private val ready = AtomicIntegerArray(sfxCount)
    private val idToOrdinal = ConcurrentHashMap<Int, Int>()
    private val lastPlayNs = LongArray(sfxCount)
    private val lastRate = FloatArray(sfxCount)
    private val lastStream = IntArray(sfxCount)
    private val pending = ArrayList<Job>()
    private val io: ScheduledExecutorService? = try {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "neonreaper-audio-io").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    } catch (_: Throwable) { null }

    init {
        try {
            pool?.setOnLoadCompleteListener { _, sampleId, status ->
                val o = idToOrdinal[sampleId]
                if (o != null && status == 0) ready.set(o, 1)
            }
        } catch (_: Throwable) {}
        if (autoRender && cacheRoot != null) {
            try {
                Thread({ renderLoop() }, "neonreaper-audio-render").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
            } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------------------------------------------ effects
    /** Plays [s]; [volume] 0..1, [rate] 0.5..2 (pitch + speed). Cheap, never throws, silently skips what is not rendered yet. */
    fun play(s: Sfx, volume: Float = 1f, rate: Float = 1f) {
        if (!sfxOn || released) return
        try {
            val p = pool ?: return
            val o = s.ordinal
            if (ready.get(o) == 0) { bump(Job.OneShot(s)); return }
            val v = (volume * sfxVolume).coerceIn(0f, 1f)
            if (v < 0.002f) return
            val r = rate.coerceIn(0.5f, 2f)
            val now = System.nanoTime()
            if (now - lastPlayNs[o] < GUARD_NS && kotlin.math.abs(r - lastRate[o]) < 0.02f) return    // identical double trigger in the same frame
            lastPlayNs[o] = now; lastRate[o] = r
            lastStream[o] = p.play(ids[o], v, v, 1, 0, r)
            if (autoDuck) {
                val sp = SfxBank.spec(s)
                if (sp != null && sp.duckSec > 0.0) mixer.duck(sp.duckSec.toFloat(), (1.0 - (1.0 - sp.duckTo) * v).toFloat())
            }
        } catch (_: Throwable) {}
    }

    /** Fades out and stops the most recent instance of [s] (e.g. the ANTICIPATE riser when the tension resolves early). */
    fun stop(s: Sfx) {
        try {
            val p = pool ?: return
            val id = lastStream[s.ordinal]
            if (id == 0) return
            lastStream[s.ordinal] = 0
            val e = io
            if (e == null) { p.stop(id); return }
            p.setVolume(id, 0.5f, 0.5f)
            e.schedule({ try { p.setVolume(id, 0.2f, 0.2f) } catch (_: Throwable) {} }, 20, TimeUnit.MILLISECONDS)
            e.schedule({ try { p.stop(id) } catch (_: Throwable) {} }, 45, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------------------------------------ music
    /** Cross-fades (about 1.5 s) to track [t]; if its layers are not rendered yet the current music keeps playing until they are. */
    fun playTrack(t: Track) {
        wantTrack = t
        if (released) return
        try {
            if (musicOn) { applyWant(); resumeMusic() }
        } catch (_: Throwable) {}
    }

    fun pauseMusic() { try { mixer.paused = true } catch (_: Throwable) {} }

    private fun resumeMusic() {
        if (!musicOn || released) return
        try {
            mixer.paused = false
            synchronized(parkLock) { parkLock.notifyAll() }
        } catch (_: Throwable) {}
    }

    /** Stops everything and frees the native resources. Safe to call more than once. */
    fun release() {
        if (released) return
        released = true
        try { synchronized(parkLock) { parkLock.notifyAll() } } catch (_: Throwable) {}
        try { pool?.release() } catch (_: Throwable) {}
        try { io?.shutdownNow() } catch (_: Throwable) {}
        try { musicThread?.join(300) } catch (_: Throwable) {}
        try { synchronized(lock) { tracks.clear() } } catch (_: Throwable) {}
    }

    /** Makes the mixer play [wantTrack]: uses layers already in memory, otherwise loads the cached WAVs off the UI thread. */
    private fun applyWant() {
        val t = wantTrack
        if (released || mixer.currentTrack == t) return
        val mt = synchronized(lock) { tracks[t] }
        if (mt != null && mt.has(0)) { startMixer(mt); return }
        // not in memory: render what is missing first (L0 ends up in front) and load whatever is already cached
        val spec = Scores.tracks[t]
        if (spec != null) for (l in spec.layers.reversed()) bump(Job.Layer(t, l.index))
        val e = io ?: return
        try { e.execute { try { loadFromCache(t) } catch (_: Throwable) {} } } catch (_: Throwable) {}
    }

    private fun loadFromCache(t: Track) {
        val dir = cacheDir ?: return
        val spec = Scores.tracks[t] ?: return
        val mt = synchronized(lock) { tracks.getOrPut(t) { MixTrack(spec) } }
        for (l in spec.layers) {
            if (mt.has(l.index)) continue
            val pcm = Wav.read(File(dir, "music_${t.name}_L${l.index}.wav")) ?: continue
            if (pcm.frames == mt.frames) mt.put(l.index, MixLayer(pcm.l, pcm.r, l.width))
        }
        if (mt.has(0) && wantTrack == t) startMixer(mt)
    }

    private fun startMixer(mt: MixTrack) {
        if (released) return
        synchronized(lock) {
            if (musicThread == null) {
                val th = Thread({ musicLoop() }, "neonreaper-music").apply { isDaemon = true }
                musicThread = th
                th.start()
            }
        }
        mixer.setTrack(mt)
        // free layers of tracks we have left (BASE stays resident)
        synchronized(lock) { tracks.keys.retainAll { it == Track.BASE || it == mt.track || it == wantTrack } }
    }

    // ------------------------------------------------------------------------------------------------ audio thread
    private fun buildTrack(): AudioTrack? = try {
        val fmt = AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bytes = maxOf(min, CHUNK * 4 * 4)           // ~190 ms: ducking stays responsive, slow tablets keep a safety margin
        AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setBufferSizeInBytes(bytes).setTransferMode(AudioTrack.MODE_STREAM).build()
    } catch (_: Throwable) { null }

    private fun musicLoop() {
        var track: AudioTrack? = null
        try {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) {}
            track = buildTrack() ?: return
            val buf = ShortArray(CHUNK * 2)
            var started = false
            var hardwarePaced = false
            var written = 0L
            var t0 = System.nanoTime()
            var failures = 0
            while (!released) {
                if (mixer.silentAndPaused()) {
                    if (started) { try { track!!.pause(); track!!.flush() } catch (_: Throwable) {}; started = false }
                    synchronized(parkLock) { while (!released && mixer.silentAndPaused()) parkLock.wait(250) }
                    continue
                }
                if (!started) { track!!.play(); started = true; written = 0; t0 = System.nanoTime() }
                mixer.render(buf, CHUNK)
                val w0 = System.nanoTime()
                val r = track!!.write(buf, 0, buf.size)
                if (r < 0) {
                    // dead object / invalid operation: rebuild the track a few times, then give up silently
                    if (++failures > 5) return
                    try { track!!.release() } catch (_: Throwable) {}
                    track = buildTrack() ?: return
                    started = false
                    Thread.sleep(50)
                    continue
                }
                failures = 0
                if ((System.nanoTime() - w0) > 4_000_000L) hardwarePaced = true
                written += CHUNK
                if (!hardwarePaced) {                          // no real audio sink (emulated environments): never spin
                    val ahead = written.toDouble() / SR - (System.nanoTime() - t0) / 1e9
                    if (ahead > 0.25) Thread.sleep(((ahead - 0.2) * 1000).toLong())
                }
            }
        } catch (_: Throwable) {
        } finally {
            try { track?.stop() } catch (_: Throwable) {}
            try { track?.release() } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------------------------------------------ background rendering
    private fun bump(job: Job) {
        synchronized(pending) {
            val i = pending.indexOfFirst { it.file == job.file }
            if (i > 0) pending.add(0, pending.removeAt(i))
        }
    }

    /**
     * Whether the app heap has room for synthesising [job] right now (see [AudioRender.heapNeed]). On a small heap the asset is skipped
     * (the game stays silent for it, and it is tried again on the next launch) instead of risking an OutOfMemoryError in the UI thread.
     */
    private fun roomFor(job: Job): Boolean {
        fun free(): Long { val rt = Runtime.getRuntime(); return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory()) }
        if (AudioRender.fits(job, free())) return true
        Runtime.getRuntime().gc()                       // the figure counts garbage that has not been collected yet
        return AudioRender.fits(job, free())
    }

    private fun renderLoop() {
        try {
            val root = cacheRoot ?: return
            val dir = AudioRender.prepareCache(root)
            cacheDir = dir
            synchronized(pending) { pending.addAll(AudioRender.jobs()) }
            while (!released) {
                val job = synchronized(pending) { if (pending.isEmpty()) null else pending.removeAt(0) } ?: break
                try {
                    val f = File(dir, job.file)
                    when (job) {
                        is Job.OneShot -> {
                            if (!Wav.valid(f)) { if (!roomFor(job)) continue; val pcm = AudioRender.render(job); Wav.write(f, pcm) }
                            if (!released) {
                                val id = pool?.load(f.path, 1) ?: 0
                                if (id != 0) { ids[job.sfx.ordinal] = id; idToOrdinal[id] = job.sfx.ordinal }
                            }
                        }
                        is Job.Layer -> {
                            var pcm = Wav.read(f)
                            if (pcm == null) { if (!roomFor(job)) continue; pcm = AudioRender.render(job); Wav.write(f, pcm) }
                            val keep = job.track == Track.BASE || job.track == wantTrack
                            if (keep) {
                                val spec = Scores.tracks[job.track]!!
                                val mt = synchronized(lock) { tracks.getOrPut(job.track) { MixTrack(spec) } }
                                mt.put(job.index, MixLayer(pcm.l, pcm.r, spec.layers[job.index].width))
                                if (musicOn && wantTrack == job.track) applyWant()
                            }
                        }
                    }
                } catch (_: Throwable) { /* skip this asset */ }
                Thread.yield()
            }
        } catch (_: Throwable) {}
    }

    /** Track the mixer is currently playing (null before the first layer is available). Test / diagnostics hook. */
    internal val playingTrack: Track? get() = mixer.currentTrack

    /** Test hook: synthesizes everything into [out] (used to inspect audio off-device). */
    internal fun renderAllTo(out: File) { AudioRender.renderAllTo(out) }

    companion object {
        private const val CHUNK = 1024
        private const val GUARD_NS = 12_000_000L

        /** True on a real Android runtime (ART reports "Dalvik"); false under JVM unit tests, where no audio hardware exists. */
        internal fun onDevice(): Boolean = System.getProperty("java.vm.name") == "Dalvik"

        private val MINOR = intArrayOf(0, 2, 3, 5, 7, 8, 10)

        /** Playback rate that raises a D-based sound by [step] degrees of the D natural-minor scale (0 = unchanged, 7 = octave); clamped to 0.5..2. */
        fun scaleRate(step: Int): Float {
            val oct = Math.floorDiv(step, 7); val deg = Math.floorMod(step, 7)
            return 2.0.pow((MINOR[deg] + 12 * oct) / 12.0).toFloat().coerceIn(0.5f, 2f)
        }

        /** Rendered length of [s] in seconds (0 if unknown). */
        fun durationSeconds(s: Sfx): Float = (SfxBank.spec(s)?.sec ?: 0.0).toFloat()

        /** Seconds after the start at which the main impact of [s] lands (sync banners / flashes to it). */
        fun hitSeconds(s: Sfx): Float = (SfxBank.spec(s)?.hitAt ?: 0.0).toFloat()
    }
}
