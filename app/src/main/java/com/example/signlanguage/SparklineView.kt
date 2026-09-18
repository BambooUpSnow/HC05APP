package com.example.signlanguage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 心率迷你折线图（Sparkline）。
 *
 * 完全自绘：只用 [Canvas] + [Paint]，不依赖任何第三方图表库。
 * - 背景画三根网格基线（上 / 中 / 下）
 * - 折线下方填充半透明色块
 * - 最后一个采样点画一个小圆点
 *
 * 使用方式：[setSamples] 传入最近的心率采样值（自动只保留最后 [MAX_POINTS] 个）。
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 最多绘制的采样点数 */
        private const val MAX_POINTS = 60

        /** 折线颜色（与心率卡片一致的 #FF5370） */
        private val LINE_COLOR = 0xFFFF5370.toInt()

        /** 折线下方填充色（同色低透明度） */
        private val FILL_COLOR = 0x2EFF5370

        /** 网格基线颜色 #334155 */
        private val GRID_COLOR = 0xFF334155.toInt()

        /** 末点圆点颜色 */
        private val DOT_COLOR = 0xFFFF93A8.toInt()
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 采样点（y 值即心率数值） */
    private val points = ArrayList<Float>()

    private var lineColor = LINE_COLOR

    init {
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 3f
        linePaint.strokeCap = Paint.Cap.ROUND
        linePaint.strokeJoin = Paint.Join.ROUND
        linePaint.color = lineColor

        fillPaint.style = Paint.Style.FILL
        fillPaint.color = FILL_COLOR

        gridPaint.style = Paint.Style.STROKE
        gridPaint.strokeWidth = 1f
        gridPaint.color = GRID_COLOR

        dotPaint.style = Paint.Style.FILL
        dotPaint.color = DOT_COLOR
    }

    /**
     * 设置采样数据（只保留最后 [MAX_POINTS] 个点）。
     */
    fun setSamples(values: List<Int>) {
        points.clear()
        if (values.isNotEmpty()) {
            val start = if (values.size > MAX_POINTS) values.size - MAX_POINTS else 0
            var i = start
            while (i < values.size) {
                points.add(values[i].toFloat())
                i++
            }
        }
        invalidate()
    }

    /**
     * 清空图表。
     */
    fun clearSamples() {
        points.clear()
        invalidate()
    }

    /**
     * 修改折线颜色（默认 #FF5370）。
     */
    fun setLineColor(color: Int) {
        lineColor = color
        linePaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = (width - paddingRight).toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (right <= left || bottom <= top) return

        // ---------- 网格基线 ----------
        val middle = (top + bottom) / 2f
        canvas.drawLine(left, top, right, top, gridPaint)
        canvas.drawLine(left, middle, right, middle, gridPaint)
        canvas.drawLine(left, bottom, right, bottom, gridPaint)

        val count = points.size
        if (count <= 0) return

        // ---------- 只有一个点时画圆点 ----------
        if (count == 1) {
            canvas.drawCircle((left + right) / 2f, middle, 4f, dotPaint)
            return
        }

        // ---------- 计算纵轴范围（上下各留 15% 空白，避免贴边） ----------
        var minValue = points[0]
        var maxValue = points[0]
        var i = 1
        while (i < count) {
            val v = points[i]
            if (v < minValue) minValue = v
            if (v > maxValue) maxValue = v
            i++
        }
        var span = maxValue - minValue
        if (span < 1f) span = 1f
        val padding = span * 0.15f
        val low = minValue - padding
        val range = (maxValue + padding) - low
        val plotHeight = bottom - top

        val stepX = (right - left) / (count - 1)

        // ---------- 折线 ----------
        val linePath = Path()
        // ---------- 折线下方的填充区域 ----------
        val areaPath = Path()
        areaPath.moveTo(left, bottom)

        i = 0
        while (i < count) {
            val x = left + stepX * i
            var y = bottom - (points[i] - low) / range * plotHeight
            if (y < top) y = top
            if (y > bottom) y = bottom
            if (i == 0) {
                linePath.moveTo(x, y)
            } else {
                linePath.lineTo(x, y)
            }
            areaPath.lineTo(x, y)
            i++
        }
        areaPath.lineTo(right, bottom)
        areaPath.close()

        canvas.drawPath(areaPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // ---------- 最后一个采样点 ----------
        val lastX = right
        var lastY = bottom - (points[count - 1] - low) / range * plotHeight
        if (lastY < top) lastY = top
        if (lastY > bottom) lastY = bottom
        canvas.drawCircle(lastX, lastY, 4f, dotPaint)
    }
}
