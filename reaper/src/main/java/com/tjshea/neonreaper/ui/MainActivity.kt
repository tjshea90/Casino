package com.tjshea.neonreaper.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.tjshea.neonreaper.audio.Audio
import com.tjshea.neonreaper.data.Wallet
import com.tjshea.neonreaper.engine.VaultResult
import com.tjshea.neonreaper.gl.GameGLView

class MainActivity : Activity(), ReaperView.Host {

    override lateinit var wallet: Wallet
    override lateinit var audio: Audio
    private lateinit var root: FrameLayout
    private lateinit var game: ReaperView
    private lateinit var dialogs: Dialogs
    private var vault: VaultView? = null
    private var glView: GameGLView? = null
    private var currentTrack = Audio.Track.BASE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wallet = Wallet(applicationContext)
        val status = wallet.load()
        audio = Audio(applicationContext).apply { sfxOn = wallet.soundOn; musicOn = wallet.musicOn }
        audio.playTrack(Audio.Track.BASE)
        game = ReaperView(this, this)
        root = FrameLayout(this).apply { addView(game, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        setContentView(root)
        attach3d()
        dialogs = Dialogs(this, wallet) { game.onWalletChanged(); hideSystemUi() }
        dialogs.toastBanner = { game.showMessage(it) }
        dialogs.onCue = { when (it) {
            Dialogs.Cue.TAP -> audio.play(Audio.Sfx.CLICK, 0.7f)
            Dialogs.Cue.OPEN -> audio.play(Audio.Sfx.UI_OPEN, 0.8f)
            Dialogs.Cue.CLOSE -> audio.play(Audio.Sfx.UI_CLOSE, 0.7f)
        } }
        when {
            status == Wallet.LoadStatus.TAMPERED -> dialogs.tampered()
            !wallet.hasPassword -> dialogs.setupPassword()
            else -> {
                val se = wallet.pendingSeance()
                val v = wallet.pendingVault()
                if (se != null) game.post { game.resumeSeance(se) } else if (v != null) game.post { game.resumeVault(v) }
            }
        }
    }

    /** OpenGL ES 3 world behind the HUD; falls back to the 2D renderer if unsupported or it fails. */
    private fun attach3d() {
        val ok = try { GameGLView.supported(this) } catch (e: Throwable) { false }
        if (!ok) return
        val gv = try { GameGLView(this) } catch (e: Throwable) { return }
        gv.onFailed = {
            game.gl3d = null
            root.removeView(gv)
            glView = null
        }
        root.addView(gv, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        glView = gv
        gv.qualitySetting = wallet.quality
        game.gl3d = gv
    }

    override fun openVault(result: VaultResult) {
        if (vault != null) return
        currentTrack = Audio.Track.VAULT
        val v = VaultView(this, audio, result, game.takeBoughtPrice()) { paid ->
            vault?.let { root.removeView(it); it.release() }
            vault = null
            wallet.markVaultSeen()
            currentTrack = Audio.Track.BASE
            glView?.setActive(true)
            game.onVaultFinished(paid)
        }
        vault = v
        glView?.setActive(false)
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    private fun ready() = wallet.status == Wallet.LoadStatus.OK && wallet.hasPassword
    private fun gate() { if (wallet.status == Wallet.LoadStatus.TAMPERED) dialogs.tampered() else dialogs.setupPassword() }

    private fun uiOpen() {}   // the dialogs themselves play the open / close / tap cues (Dialogs.onCue)

    override fun openDeposit() { uiOpen(); if (ready()) dialogs.deposit() else gate() }
    override fun openWithdraw() { uiOpen(); if (ready()) dialogs.withdraw() else gate() }
    override fun openInfo() { uiOpen(); dialogs.info(wallet.stake) }
    override fun openStats() { uiOpen(); if (ready()) dialogs.stats() else gate() }
    override fun openAutoPicker(onPicked: (AutoPlan) -> Unit) { uiOpen(); dialogs.autoPicker(onPicked) }
    override fun openBuy(stake: Int, onChoice: (com.tjshea.neonreaper.data.Feature) -> Unit) { uiOpen(); if (ready()) dialogs.buyFeature(stake, onChoice) else gate() }
    override fun openSettings() { uiOpen(); dialogs.settings { applySettings() } }
    override fun openRank() { uiOpen(); dialogs.rank() }
    override fun openReality(onContinue: () -> Unit, onStop: () -> Unit) = dialogs.reality(onContinue, onStop)

    /** Pushes the saved settings (volumes, quality) into the audio and graphics layers. */
    private fun applySettings() {
        audio.sfxVolume = wallet.sfxVolume / 100f; audio.musicVolume = wallet.musicVolume / 100f
        glView?.qualitySetting = wallet.quality
        game.onWalletChanged()
    }

    override fun onPause() {
        super.onPause()
        game.onHostPause()
        glView?.onPause()
        audio.pauseMusic()
    }

    override fun onResume() {
        super.onResume()
        glView?.onResume()
        audio.playTrack(if (vault != null) Audio.Track.VAULT else game.musicTrack)
    }

    override fun onDestroy() {
        super.onDestroy()
        game.release()
        vault?.release()
        audio.release()
    }
}
