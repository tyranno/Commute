package com.commute.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 코발트 — the app's palette, built around the one thing it is for: reading a workday at a glance.
 *
 * The weekly bar is the hero. It stacks 근무 with 점심 punched out and 초과 laid on the end, at
 * thumbnail size, in both light and dark. Two rules fall out of that and everything here follows
 * them:
 *
 * 1. **Cool is normal, warm is the exception.** 초과근무 has to read as warm — heat means "over" and
 *    nobody has to be taught that. So the working colour must be cool, or the two fight. Cobalt is
 *    the working state; Gold is every kind of "more than planned"; Ember is "needs your attention".
 * 2. **Colour is data.** The chrome — surfaces, cards, bars, text — is near-neutral on purpose, so
 *    that saturation anywhere on screen means it is telling you something. This is the opposite of
 *    Material You spreading one wallpaper hue across every surface, which is what this app did
 *    before and why it had no look of its own.
 *
 * Chroma is deliberate, not incidental. An earlier verdigris draft was the right *structure* with
 * the wrong intensity: its tones were pastel and sat too close in value to the surface behind them,
 * so the whole app read washed out. Cobalt and Gold are both high-chroma, and the dark ground is
 * pushed deeper than a neutral grey to give them something to be vivid against.
 */

// --- core hues -------------------------------------------------------------------------------
/** 코발트 — 근무. The one colour that means "you were here". */
val Cobalt = Color(0xFF1552D8)
val CobaltBright = Color(0xFF4C8DFF)
val CobaltContainer = Color(0xFFD8E2FF)
val CobaltContainerDark = Color(0xFF1B3A7A)

/** 금 — 초과근무, 이석. Warm and fully saturated, so it separates from 근무 without a legend. */
val Gold = Color(0xFFA8730C)
val GoldBright = Color(0xFFF0B429)
val GoldContainer = Color(0xFFFFDFA8)
val GoldContainerDark = Color(0xFF6B4E00)

/** 잉걸 — 휴일, 오류. Redder than Gold, reserved for what must not be missed. */
val Ember = Color(0xFFC2412D)
val EmberBright = Color(0xFFFF6B57)
val EmberContainer = Color(0xFFFFDAD3)
val EmberContainerDark = Color(0xFF7A2418)

/** 청석 — 연차·외출, 퇴근. Declared and deliberate rather than exceptional: cool, and quiet enough
 * that it never competes with 근무 for the eye. */
val Steel = Color(0xFF4C5B75)
val SteelBright = Color(0xFF9DB2D8)
val SteelContainer = Color(0xFFD5DEF0)
val SteelContainerDark = Color(0xFF2C3A52)

// --- surfaces --------------------------------------------------------------------------------
/** The light surface. Cooled a touch off pure white so it belongs to the same family as the bar,
 * and so a long look at a work log isn't a look at a lightbulb. */
val Paper = Color(0xFFF7F8FC)
val PaperVariant = Color(0xFFE1E3EC)
val InkOnPaper = Color(0xFF14161C)

/** The dark surface. Deeper and bluer than a neutral charcoal — this is what makes Cobalt and Gold
 * read as vivid rather than muddy, which a mid-grey ground would not. */
val Midnight = Color(0xFF0F1319)
val MidnightVariant = Color(0xFF1B2029)
val PaperOnDark = Color(0xFFE6E9EF)

// --- data-only colours -------------------------------------------------------------------------
/** 점심·제외 — time that passed but doesn't count. Deliberately the only grey in the chart: legible
 * as a gap without competing with the hues on either side of it, and faintly blue so it reads as
 * part of the same chart rather than a hole in it. Fixed rather than pulled from the scheme because
 * it has to hold the same weight on both surfaces. */
val ExcludedLight = Color(0xFF9AA3B2)
val ExcludedDark = Color(0xFF5C6675)
