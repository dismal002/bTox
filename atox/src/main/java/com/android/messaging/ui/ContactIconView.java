package com.android.messaging.ui;

import android.content.Context;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * Simplified ContactIconView: lightweight replacement that displays a contact avatar
 * and provides a click-handler toggle. This avoids heavy upstream dependencies.
 */
public class ContactIconView extends AppCompatImageView {
    private boolean mDisableClickHandler = false;

    public ContactIconView(Context context) {
        super(context);
    }

    public ContactIconView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ContactIconView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setImageClickHandlerDisabled(final boolean isHandlerDisabled) {
        mDisableClickHandler = isHandlerDisabled;
        setOnClickListener(null);
        setClickable(false);
    }

    public void setImageResourceUri(final Uri uri) {
        if (uri == null) {
            setImageDrawable(null);
        } else {
            try {
                setImageURI(uri);
            } catch (Exception e) {
                // fallback: ignore and leave placeholder
            }
        }
        maybeInitializeOnClickListener();
    }

    protected void maybeInitializeOnClickListener() {
        if (!mDisableClickHandler) {
            setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    // Intentionally minimal: upstream shows contact details; keep placeholder.
                }
            });
            setClickable(true);
        } else {
            setOnClickListener(null);
            setClickable(false);
        }
    }
}
