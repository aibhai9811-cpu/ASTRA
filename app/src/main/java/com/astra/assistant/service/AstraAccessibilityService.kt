package com.astra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

    companion object {
        @Volatile
        var instance: AstraAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null

        fun perform(action: Int): Boolean = instance?.performGlobalAction(action) ?: false
    }
}
