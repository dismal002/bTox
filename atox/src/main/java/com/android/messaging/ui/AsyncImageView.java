package com.android.messaging.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import android.text.TextUtils;

import com.dismal.btox.R;

/**
 * Lightweight AsyncImageView replacement: honor common attrs (placeholderDrawable, cornerRadius)
 * but avoid heavy media manager dependencies.
 */
public class AsyncImageView extends AppCompatImageView {
    private final int mCornerRadius;
    private final Path mRoundedCornerClipPath;
    private int mClipPathWidth;
    private int mClipPathHeight;

    public AsyncImageView(Context context) {
        this(context, null);
    }

    public AsyncImageView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AsyncImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        int corner = 0;
        Drawable placeholder = null;
        if (attrs != null) {
            final TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AsyncImageView,
                    0, 0);
            corner = a.getDimensionPixelSize(R.styleable.AsyncImageView_cornerRadius, 0);
            placeholder = a.getDrawable(R.styleable.AsyncImageView_placeholderDrawable);
            a.recycle();
        }
        mCornerRadius = corner;
        mRoundedCornerClipPath = new Path();
        if (placeholder != null) setBackground(placeholder);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (mCornerRadius > 0) {
            final int currentWidth = this.getWidth();
            final int currentHeight = this.getHeight();
            if (mClipPathWidth != currentWidth || mClipPathHeight != currentHeight) {
                final RectF rect = new RectF(0, 0, currentWidth, currentHeight);
                mRoundedCornerClipPath.reset();
                mRoundedCornerClipPath.addRoundRect(rect, mCornerRadius, mCornerRadius,
                        Path.Direction.CW);
                mClipPathWidth = currentWidth;
                mClipPathHeight = currentHeight;
            }

            final int saveCount = canvas.getSaveCount();
            canvas.save();
            canvas.clipPath(mRoundedCornerClipPath);
            super.onDraw(canvas);
            canvas.restoreToCount(saveCount);
        } else {
            super.onDraw(canvas);
        }
    }

    // Public compatibility method used by some layouts in upstream code.
    public void setImageResourceId(final String descriptor) {
        if (TextUtils.isEmpty(descriptor)) {
            setImageDrawable(null);
        }
        // This simplified view ignores descriptors; callers should set image via normal APIs.
    }
}
