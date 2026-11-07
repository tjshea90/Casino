package com.tjshea.dragonhoard.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.tjshea.dragonhoard.audio.SoundFx
import com.tjshea.dragonhoard.data.Wallet
import com.tjshea.dragonhoard.engine.GameConfig

class MainActivity : Activity(), SlotView.Host {

    override lateinit var wallet: Wallet
    override lateinit var sfx: SoundFx
    private lateinit var slot: SlotView
    private lateinit var dialogs: Dialogs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wallet = Wallet(applicationContext)
        val status = wallet.load()
        sfx = SoundFx(applicationContext).apply { enabled = wallet.soundOn }
        slot = SlotView(this, this)
        setContentView(slot)
        dialogs = Dialogs(this, wallet) { slot.onWalletChanged(); hideSystemUi() }
        dialogs.toastBanner = { slot.showMessage(it) }
        when {
            status == Wallet.LoadStatus.TAMPERED -> dialogs.tampered()
            !wallet.hasPassword -> dialogs.setupPassword()
            else -> slot.onWalletChanged()
        }
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

    private fun ready(): Boolean = wallet.status == Wallet.LoadStatus.OK && wallet.hasPassword

    override fun openDeposit() { if (ready()) dialogs.deposit() else gate() }
    override fun openWithdraw() { if (ready()) dialogs.withdraw() else gate() }
    override fun openPaytable() = dialogs.paytable(GameConfig.LINE_BET_OPTIONS[wallet.lineBetIndex.coerceIn(0, GameConfig.LINE_BET_OPTIONS.size - 1)])
    override fun openStats() { if (ready()) dialogs.stats() else gate() }
    override fun openAutoPicker(onPicked: (Int) -> Unit) = dialogs.autoPicker(onPicked)

    private fun gate() {
        if (wallet.status == Wallet.LoadStatus.TAMPERED) dialogs.tampered() else dialogs.setupPassword()
    }

    override fun onPause() {
        super.onPause()
        slot.onHostPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        slot.release()
        sfx.release()
    }
}
