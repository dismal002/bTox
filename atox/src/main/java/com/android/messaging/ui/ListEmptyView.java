package com.android.messaging.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dismal.btox.R;

/** A reusable view that shows a hint image and text for an empty list view. */
public class ListEmptyView extends LinearLayout {
    private ImageView emptyImageHint;
    private TextView emptyTextHint;

    public ListEmptyView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        emptyImageHint = findViewById(R.id.empty_image_hint);
        emptyTextHint = findViewById(R.id.empty_text_hint);
    }

    public void setImageHint(final int resId) {
        emptyImageHint.setImageResource(resId);
    }

    public void setTextHint(final int resId) {
        emptyTextHint.setText(getResources().getText(resId));
    }

    public void setTextHint(final CharSequence hintText) {
        emptyTextHint.setText(hintText);
    }

    public void setIsImageVisible(final boolean isImageVisible) {
        emptyImageHint.setVisibility(isImageVisible ? VISIBLE : GONE);
    }

    public void setIsVerticallyCentered(final boolean isVerticallyCentered) {
        final int gravity = isVerticallyCentered ? Gravity.CENTER : Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        ((LayoutParams) emptyImageHint.getLayoutParams()).gravity = gravity;
        ((LayoutParams) emptyTextHint.getLayoutParams()).gravity = gravity;
        getLayoutParams().height = isVerticallyCentered ? LayoutParams.WRAP_CONTENT : LayoutParams.MATCH_PARENT;
        requestLayout();
    }
}
