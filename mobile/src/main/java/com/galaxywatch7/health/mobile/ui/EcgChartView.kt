package com.galaxywatch7.health.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max
import kotlin.math.min

class EcgChartView(context: Context) : View(context) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(11, 122, 117)
        strokeWidth = 3f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 225, 230)
        strokeWidth = 1f
    }
    var samples: FloatArray = FloatArray(0)
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val midY = height / 2f
        canvas.drawLine(0f, midY, width.toFloat(), midY, axisPaint)
        if (samples.size < 2) return

        val stride = max(1, samples.size / max(1, width))
        val visible = samples.indices step stride
        val minValue = visible.minOf { samples[it] }
        val maxValue = visible.maxOf { samples[it] }
        val range = max(0.01f, maxValue - minValue)
        var previousX = 0f
        var previousY = yFor(samples[0], minValue, range)
        var pointIndex = 0
        for (sampleIndex in visible) {
            val x = pointIndex.toFloat() / max(1, samples.size / stride - 1) * width
            val y = yFor(samples[sampleIndex], minValue, range)
            canvas.drawLine(previousX, previousY, x, y, linePaint)
            previousX = x
            previousY = y
            pointIndex++
        }
    }

    private fun yFor(value: Float, minValue: Float, range: Float): Float {
        val normalized = ((value - minValue) / range).coerceIn(0f, 1f)
        return height - (normalized * height)
    }
}

