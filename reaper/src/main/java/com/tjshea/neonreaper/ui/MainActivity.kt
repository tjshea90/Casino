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

class MainActivity : Activity(), ReaperView.Host {

    override lateinit var wallet: Wallet
    override lateinit var audio: Audio
    private lateinit var root: FrameLayout
    private lateinit var game: ReaperView
    private lateinit var dialogs: Dialogs
    private var vault: VaultView? = null
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
        dialogs = Dialogs(this, wallet) { game.onWalletChanged(); hideSystemUi() }
        dialogs.toastBanner = { game.showMessage(it) }
        when {
            status == Wallet.LoadStatus.TAMPERED -> dialogs.tampered()
            !wallet.hasPassword -> dialogs.setupPassword()
            else -> wallet.pendingVault()?.let { v -> game.post { game.resumeVault(v) } }
        }
    }

    override fun openVault(result: VaultResult) {
        if (vault != null) return
        currentTrack = Audio.Track.VAULT
        val v = VaultView(this, audio, result) { paid ->
            vault?.let { root.removeView(it); it.release() }
            vault = null
            wallet.markVaultSeen()
            currentTrack = Audio.Track.BASE
            game.onVaultFinished(paid)
        }
        vault = v
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

    override fun openDeposit() { if (ready()) dialogs.deposit() else gate() }
    override fun openWithdraw() { if (ready()) dialogs.withdraw() else gate() }
    override fun openInfo() = dialogs.info(wallet.stake)
    override fun openStats() { if (ready()) dialogs.stats() else gate() }
    override fun openAutoPicker(onPicked: (Int) -> Unit) = dialogs.autoPicker(onPicked)

    override fun onPause() {
        super.onPause()
        game.onHostPause()
        audio.pauseMusic()
    }

    override fun onResume() {
        super.onResume()
        audio.playTrack(currentTrack)
    }

    override fun onDestroy() {
        super.onDestroy()
        game.release()
        vault?.release()
        audio.release()
    }
}
