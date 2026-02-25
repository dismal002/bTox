/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dismal.btox.ui.colorpicker;
import com.dismal.btox.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.res.Resources;
import androidx.fragment.app.DialogFragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;

import com.dismal.btox.ui.colorpicker.ColorPickerSwatch.OnColorSelectedListener;
import android.widget.Button;
import android.widget.EditText;
import android.text.InputType;
import android.graphics.Color;
import android.widget.Toast;
import android.text.TextUtils;
import android.content.DialogInterface;

/**
 * A dialog which takes in as input an array of colors and creates a palette allowing the user to
 * select a specific color swatch, which invokes a listener.
 */
public class ColorPickerDialog extends DialogFragment implements OnColorSelectedListener {

    public static final int SIZE_LARGE = 1;
    public static final int SIZE_SMALL = 2;

    protected AlertDialog mAlertDialog;

    protected static final String KEY_TITLE_ID = "title_id";
    protected static final String KEY_COLORS = "colors";
    protected static final String KEY_COLOR_CONTENT_DESCRIPTIONS = "color_content_descriptions";
    protected static final String KEY_SELECTED_COLOR = "selected_color";
    protected static final String KEY_COLUMNS = "columns";
    protected static final String KEY_SIZE = "size";

    protected int mTitleResId = R.string.color_picker_default_title;
    protected int[] mColors = null;
    protected String[] mColorContentDescriptions = null;
    protected int mSelectedColor;
    protected int mColumns;
    protected int mSize;

    private ColorPickerPalette mPalette;
    private ProgressBar mProgress;

    protected OnColorSelectedListener mListener;

    public ColorPickerDialog() {
        // Empty constructor required for dialog fragments.
    }

    public static ColorPickerDialog newInstance(int titleResId, int[] colors, int selectedColor,
            int columns, int size) {
        ColorPickerDialog ret = new ColorPickerDialog();
        ret.initialize(titleResId, colors, selectedColor, columns, size);
        return ret;
    }

    public void initialize(int titleResId, int[] colors, int selectedColor, int columns, int size) {
        setArguments(titleResId, columns, size);
        setColors(colors, selectedColor);
    }

    public void setArguments(int titleResId, int columns, int size) {
        Bundle bundle = new Bundle();
        bundle.putInt(KEY_TITLE_ID, titleResId);
        bundle.putInt(KEY_COLUMNS, columns);
        bundle.putInt(KEY_SIZE, size);
        setArguments(bundle);
    }

    public void setOnColorSelectedListener(OnColorSelectedListener listener) {
        mListener = listener;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getArguments() != null) {
            mTitleResId = getArguments().getInt(KEY_TITLE_ID);
            mColumns = getArguments().getInt(KEY_COLUMNS);
            mSize = getArguments().getInt(KEY_SIZE);
        }

        if (savedInstanceState != null) {
            mColors = savedInstanceState.getIntArray(KEY_COLORS);
            mSelectedColor = (Integer) savedInstanceState.getSerializable(KEY_SELECTED_COLOR);
            mColorContentDescriptions = savedInstanceState.getStringArray(
                    KEY_COLOR_CONTENT_DESCRIPTIONS);
        }
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Activity activity = getActivity();

        View view = LayoutInflater.from(getActivity()).inflate(R.layout.color_picker_dialog, null);
        mProgress = (ProgressBar) view.findViewById(android.R.id.progress);
        mPalette = (ColorPickerPalette) view.findViewById(R.id.color_picker);
        mPalette.init(mSize, getColumnsForScreen(activity), this);

        if (mColors != null) {
            showPaletteView();
        }

