package com.praeceptamachinae.vrvncviewer;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.widget.ImageView;

public class FrameView extends ImageView {
    public interface FrameTransform {
        void updateMatrix(Matrix outMatrix, int viewW, int viewH, int frameW, int frameH);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameStore.Snapshot snapshot = new FrameStore.Snapshot();
    private final Matrix transformMatrix = new Matrix();
    private FrameStore frameStore;
    private FrameTransform frameTransform;
    private boolean flipHorizontal;
    private boolean splitEyes;
    private boolean running;
    private int lastVersion = -1;
    private Bitmap lastBitmap;

    public FrameView(Context context) {
        super(context);
        init();
    }

    public FrameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FrameView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setScaleType(ScaleType.MATRIX);
    }

    void setFrameStore(FrameStore store) {
        frameStore = store;
    }

    void setFrameTransform(FrameTransform transform) {
        frameTransform = transform;
    }

    void setFlipHorizontal(boolean enabled) {
        flipHorizontal = enabled;
    }

    void setSplitEyes(boolean enabled) {
        splitEyes = enabled;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (splitEyes) {
            int eyeWidth = getWidth() / 2;
            drawEye(canvas, 0, eyeWidth);
            drawEye(canvas, getWidth() - eyeWidth, eyeWidth);
        } else {
            super.onDraw(canvas);
        }
    }

    private void drawEye(Canvas canvas, int left, int width) {
        int checkpoint = canvas.save();
        canvas.translate(left, 0);
        canvas.clipRect(0, 0, width, getHeight());
        super.onDraw(canvas);
        canvas.restoreToCount(checkpoint);
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        handler.post(tick);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (frameStore != null) {
                frameStore.readSnapshot(snapshot);
                Bitmap frame = snapshot.frame;
                if (frame != null) {
                    if (frame != lastBitmap) {
                        lastBitmap = frame;
                        setImageBitmap(frame);
                    } else if (snapshot.version != lastVersion) {
                        invalidate();
                    }
                    lastVersion = snapshot.version;

                    int viewWidth = splitEyes ? getWidth() / 2 : getWidth();
                    if (viewWidth > 0 && getHeight() > 0) {
                        transformMatrix.reset();
                        if (frameTransform != null) {
                            frameTransform.updateMatrix(transformMatrix, viewWidth, getHeight(),
                                    snapshot.width, snapshot.height);
                        } else {
                            applyFitCenter(transformMatrix, viewWidth, getHeight(),
                                    snapshot.width, snapshot.height);
                        }
                        if (flipHorizontal) {
                            transformMatrix.postScale(-1.0f, 1.0f, viewWidth / 2.0f, getHeight() / 2.0f);
                        }
                        setImageMatrix(transformMatrix);
                    }
                } else if (lastBitmap != null) {
                    lastBitmap = null;
                    setImageBitmap(null);
                }
            }
            handler.postDelayed(this, 33);
        }
    };

    private static void applyFitCenter(Matrix outMatrix, int viewW, int viewH, int frameW, int frameH) {
        if (frameW <= 0 || frameH <= 0 || viewW <= 0 || viewH <= 0) {
            return;
        }
        float scale = Math.min((float) viewW / frameW, (float) viewH / frameH);
        float scaledW = frameW * scale;
        float scaledH = frameH * scale;
        float dx = (viewW - scaledW) / 2.0f;
        float dy = (viewH - scaledH) / 2.0f;
        outMatrix.reset();
        outMatrix.postScale(scale, scale);
        outMatrix.postTranslate(dx, dy);
    }
}
