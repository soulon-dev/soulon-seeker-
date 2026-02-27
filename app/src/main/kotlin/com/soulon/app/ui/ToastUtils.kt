package com.soulon.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast

/**
 * 显示居中的“敬请期待” Toast 提示
 */
fun showComingSoonToast(context: Context) {
    val toast = Toast(context)
    
    val textView = TextView(context).apply {
        text = com.soulon.app.i18n.AppStrings.stakingComingSoonToast
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(60, 32, 60, 32)
        
        val backgroundDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#E6000000")) // 90% 不透明度的黑色
            cornerRadius = 16f // 圆角
        }
        background = backgroundDrawable
    }
    
    @Suppress("DEPRECATION")
    toast.view = textView
    toast.setGravity(Gravity.CENTER, 0, 0)
    toast.duration = Toast.LENGTH_SHORT
    toast.show()
}
