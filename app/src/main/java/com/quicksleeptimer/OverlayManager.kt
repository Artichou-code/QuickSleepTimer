package com.quicksleeptimer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.quicksleeptimer.databinding.OverlayHudBinding

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Calque 1 : Night Dimmer
    private var dimmerView: View? = null
    private var currentDimmerAlpha: Float = 0f

    // Calque 2 : HUD Pill
    private var hudBinding: OverlayHudBinding? = null
    private var hudDismissRunnable: Runnable? = null
    private var isHudShowing: Boolean = false

    // Calque 3 : Stealth Shield (Masquage anti-flash de l'application Live)
    private var shieldView: View? = null
    private var shieldDismissRunnable: Runnable? = null

    val isHudVisible: Boolean
        get() = isHudShowing

    val currentAlpha: Float
        get() = currentDimmerAlpha

    // ==========================================
    // CALQUE 1 : NIGHT DIMMER (FULLSCREEN FILTER)
    // ==========================================

    fun showDimmer(alpha: Float) {
        mainHandler.post {
            if (!canDrawOverlays()) {
                Log.w(TAG, "Cannot show Dimmer: SYSTEM_ALERT_WINDOW permission missing")
                return@post
            }

            currentDimmerAlpha = alpha.coerceIn(0f, 0.98f)

            if (dimmerView == null) {
                val view = View(context).apply {
                    setBackgroundColor(Color.BLACK)
                    this.alpha = currentDimmerAlpha
                }

                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.FILL
                }

                try {
                    windowManager.addView(view, layoutParams)
                    dimmerView = view
                    Log.d(TAG, "Dimmer overlay added with alpha: $currentDimmerAlpha")
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding dimmer view", e)
                }
            } else {
                dimmerView?.animate()?.alpha(currentDimmerAlpha)?.setDuration(300)?.start()
            }
        }
    }

    fun setDimmerAlpha(alpha: Float) {
        mainHandler.post {
            currentDimmerAlpha = alpha.coerceIn(0f, 0.95f)
            dimmerView?.alpha = currentDimmerAlpha
        }
    }

    fun animateDimmerAlpha(targetAlpha: Float, durationMs: Long = 1500L) {
        mainHandler.post {
            if (dimmerView == null) {
                showDimmer(currentDimmerAlpha)
            }
            currentDimmerAlpha = targetAlpha.coerceIn(0f, 0.95f)
            dimmerView?.animate()
                ?.alpha(currentDimmerAlpha)
                ?.setDuration(durationMs)
                ?.start()
        }
    }

    fun hideDimmer(animated: Boolean = false) {
        mainHandler.post {
            dimmerView?.let { view ->
                if (animated) {
                    view.animate()
                        .alpha(0f)
                        .setDuration(1000L)
                        .withEndAction {
                            try {
                                windowManager.removeViewImmediate(view)
                                Log.d(TAG, "Dimmer overlay animated out and removed")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error removing dimmer view", e)
                            } finally {
                                dimmerView = null
                                currentDimmerAlpha = 0f
                            }
                        }
                        .start()
                } else {
                    try {
                        windowManager.removeViewImmediate(view)
                        Log.d(TAG, "Dimmer overlay removed")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error removing dimmer view", e)
                    } finally {
                        dimmerView = null
                        currentDimmerAlpha = 0f
                    }
                }
            }
        }
    }

    // ==========================================
    // CALQUE 2 : HUD BUBBLE (TOP-RIGHT PILL)
    // ==========================================

    fun showHud(title: String, subtitle: String? = null, autoDismissMs: Long = 4000L) {
        mainHandler.post {
            if (!canDrawOverlays()) {
                Log.w(TAG, "Cannot show HUD: SYSTEM_ALERT_WINDOW permission missing")
                return@post
            }

            // Cancel any pending dismissal
            hudDismissRunnable?.let { mainHandler.removeCallbacks(it) }

            if (hudBinding == null) {
                val inflater = LayoutInflater.from(context)
                val binding = OverlayHudBinding.inflate(inflater)

                binding.hudTitle.text = title
                if (!subtitle.isNullOrEmpty()) {
                    binding.hudSubtitle.text = subtitle
                    binding.hudSubtitle.visibility = View.VISIBLE
                } else {
                    binding.hudSubtitle.visibility = View.GONE
                }

                // Initial transparent state for fade-in animation
                binding.root.alpha = 0f

                val marginPx = (28 * context.resources.displayMetrics.density).toInt()
                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = marginPx
                    y = marginPx
                }

                try {
                    windowManager.addView(binding.root, layoutParams)
                    hudBinding = binding
                    isHudShowing = true

                    binding.root.animate()
                        .alpha(1f)
                        .setDuration(220)
                        .start()

                } catch (e: Exception) {
                    Log.e(TAG, "Error adding HUD overlay", e)
                    return@post
                }
            } else {
                // Already attached: simply update text
                hudBinding?.let { binding ->
                    binding.hudTitle.text = title
                    if (!subtitle.isNullOrEmpty()) {
                        binding.hudSubtitle.text = subtitle
                        binding.hudSubtitle.visibility = View.VISIBLE
                    } else {
                        binding.hudSubtitle.visibility = View.GONE
                    }
                    binding.root.animate().alpha(1f).setDuration(150).start()
                    isHudShowing = true
                }
            }

            // Schedule dismiss if autoDismissMs > 0
            if (autoDismissMs > 0) {
                scheduleHudDismiss(autoDismissMs)
            }
        }
    }

    fun updateHud(title: String, subtitle: String? = null) {
        mainHandler.post {
            hudBinding?.let { binding ->
                binding.hudTitle.text = title
                if (!subtitle.isNullOrEmpty()) {
                    binding.hudSubtitle.text = subtitle
                    binding.hudSubtitle.visibility = View.VISIBLE
                } else {
                    binding.hudSubtitle.visibility = View.GONE
                }
            }
        }
    }

    fun resetHudTimer(delayMs: Long = 4000L) {
        mainHandler.post {
            hudDismissRunnable?.let { mainHandler.removeCallbacks(it) }
            if (isHudShowing) {
                scheduleHudDismiss(delayMs)
            }
        }
    }

    private fun scheduleHudDismiss(delayMs: Long) {
        hudDismissRunnable = Runnable {
            hideHud(withAnimation = true)
        }
        mainHandler.postDelayed(hudDismissRunnable!!, delayMs)
    }

    fun hideHud(withAnimation: Boolean = true) {
        mainHandler.post {
            hudDismissRunnable?.let { mainHandler.removeCallbacks(it) }
            hudDismissRunnable = null

            val binding = hudBinding ?: return@post
            isHudShowing = false

            if (withAnimation) {
                binding.root.animate()
                    .alpha(0f)
                    .setDuration(350)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            removeHudViewImmediate(binding.root)
                        }
                    })
                    .start()
            } else {
                removeHudViewImmediate(binding.root)
            }
        }
    }

    private fun removeHudViewImmediate(rootView: View) {
        try {
            windowManager.removeViewImmediate(rootView)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing HUD view", e)
        } finally {
            if (hudBinding?.root === rootView) {
                hudBinding = null
            }
        }
    }

    // ==========================================
    // CALQUE 3 : STEALTH SHIELD (ANTI-FLASH OVERLAY)
    // ==========================================

    fun showStealthShield(durationMs: Long = 850L) {
        mainHandler.post {
            if (!canDrawOverlays()) return@post

            shieldDismissRunnable?.let { mainHandler.removeCallbacks(it) }

            if (shieldView == null) {
                val view = View(context).apply {
                    setBackgroundColor(Color.BLACK)
                    alpha = 1f
                }

                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE
                ).apply {
                    gravity = Gravity.FILL
                }

                try {
                    windowManager.addView(view, layoutParams)
                    shieldView = view
                    Log.d(TAG, "Stealth shield overlay added: blocking Live Channels blue flash")
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding stealth shield view", e)
                }
            } else {
                shieldView?.alpha = 1f
            }

            shieldDismissRunnable = Runnable {
                hideStealthShield(animated = true)
            }
            mainHandler.postDelayed(shieldDismissRunnable!!, durationMs)
        }
    }

    fun hideStealthShield(animated: Boolean = true) {
        mainHandler.post {
            shieldDismissRunnable?.let { mainHandler.removeCallbacks(it) }
            shieldDismissRunnable = null

            shieldView?.let { view ->
                if (animated) {
                    view.animate()
                        .alpha(0f)
                        .setDuration(250L)
                        .withEndAction {
                            try {
                                windowManager.removeViewImmediate(view)
                                Log.d(TAG, "Stealth shield overlay animated out and removed")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error removing stealth shield view", e)
                            } finally {
                                if (shieldView === view) {
                                    shieldView = null
                                }
                            }
                        }
                        .start()
                } else {
                    try {
                        windowManager.removeViewImmediate(view)
                        Log.d(TAG, "Stealth shield overlay immediately removed")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error removing stealth shield view", e)
                    } finally {
                        if (shieldView === view) {
                            shieldView = null
                        }
                    }
                }
            }
        }
    }

    private fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    companion object {
        private const val TAG = "QuickSleep_Overlay"
    }
}