        Button enterHex = (Button) view.findViewById(R.id.enter_hex);
        if (enterHex != null) {
            enterHex.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final EditText input = new EditText(activity);
                    input.setInputType(InputType.TYPE_CLASS_TEXT);
                    input.setHint("#RRGGBB");
                    input.setText(String.format("#%06X", (0xFFFFFF & mSelectedColor)));

                    new AlertDialog.Builder(activity)
                        .setTitle(R.string.pref_app_color_enter_hex)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                String hex = input.getText().toString().trim();
                                if (TextUtils.isEmpty(hex)) return;
                                if (!hex.startsWith("#")) hex = "#" + hex;
                                try {
                                    int color = Color.parseColor(hex);
                                    onColorSelected(color);
                                } catch (IllegalArgumentException e) {
                                    Toast.makeText(activity, R.string.pref_app_color_invalid_hex, Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                }
            });
        }

        mAlertDialog = new AlertDialog.Builder(activity)
            .setTitle(mTitleResId)
            .setView(view)
            .create();

        return mAlertDialog;
    }

    @Override
    public void onColorSelected(int color) {
        if (mListener != null) {
            mListener.onColorSelected(color);
        }

        if (getTargetFragment() instanceof OnColorSelectedListener) {
            final OnColorSelectedListener listener =
                    (OnColorSelectedListener) getTargetFragment();
            listener.onColorSelected(color);
        }

        if (color != mSelectedColor) {
            mSelectedColor = color;
            // Redraw palette to show checkmark on newly selected color before dismissing.
            mPalette.drawPalette(mColors, mSelectedColor);
        }

        dismiss();
    }

    public void showPaletteView() {
        if (mProgress != null && mPalette != null) {
            mProgress.setVisibility(View.GONE);
            refreshPalette();
            mPalette.setVisibility(View.VISIBLE);
        }
    }

    public void showProgressBarView() {
        if (mProgress != null && mPalette != null) {
            mProgress.setVisibility(View.VISIBLE);
            mPalette.setVisibility(View.GONE);
        }
    }

    public void setColors(int[] colors, int selectedColor) {
        if (mColors != colors || mSelectedColor != selectedColor) {
            mColors = colors;
            mSelectedColor = selectedColor;
            refreshPalette();
        }
    }

    public void setColors(int[] colors) {
        if (mColors != colors) {
            mColors = colors;
            refreshPalette();
        }
    }

    public void setSelectedColor(int color) {
        if (mSelectedColor != color) {
            mSelectedColor = color;
            refreshPalette();
        }
    }

    public void setColorContentDescriptions(String[] colorContentDescriptions) {
        if (mColorContentDescriptions != colorContentDescriptions) {
            mColorContentDescriptions = colorContentDescriptions;
            refreshPalette();
        }
    }

    private void refreshPalette() {
        if (mPalette != null && mColors != null) {
            mPalette.drawPalette(mColors, mSelectedColor, mColorContentDescriptions);
        }
    }

    public int[] getColors() {
        return mColors;
    }

    public int getSelectedColor() {
        return mSelectedColor;
    }

    private int getColumnsForScreen(Activity activity) {
        if (activity == null) {
            return Math.max(2, mColumns);
        }

        Resources res = activity.getResources();
        final int swatch = res.getDimensionPixelSize(
                mSize == SIZE_LARGE ? R.dimen.color_swatch_large : R.dimen.color_swatch_small);
        final int margin = res.getDimensionPixelSize(
                mSize == SIZE_LARGE ? R.dimen.color_swatch_margins_large : R.dimen.color_swatch_margins_small);
        final int cellWidth = swatch + (margin * 2);
        if (cellWidth <= 0) {
            return Math.max(2, mColumns);
        }

        // AlertDialog content width is narrower than the full display width.
        // Account for:
        // 1) Alert dialog outer side margins (~24dp each side),
        // 2) Alert dialog content insets (~24dp each side),
        // 3) color_picker_dialog.xml FrameLayout padding (28dp each side).
        final float density = res.getDisplayMetrics().density;
        final int dialogOuterMarginsPx = Math.round(48f * density);
        final int dialogContentInsetsPx = Math.round(48f * density);
        final int layoutPaddingPx = Math.round(56f * density);
        final int widthSafetyPx = Math.round(8f * density);
        final int reservedWidth = dialogOuterMarginsPx + dialogContentInsetsPx + layoutPaddingPx + widthSafetyPx;
        final int availableWidth = Math.max(0, res.getDisplayMetrics().widthPixels - reservedWidth);
        final int maxColumnsThatFit = Math.max(2, availableWidth / cellWidth);
        return Math.max(2, Math.min(mColumns, maxColumnsThatFit));
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putIntArray(KEY_COLORS, mColors);
        outState.putSerializable(KEY_SELECTED_COLOR, mSelectedColor);
        outState.putStringArray(KEY_COLOR_CONTENT_DESCRIPTIONS, mColorContentDescriptions);
    }
}
