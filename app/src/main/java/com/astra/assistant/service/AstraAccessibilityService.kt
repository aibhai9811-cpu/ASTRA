package com.astra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class AstraAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // After a WhatsApp chat opens with a prepared message, tap its Send button.
    fun autoSendWhatsApp() {
        var attempts = 0
        val task = object : Runnable {
            override fun run() {
                attempts++
                if (clickSend() || attempts >= 14) return
                handler.postDelayed(this, 700)
            }
        }
        handler.postDelayed(task, 1800)
    }

    private fun clickSend(): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: return false
        if (!pkg.startsWith("com.whatsapp")) return false
        val byId = root.findAccessibilityNodeInfosByViewId("$pkg:id/send")
        if (byId != null) {
            for (node in byId) {
                if (node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        val byDescription = findByDescription(root, "send")
        if (byDescription != null) {
            return byDescription.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }

    private fun findByDescription(node: AccessibilityNodeInfo?, description: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString()
        if (desc != null && desc.equals(description, ignoreCase = true) && node.isClickable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findByDescription(node.getChild(i), description)
            if (found != null) return found
        }
        return null
    }

    // ---------------- Quick Settings tiles ----------------

    // Opens Quick Settings, finds the tile by its label and taps it. The result is reported later.
    fun toggleQuickSetting(labels: List<String>, desired: Boolean?, spoken: String) {
        if (!performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) {
            AssistantBridge.asyncReplies.tryEmit("Android did not open Quick Settings.")
            return
        }
        var attempts = 0
        val task = object : Runnable {
            override fun run() {
                attempts++
                val tile = findTile(labels)
                if (tile != null) {
                    tapTile(tile, labels, desired, spoken)
                    return
                }
                if (attempts >= 8) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    AssistantBridge.asyncReplies.tryEmit(
                        "I could not find the $spoken tile. Add it to your Quick Settings panel and try again."
                    )
                    return
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(task, 900)
    }

    private fun tapTile(tile: AccessibilityNodeInfo, labels: List<String>, desired: Boolean?, spoken: String) {
        val before = stateOf(nodeText(tile))
        if (desired != null && before == desired) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            AssistantBridge.asyncReplies.tryEmit("$spoken is already ${if (desired) "on" else "off"}.")
            return
        }
        val clicked = tile.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({
            val after = findTile(labels)?.let { stateOf(nodeText(it)) }
            performGlobalAction(GLOBAL_ACTION_BACK)
            val message = when {
                !clicked -> "Android did not let me tap the $spoken tile."
                after == true -> "$spoken is now on."
                after == false -> "$spoken is now off."
                else -> "I tapped the $spoken tile. Please check that it changed."
            }
            AssistantBridge.asyncReplies.tryEmit(message)
        }, 900)
    }

    private fun findTile(labels: List<String>): AccessibilityNodeInfo? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try {
            windows?.forEach { window -> window.root?.let { roots.add(it) } }
        } catch (e: Exception) {
            // Some phones do not allow reading the window list.
        }
        for (label in labels) {
            for (root in roots) {
                val found = search(root, label)
                if (found != null) return found
            }
        }
        return null
    }

    private fun search(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = nodeText(node).lowercase(Locale.ROOT)
        if (text.isNotEmpty() && (text.startsWith(label) || text.contains(label))) {
            var current: AccessibilityNodeInfo? = node
            var hops = 0
            while (current != null && hops < 4) {
                if (current.isClickable) return current
                current = current.parent
                hops++
            }
        }
        for (i in 0 until node.childCount) {
            val found = search(node.getChild(i), label)
            if (found != null) return found
        }
        return null
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        val parts = ArrayList<String>()
        node.text?.let { parts.add(it.toString()) }
        node.contentDescription?.let { parts.add(it.toString()) }
        if (Build.VERSION.SDK_INT >= 30) {
            node.stateDescription?.let { parts.add(it.toString()) }
        }
        return parts.joinToString(" ")
    }

    private fun stateOf(text: String): Boolean? {
        val lower = text.lowercase(Locale.ROOT)
        if (Regex("\\b(off|disabled|not connected)\\b").containsMatchIn(lower)) return false
        if (Regex("\\b(on|enabled|connected)\\b").containsMatchIn(lower)) return true
        return null
    }

    companion object {
        @Volatile
        var instance: AstraAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null

        fun perform(action: Int): Boolean = instance?.performGlobalAction(action) ?: false
    }
}
