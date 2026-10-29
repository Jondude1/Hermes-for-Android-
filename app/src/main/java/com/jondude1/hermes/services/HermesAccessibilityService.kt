package com.jondude1.hermes.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Base64
import android.util.SparseArray
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Eyes and hands. Turns the current screen into a numbered text outline the model can read, and
 * performs taps, typing, scrolling and key presses. Only used while a task the person started is
 * running; Hermes never touches the screen on its own.
 */
class HermesAccessibilityService : AccessibilityService() {

    lateinit var capsule: CapsuleOverlay
        private set

    private val nodes = SparseArray<AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        capsule = CapsuleOverlay(this)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (::capsule.isInitialized) capsule.hide()
        instance = null
        return super.onUnbind(intent)
    }

    // ---- reading -----------------------------------------------------------------------------

    fun snapshot(): String {
        nodes.clear()
        val root = rootInActiveWindow
            ?: return "No readable window. The screen may be loading or protected. Try screenshot."
        val sb = StringBuilder("app: ${root.packageName}\n")
        var n = 0

        fun walk(node: AccessibilityNodeInfo) {
            if (n >= MAX_NODES || !node.isVisibleToUser) return
            val text = (node.text ?: node.contentDescription)?.toString()?.replace('\n', ' ')?.take(90)
            val interesting = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable || !text.isNullOrBlank()
            if (interesting) {
                val r = Rect().also { node.getBoundsInScreen(it) }
                nodes.put(n, node)
                sb.append('[').append(n).append("] ").append(role(node))
                if (!text.isNullOrBlank()) sb.append(" \"").append(text).append('"')
                val flags = buildList {
                    if (node.isClickable) add("clickable")
                    if (node.isEditable) add("editable")
                    if (node.isScrollable) add("scrollable")
                    if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                    if (node.isFocused) add("focused")
                }
                if (flags.isNotEmpty()) sb.append(" (").append(flags.joinToString(",")).append(')')
                sb.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n')
                n++
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }
        }
        walk(root)
        if (n == 0) sb.append("(no readable elements - try screenshot)\n")
        return sb.toString()
    }

    private fun role(n: AccessibilityNodeInfo): String =
        n.className?.toString()?.substringAfterLast('.') ?: "View"

    suspend fun screenshotJpegBase64(): String? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val out = runCatching {
                    val hw = result.hardwareBuffer
                    val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    hw.close()
                    if (bmp == null) null else {
                        val scale = 1024f / maxOf(bmp.width, bmp.height)
                        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                        val bos = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                        Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                    }
                }.getOrNull()
                if (cont.isActive) cont.resume(out)
            }

            override fun onFailure(errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }
        })
    }

    // ---- acting ------------------------------------------------------------------------------

    suspend fun tapIndex(index: Int): String {
        val node = nodes[index] ?: return "No element [$index]. Call read_screen again."
        node.refresh()
        val r = Rect().also { node.getBoundsInScreen(it) }
        capsule.pulseAt(r.centerX(), r.centerY())
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped [$index]."
        return if (tapGesture(r.centerX().toFloat(), r.centerY().toFloat())) "Tapped [$index] by gesture."
        else "Tap failed on [$index]."
    }

    suspend fun tapAt(x: Int, y: Int): String {
        capsule.pulseAt(x, y)
        return if (tapGesture(x.toFloat(), y.toFloat())) "Tapped ($x, $y)." else "Tap failed at ($x, $y)."
    }

    private suspend fun tapGesture(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return gesture(p, 60)
    }

    fun typeText(index: Int?, text: String, submit: Boolean): String {
        val node = if (index != null) nodes[index] else rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) {
            return if (index != null) "No element [$index]. Call read_screen again."
            else "No focused text field. Tap a field first or pass its index."
        }
        node.refresh()
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "Typing failed: that element isn't editable."
        if (submit) node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        return if (submit) "Typed and submitted." else "Typed."
    }

    suspend fun scroll(direction: String): Boolean {
        val forward = direction == "down" || direction == "right"
        val root = rootInActiveWindow
        val scrollable = root?.let { findFirst(it) { n -> n.isScrollable } }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (scrollable != null && scrollable.performAction(action)) return true

        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val p = Path()
        when (direction) {
            "down" -> { p.moveTo(w / 2, h * 0.72f); p.lineTo(w / 2, h * 0.28f) }
            "up" -> { p.moveTo(w / 2, h * 0.28f); p.lineTo(w / 2, h * 0.72f) }
            "right" -> { p.moveTo(w * 0.78f, h / 2); p.lineTo(w * 0.22f, h / 2) }
            else -> { p.moveTo(w * 0.22f, h / 2); p.lineTo(w * 0.78f, h / 2) }
        }
        return gesture(p, 350)
    }

    fun pressKey(key: String): Boolean {
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return performGlobalAction(action)
    }

    private fun findFirst(root: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (test(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findFirst(child, test)?.let { return it }
        }
        return null
    }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val dispatched = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    companion object {
        @Volatile var instance: HermesAccessibilityService? = null
        private const val MAX_NODES = 220
    }
}
