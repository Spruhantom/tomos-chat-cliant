package uk.co.tomos.chat

import androidx.window.layout.FoldingFeature

/**
 * Layout posture derived from the fold state and hinge orientation reported by AndroidX WindowManager.
 * This intentionally does not infer posture from a device model or a guessed hinge angle.
 */
enum class FoldPosture {
    NONE,
    FLAT,
    BOOK,
    TABLETOP;

    companion object {
        fun from(feature: FoldingFeature?): FoldPosture {
            if (feature == null) return NONE
            return from(
                isHalfOpened = feature.state == FoldingFeature.State.HALF_OPENED,
                isVertical = feature.orientation == FoldingFeature.Orientation.VERTICAL
            )
        }

        internal fun from(isHalfOpened: Boolean, isVertical: Boolean): FoldPosture {
            if (!isHalfOpened) return FLAT
            return if (isVertical) BOOK else TABLETOP
        }
    }
}
