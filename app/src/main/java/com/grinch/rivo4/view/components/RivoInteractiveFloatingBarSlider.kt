package com.grinch.rivo4.view.components

import androidx.compose.runtime.Composable

/** Reuses the existing live roundness control for the previously missing upstream entry point. */
@Composable
fun RivoInteractiveFloatingBarSlider(
    headline: String, supporting: String?, value: Float, isBlurEnabled: Boolean, iconOnly: Boolean,
    onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit
) {
    RivoInteractiveRoundnessSlider(headline = headline, supporting = supporting, value = value,
        valueRange = 0f..50f, steps = 0, onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished)
}
