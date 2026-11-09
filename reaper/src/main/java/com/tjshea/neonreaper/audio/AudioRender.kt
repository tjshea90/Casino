package com.tjshea.neonreaper.audio

import com.tjshea.neonreaper.audio.Audio.Track
import java.io.File

/** One renderable asset: a one-shot effect or one loop layer of a music track. */
internal sealed class Job(val file: String) {
    class OneShot(val sfx: Audio.Sfx) : Job("${sfx.name}.wav")
    class Layer(val track: Track, val index: Int) : Job("music_${track.name}_L$index.wav")
}

/** Rendering order, WAV cache layout and the test hook. Pure JVM (java.io only). */
internal object AudioRender {
    /** Bump when a recipe changes: caches under cacheDir/audio_v4 with a different marker are wiped. */
    const val REV = 1
    const val CACHE_DIR = "audio_v4"
    private val OLD_DIRS = listOf("audio_v1", "audio_v2", "audio_v3")

    /**
     * Lazy priority order for the background renderer: core / UI effects first, then the BASE score (so the game is never
     * silent for long), then win and feature feedback, the rest of the effects, and finally the SEANCE and VAULT scores.
     */
    fun jobs(): List<Job> {
        val sfx = Audio.Sfx.values().filter { SfxBank.defs[it] != null }
        fun tier(t: Int) = sfx.filter { SfxBank.defs[it]!!.spec.tier == t }.map { Job.OneShot(it) }
        val out = ArrayList<Job>()
        out += tier(0)
        for (i in Scores.BASE.layers.indices) out += Job.Layer(Track.BASE, i)
        out += tier(1)
        out += tier(2)
        for (i in Scores.SEANCE.layers.indices) out += Job.Layer(Track.SEANCE, i)
        out += Job.Layer(Track.VAULT, 0)
        out += tier(3)
        return out
    }

    /**
     * Heap the synthesiser needs while it renders an asset, per output frame: measured on the 64 s BASE layers, which need ~45 MB of
     * live heap in total (dry bus + two reverb sends + the master copy + the PCM + its WAV image) = ~32 bytes per frame; 36 leaves ~10 %.
     * (On a device whose app heap is 96 MB, a first-launch render running next to the game could otherwise push ANY thread into an
     * OutOfMemoryError, not only the render thread that catches it.)
     */
    private const val RENDER_BYTES_PER_FRAME = 36L

    /** Heap in bytes [job] needs while it renders. */
    fun heapNeed(job: Job): Long {
        val frames = when (job) {
            is Job.Layer -> Scores.tracks[job.track]?.loopFrames ?: 0
            is Job.OneShot -> frames(SfxBank.spec(job.sfx)?.sec ?: 0.0)
        }
        return frames.toLong() * RENDER_BYTES_PER_FRAME
    }

    /** True when [freeHeap] bytes are enough to render [job] without endangering the other threads of the app. */
    fun fits(job: Job, freeHeap: Long): Boolean = freeHeap >= heapNeed(job)

    fun render(job: Job): Pcm = when (job) {
        is Job.OneShot -> SfxBank.render(job.sfx)
        is Job.Layer -> Scores.renderLayer(job.track, job.index)
    }

    /** Deletes caches of older audio generations and (re)creates the current one; returns the directory. */
    fun prepareCache(cacheRoot: File): File {
        for (o in OLD_DIRS) File(cacheRoot, o).deleteRecursively()
        val dir = File(cacheRoot, CACHE_DIR)
        val marker = File(dir, ".rev")
        val ok = try { marker.isFile && marker.readText().trim() == REV.toString() } catch (_: Throwable) { false }
        if (!ok) { dir.deleteRecursively() }
        dir.mkdirs()
        if (!ok) try { marker.writeText(REV.toString()) } catch (_: Throwable) {}
        return dir
    }

    class Report(val ms: Long, val files: Int, val bytes: Long, val perJob: List<Pair<String, Double>>)

    /** Test hook: renders every asset into [out] as `NAME.wav` (effects) and `music_TRACK_Lk.wav` (loop layers). */
    fun renderAllTo(out: File, only: (Job) -> Boolean = { true }): Report {
        out.mkdirs()
        val t0 = System.nanoTime()
        var bytes = 0L; var n = 0
        val per = ArrayList<Pair<String, Double>>()
        for (j in jobs()) {
            if (!only(j)) continue
            val t1 = System.nanoTime()
            val pcm = render(j)
            Wav.write(File(out, j.file), pcm)
            bytes += pcm.bytes; n++
            per += j.file to (System.nanoTime() - t1) / 1e6
        }
        return Report((System.nanoTime() - t0) / 1_000_000, n, bytes, per)
    }
}
