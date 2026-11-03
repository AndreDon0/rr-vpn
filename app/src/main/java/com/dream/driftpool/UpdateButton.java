package com.dream.driftpool;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Compact, determinate download ring. The completed state has no repeating animation. */
final class UpdateButton extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private float progress;
    private boolean ready;
    private ValueAnimator animation;
    UpdateButton(Context context) {
        super(context); density = getResources().getDisplayMetrics().density;
        setClickable(true); setFocusable(true);
        android.util.TypedValue value = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
        setBackgroundResource(value.resourceId);
    }
    void show(UpdateSession.Snapshot state) {
        setVisibility(state.visible() ? VISIBLE : GONE);
        boolean complete = state.phase() == UpdateSession.Phase.READY;
        float target = complete ? 1 : state.progress();
        if (complete != ready || Math.abs(progress - target) > .001f) {
            ready = complete;
            if (animation != null) animation.cancel();
            if (ready || !isShown()) { progress = target; invalidate(); }
            else {
                animation = ValueAnimator.ofFloat(progress, target); animation.setDuration(140);
                animation.addUpdateListener(value -> { progress = (float) value.getAnimatedValue(); invalidate(); }); animation.start();
            }
        }
        int description = switch (state.phase()) {
            case READY -> R.string.update_ready;
            case WAITING_WIFI -> R.string.update_waiting_wifi;
            case VERIFYING -> R.string.update_verifying;
            case ERROR -> R.string.update_failed_short;
            case PAUSED -> R.string.update_paused;
            default -> R.string.update_progress;
        };
        setContentDescription(getResources().getString(description, Math.round(state.progress() * 100)));
    }
    @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info); info.setClassName(android.widget.Button.class.getName());
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float x = getWidth() / 2f, y = getHeight() / 2f, radius = 17 * density;
        paint.setStyle(Paint.Style.FILL); paint.setColor(ready ? 0xffe8efff : Color.WHITE); canvas.drawCircle(x, y, radius + density, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.4f * density); paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xffdce4f3); canvas.drawCircle(x, y, radius, paint);
        paint.setColor(0xff2e5fdd);
        if (progress > 0) canvas.drawArc(new RectF(x-radius,y-radius,x+radius,y+radius), -90, progress * 360, false, paint);
        paint.setStrokeWidth(1.8f * density);
        Path arrow = new Path(); arrow.moveTo(x,y-7*density); arrow.lineTo(x,y+3*density);
        arrow.moveTo(x-4*density,y-density); arrow.lineTo(x,y+3*density); arrow.lineTo(x+4*density,y-density);
        arrow.moveTo(x-6*density,y+7*density); arrow.lineTo(x+6*density,y+7*density); canvas.drawPath(arrow,paint);
    }
    @Override protected void onDetachedFromWindow() { if (animation != null) animation.cancel(); super.onDetachedFromWindow(); }
}
