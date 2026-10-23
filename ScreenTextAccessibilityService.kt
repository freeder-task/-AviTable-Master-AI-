package com.example.aibotgame

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ScreenTextAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val builder = StringBuilder()
        collectText(root, builder, 0)
        latestVisibleText = builder.toString().take(6000)
        root.recycle()
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (node == null || depth > 20 || out.length > 6000) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) out.append(text).append('\n')
        if (desc.isNotEmpty() && desc != text) out.append(desc).append('\n')
        for (i in 0 until node.childCount) collectText(node.getChild(i), out, depth + 1)
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile var latestVisibleText: String = ""
    }
}
