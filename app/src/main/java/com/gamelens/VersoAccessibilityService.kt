package com.gamelens

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.gamelens.ui.RegionDragView
import com.gamelens.ui.RegionOverlayView

/**
 * Minimal AccessibilityService whose only purpose is to call
 * [takeScreenshot] on a specific display.
 *
 * WHY AN ACCESSIBILITY SERVICE?
 * ─────────────────────────────
 * MediaProjection captures the display the requesting Activity runs on.
 * Launching a bridge Activity on the game display caused the whole app
 * to move there. AccessibilityService.takeScreenshot(displayId) captures
 * any display by ID with no UI, no focus change, and no app relocation.
 *
 * SETUP (one-time)
 * ─────────────────
 * Settings → Accessibility → Installed apps → PlayTranslate → Enable
 * The app detects the enabled state via [isEnabled].
 */
class PlayTranslateAccessibilityService : AccessibilityService() {

    private var overlayView: RegionOverlayView? = null
    private var overlayWm: WindowManager? = null
    private var dragView: RegionDragView? = null
    private var dragWm: WindowManager? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hideRegionOverlay()
        hideRegionDragOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    fun showRegionOverlay(
        display: Display,
        topFraction: Float, bottomFraction: Float,
        leftFraction: Float = 0f, rightFraction: Float = 1f
    ) {
        hideRegionOverlay()
        val wm = createDisplayContext(display).getSystemService(WindowManager::class.java) ?: return
        val view = RegionOverlayView(this).apply {
            updateRegion(topFraction, bottomFraction, leftFraction, rightFraction)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, params)
        overlayWm = wm
        overlayView = view
    }

    fun updateRegionOverlay(
        topFraction: Float, bottomFraction: Float,
        leftFraction: Float = 0f, rightFraction: Float = 1f
    ) {
        overlayView?.updateRegion(topFraction, bottomFraction, leftFraction, rightFraction)
    }

    fun hideRegionOverlay() {
        try { overlayView?.let { overlayWm?.removeView(it) } } catch (_: Exception) {}
        overlayView = null
        overlayWm = null
    }

    fun showRegionDragOverlay(display: Display, onRegionChanged: (Float, Float, Float, Float) -> Unit) {
        hideRegionDragOverlay()
        val wm = createDisplayContext(display).getSystemService(WindowManager::class.java) ?: return
        val view = RegionDragView(this).apply {
            setRegion(0.25f, 0.75f, 0.25f, 0.75f)
            this.onRegionChanged = onRegionChanged
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, params)
        dragWm = wm
        dragView = view
    }

    fun hideRegionDragOverlay() {
        try { dragView?.let { dragWm?.removeView(it) } } catch (_: Exception) {}
        dragView = null
        dragWm = null
    }

    fun getDragRegion(): Array<Float> = dragView?.getFullRegion() ?: arrayOf(0.25f, 0.75f, 0.25f, 0.75f)

    // We don't need to monitor accessibility events — just screenshots
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Takes a screenshot of [displayId] and returns the result as a software
     * [Bitmap] via [onResult]. Returns null on failure or if API < 30.
     *
     * Must be called on a thread that has a Looper (e.g. main thread).
     */
    fun captureDisplay(displayId: Int, onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "takeScreenshot requires API 30+")
            onResult(null)
            return
        }

        takeScreenshot(
            displayId,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    // HardwareBuffer → software Bitmap so ML Kit can read pixels
                    val bitmap = Bitmap
                        .wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    onResult(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "takeScreenshot failed on display $displayId, code=$errorCode")
                    onResult(null)
                }
            }
        )
    }

    companion object {
        private const val TAG = "PlayTranslateA11y"

        /** Non-null while the service is connected (i.e. user has it enabled). */
        var instance: PlayTranslateAccessibilityService? = null

        val isEnabled: Boolean get() = instance != null
    }
}
