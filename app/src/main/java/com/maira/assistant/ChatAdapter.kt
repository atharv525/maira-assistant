package com.maira.assistant

import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

data class ChatMsg(var text: String, val fromUser: Boolean)

class ChatAdapter(private val items: MutableList<ChatMsg>) : RecyclerView.Adapter<ChatAdapter.VH>() {
    class VH(val frame: FrameLayout, val tv: TextView) : RecyclerView.ViewHolder(frame)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt() }
        val frame = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(4), 0, dp(4))
        }
        val tv = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.80).toInt()
            setTextIsSelectable(true)
        }
        frame.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return VH(frame, tv)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val m = items[position]
        val ctx = h.tv.context
        h.tv.text = m.text
        val lp = h.tv.layoutParams as FrameLayout.LayoutParams
        if (m.fromUser) {
            lp.gravity = Gravity.END
            h.tv.setBackgroundResource(R.drawable.bubble_user)
            h.tv.setTextColor(0xFFFFFFFF.toInt())
        } else {
            lp.gravity = Gravity.START
            h.tv.setBackgroundResource(R.drawable.bubble_bot)
            h.tv.setTextColor(ContextCompat.getColor(ctx, R.color.text_dark))
        }
        h.tv.layoutParams = lp
    }

    override fun getItemCount() = items.size
}
