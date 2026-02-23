package com.android.messaging.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.util.AttributeSet;
import android.widget.EditText;

/**
 * Paste-to-plain-text EditText (ported from Messaging-goplay)
 */
public class PlainTextEditText extends EditText {
    private static final char OBJECT_UNICODE = '\uFFFC';

    public PlainTextEditText(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    // Intercept and modify the paste event. Let everything else through unchanged.
    @Override
    public boolean onTextContextMenuItem(final int id) {
        if (id == android.R.id.paste) {
            final int selectionStartPrePaste = getSelectionStart();
            final boolean result = super.onTextContextMenuItem(id);
            CharSequence text = getText();
            int selectionStart = getSelectionStart();
            int selectionEnd = getSelectionEnd();

            final int startIndex = selectionStart - 1;
            final int pasteStringLength = selectionStart - selectionStartPrePaste;
            if (pasteStringLength == 1 && text.charAt(startIndex) == OBJECT_UNICODE) {
                final ClipboardManager clipboard = (ClipboardManager)
                        getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                final ClipData clip = clipboard.getPrimaryClip();
                if (clip != null) {
                    ClipData.Item item = clip.getItemAt(0);
                    StringBuilder sb = new StringBuilder(text);
                    final String url = item.getText().toString();
                    sb.replace(selectionStartPrePaste, selectionStart, url);
                    text = sb.toString();
                    selectionStart = selectionStartPrePaste + url.length();
                    selectionEnd = selectionStart;
                }
            }

            setText(text.toString());
            setSelection(selectionStart, selectionEnd);
            return result;
        } else {
            return super.onTextContextMenuItem(id);
        }
    }
}
