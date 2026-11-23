package ru.uralgamebuilding.ovnezey

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import ru.uralgamebuilding.ovnezey.engine.Language

/**
 * Общее поведение экранов: язык интерфейса из настроек, полноэкранный режим,
 * диалоги-слои с закрытием по кнопке «Назад».
 */
abstract class BaseActivity : Activity() {
    protected val services: Services by lazy { Services.get(this) }
    private var appliedLanguage: Language? = null
    private var overlay: View? = null

    override fun attachBaseContext(newBase: Context) {
        val language = Services.get(newBase).settings.language
        appliedLanguage = language
        super.attachBaseContext(LocaleHelper.wrap(newBase, language))
    }

    override fun onResume() {
        super.onResume()
        if (appliedLanguage != services.settings.language) recreate()
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    protected fun showDialog(title: String?, message: String?, actions: List<DialogAction>) {
        dismissOverlay()
        overlay = PixelUi.showDialog(this, title, message, actions) { overlay = null }
    }

    protected fun hasOverlay(): Boolean = overlay != null

    /** Закрывает диалог-слой. Возвращает true, если он был открыт. */
    protected fun dismissOverlay(): Boolean {
        val view = overlay ?: return false
        (view.parent as? ViewGroup)?.removeView(view)
        overlay = null
        return true
    }

    override fun onBackPressed() {
        if (dismissOverlay()) return
        super.onBackPressed()
    }
}
