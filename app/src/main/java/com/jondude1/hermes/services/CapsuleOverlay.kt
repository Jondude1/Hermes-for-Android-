package com.jondude1.hermes.services

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The floating pill that shows what Hermes is doing while it works inside another app. It carries a
 * Stop button, opens Hermes when tapped, and expands to ask for approval when the agent needs one.
 * Plain Views on an accessibility overlay window: no extra permission, no Compose lifecycle needed.
 */
class CapsuleOverlay(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var label: TextView? = null
    private var dot: View? = null
    private var dotAnim: ObjectAnimator? = null
    private var approvalBox: LinearLayout? = null
    private var approvalTitle: TextView? = null
    private var approvalDetail: TextView? = null

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, svc.resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).toInt()

    fun show(text: String, onStop: () -> Unit, onOpen: () -> Unit) = main.post {
        if (root == null) build(onStop, onOpen)
        label?.text = text
    }

    fun hide() = main.post {
        hideApprovalNow()
        dotAnim?.cancel(); dotAnim = null
        root?.let { runCatching { wm.removeView(it) } }
        root = null; label = null; dot = null
    }

    fun showApproval(title: String, detail: String, onDecision: (Boolean) -> Unit) = main.post {
        val box = approvalBox ?: return@post
        approvalTitle?.text = title
        approvalDetail?.text = detail
        // children of box: title, detail, then the buttons row [decline, approve]
        val buttons = box.getChildAt(2) as LinearLayout
        buttons.getChildAt(0).setOnClickListener { onDecision(false) }
        buttons.getChildAt(1).setOnClickListener { onDecision(true) }
        box.visibility = View.VISIBLE
        root?.let { runCatching { wm.updateViewLayout(it, it.layoutParams) } }
    }

    fun hideApproval() = main.post { hideApprovalNow() }

    private fun hideApprovalNow() {
        approvalBox?.visibility = View.GONE
    }

    /** A brief ring where the agent just tapped, so you can follow what it does. */
    fun pulseAt(x: Int, y: Int) = main.post {
        val size = dpi(64f)
        val ring = View(svc).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dpi(3f), TIDE)
                setColor(Color.TRANSPARENT)
            }
            alpha = 1f; scaleX = 0.5f; scaleY = 0.5f
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x - size / 2
            this.y = y - size / 2
        }
        runCatching { wm.addView(ring, lp) }.onFailure { return@post }
        ring.animate().scaleX(1.5f).scaleY(1.5f).alpha(0f).setDuration(520)
            .withEndAction { runCatching { wm.removeView(ring) } }.start()
    }

    private fun build(onStop: () -> Unit, onOpen: () -> Unit) {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(26f)
            setColor(0xF2_0A120D.toInt())
            setStroke(dpi(1f), HAZE)
        }
        val container = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f))
            minimumWidth = dpi(200f)
        }

        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val d = View(svc).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TIDE) }
        }
        row.addView(d, LinearLayout.LayoutParams(dpi(8f), dpi(8f)).apply { rightMargin = dpi(10f) })
        val l = TextView(svc).apply {
            setTextColor(BONE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { onOpen() }
        }
        row.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val stop = TextView(svc).apply {
            text = "Stop"; setTextColor(EMBER); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpi(14f), dpi(6f), 0, dpi(6f))
            setOnClickListener { onStop() }
        }
        row.addView(stop)
        container.addView(row)

        // Approval section: title, detail, then [Decline, Approve]
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dpi(10f), 0, 0)
        }
        val t = TextView(svc).apply {
            setTextColor(BONE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Typeface.DEFAULT_BOLD
        }
        val det = TextView(svc).apply {
            setTextColor(FOG); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dpi(4f), 0, dpi(10f))
        }
        val buttons = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
        fun pill(text: String, fill: Int, ink: Int) = TextView(svc).apply {
            this.text = text; setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(20f); setColor(fill) }
            setPadding(0, dpi(10f), 0, dpi(10f))
        }
        buttons.addView(pill("Decline", HAZE, BONE), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dpi(8f) })
        buttons.addView(pill("Approve", BONE, Color.BLACK), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(t); box.addView(det); box.addView(buttons)
        container.addView(box)

        val lp = WindowManager.LayoutParams(
            dpi(320f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpi(64f) // clears the Pixel's camera cutout
        }
        runCatching { wm.addView(container, lp) }.onFailure { return }

        dotAnim = ObjectAnimator.ofFloat(d, View.ALPHA, 1f, 0.25f).apply {
            duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
        root = container; label = l; dot = d
        approvalBox = box; approvalTitle = t; approvalDetail = det
    }

    private companion object {
        val TIDE = 0xFF7FC29B.toInt()
        val BONE = 0xFFEFE9DA.toInt()
        val FOG = 0xFF8E9C91.toInt()
        val HAZE = 0xFF1A2238.toInt()
        val EMBER = 0xFFE8A33D.toInt()
    }
}
