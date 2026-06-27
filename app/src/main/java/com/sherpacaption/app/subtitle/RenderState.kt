package com.sherpacaption.app.subtitle

sealed class RenderState {
    data object Hidden : RenderState()
    data object Stable : RenderState()
    data object Transitional : RenderState()
    data object Unstable : RenderState()
}
