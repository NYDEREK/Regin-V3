package com.snyde.reginapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * 16 line sensors from left to right: raw ADC value above each bar,
 * sensor number below, dashed threshold line and the line position marker.
 */
class SensorBarsView extends View {
    static final int COUNT = 16;
    private static final float ADC_MAX = 4095.0f;

    private static final int TRACK = Color.rgb(30, 30, 33);
    private static final int BAR_OFF = Color.rgb(88, 88, 94);
    private static final int BAR_ON = Color.rgb(236, 236, 238);
    private static final int TEXT = Color.rgb(236, 236, 238);
    private static final int MUTED = Color.rgb(140, 140, 148);
    private static final int THRESHOLD = Color.rgb(245, 165, 36);

    private final int[] values = new int[COUNT];
    private int valueCount;
    private float threshold = 3700.0f;
    private int position;
    private int actives;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thresholdPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path marker = new Path();

    SensorBarsView(Context context) {
        super(context);
        setMinimumHeight(Math.round(dp(220)));

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));

        thresholdPaint.setStyle(Paint.Style.STROKE);
        thresholdPaint.setStrokeWidth(dp(1));
        thresholdPaint.setColor(THRESHOLD);
        thresholdPaint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(3)}, 0));
    }

    void setThreshold(float value) {
        threshold = value;
        invalidate();
    }

    void setData(int[] newValues, int newPosition, int newActives) {
        valueCount = Math.min(COUNT, newValues.length);
        System.arraycopy(newValues, 0, values, 0, valueCount);
        position = newPosition;
        actives = newActives;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float column = width / COUNT;

        // Largest text where "4095" still fits in one column.
        float textSize = sp(12);
        textPaint.setTextSize(textSize);
        float widest = textPaint.measureText("0000");
        float maxWidth = column - dp(2);
        if (widest > maxWidth) {
            textSize = textSize * maxWidth / widest;
            textPaint.setTextSize(textSize);
        }

        float valueRow = textSize + dp(8);
        float labelRow = textSize + dp(6);
        float markerRow = dp(12);
        float top = valueRow;
        float bottom = height - labelRow - markerRow;
        float chartHeight = Math.max(1.0f, bottom - top);
        float barWidth = Math.max(dp(4), column * 0.62f);
        float radius = dp(2);

        for (int i = 0; i < COUNT; i++) {
            float cx = column * (i + 0.5f);
            float left = cx - barWidth * 0.5f;
            float right = cx + barWidth * 0.5f;

            fillPaint.setColor(TRACK);
            rect.set(left, top, right, bottom);
            canvas.drawRoundRect(rect, radius, radius, fillPaint);

            if (i < valueCount) {
                int value = Math.max(0, Math.min((int) ADC_MAX, values[i]));
                boolean onLine = value > threshold;
                float barTop = bottom - chartHeight * (value / ADC_MAX);

                fillPaint.setColor(onLine ? BAR_ON : BAR_OFF);
                rect.set(left, barTop, right, bottom);
                canvas.drawRoundRect(rect, radius, radius, fillPaint);

                textPaint.setColor(onLine ? TEXT : MUTED);
                canvas.drawText(String.valueOf(value), cx, valueRow - dp(5) - textPaint.descent(), textPaint);
            }

            textPaint.setColor(MUTED);
            canvas.drawText(String.valueOf(i + 1), cx, height - dp(2) - textPaint.descent(), textPaint);
        }

        float thresholdY = bottom - chartHeight * (Math.max(0.0f, Math.min(ADC_MAX, threshold)) / ADC_MAX);
        canvas.drawLine(0.0f, thresholdY, width, thresholdY, thresholdPaint);

        // Firmware position: sensor k (1..16) on the line gives k * 1000.
        if ((valueCount > 0) && (actives > 0)) {
            float markerX = column * (position / 1000.0f - 0.5f);
            float markerTop = bottom + dp(3);
            marker.reset();
            marker.moveTo(markerX, markerTop);
            marker.lineTo(markerX - dp(5), markerTop + dp(7));
            marker.lineTo(markerX + dp(5), markerTop + dp(7));
            marker.close();
            fillPaint.setColor(TEXT);
            canvas.drawPath(marker, fillPaint);
        }
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(int value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
