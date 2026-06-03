package com.termux.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

/**
 * Shared "precision terminal HUD" chrome language.
 *
 * <p>One phosphor-green accent, frosted near-black surfaces, faint green
 * hairlines, borderless rounded press states, stroke-drawn icons. This is the
 * single source of truth for both the floating terminal and the main-activity
 * chrome (tab strip, Keys toolbar, snippets, overview, dialogs) so they stay
 * pixel-consistent.
 */
public final class TpChrome {

    private TpChrome() {}

    /** Phosphor green — the only accent. Selected / active / running / focus. */
    public static final int ACCENT       = 0xFF0BC989;
    /** Darker green for pressed/again states. */
    public static final int ACCENT_DIM   = 0xFF0AA876;

    /** Frosted near-black for floating bars/sheets that overlay other content. */
    public static final int GLASS_BG     = 0xE6090D0E; // alpha 230
    /** Opaque near-black surface. */
    public static final int SOLID_BG     = 0xFF090D0E;
    /** Slightly more opaque frosted surface for sheets that sit over the terminal. */
    public static final int SHEET_BG     = 0xF2090D0E; // alpha 242

    /** Faint phosphor-green hairline (dividers, frosted edges). */
    public static final int HAIRLINE     = 0x380BC989; // alpha 56
    /** Neutral subtle border for quiet card edges. */
    public static final int BORDER       = 0xFF1A2422;

    /** Muted light — primary chrome text. */
    public static final int TEXT         = 0xECC5DED5;
    /** Dim desaturated green-gray — secondary text, idle grips. */
    public static final int TEXT_DIM     = 0x8079968D;

    /** Borderless press tint (green). */
    public static final int PRESS        = 0x280BC989; // alpha 40
    /** Borderless press tint for destructive actions (red). */
    public static final int PRESS_CLOSE  = 0x32E8685C; // alpha 50
    /** Error / destructive / dead-session red. */
    public static final int ERROR        = 0xFFE8685C;

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Stroke width for the stroke-drawn HUD icons. */
    public static float strokePx(Context context) {
        return context.getResources().getDisplayMetrics().density * 1.3f;
    }

    public static GradientDrawable roundRect(int color, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    public static GradientDrawable roundRect(int color, float radiusPx, int strokeColor, int strokePx) {
        GradientDrawable drawable = roundRect(color, radiusPx);
        if (strokePx > 0)
            drawable.setStroke(strokePx, strokeColor);
        return drawable;
    }

    /** Rounded only on the top corners — sheet/header chrome over a transparent window. */
    public static GradientDrawable topRoundRect(int color, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadii(new float[]{radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f});
        return drawable;
    }

    /** Borderless rounded press background: transparent at rest, green (or red for
     *  destructive) while pressed. */
    public static Drawable pressBg(Context context, boolean destructive) {
        float radius = dp(context, 7);
        GradientDrawable pressed = roundRect(destructive ? PRESS_CLOSE : PRESS, radius);
        GradientDrawable idle = roundRect(Color.TRANSPARENT, radius);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[0], idle);
        return states;
    }
}
