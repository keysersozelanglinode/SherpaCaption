package com.sherpacaption.app.subtitle

enum class SubtitleDisplayMode(
    val maxLines: Int,
    val textSizeSp: Float,
    val widthRatio: Float,
    val horizontalPaddingDp: Int,
    val verticalPaddingDp: Int
) {
    MOVIE(
        maxLines = 2,
        textSizeSp = 22f,
        widthRatio = 0.94f,
        horizontalPaddingDp = 20,
        verticalPaddingDp = 12
    ),
    LEARNING(
        maxLines = 3,
        textSizeSp = 20f,
        widthRatio = 0.96f,
        horizontalPaddingDp = 16,
        verticalPaddingDp = 12
    ),
    NEWS(
        maxLines = 3,
        textSizeSp = 20f,
        widthRatio = 0.96f,
        horizontalPaddingDp = 16,
        verticalPaddingDp = 12
    ),
    PODCAST(
        maxLines = 3,
        textSizeSp = 20f,
        widthRatio = 0.96f,
        horizontalPaddingDp = 16,
        verticalPaddingDp = 12
    )
}
