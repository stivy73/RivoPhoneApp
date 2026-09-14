package com.grinch.rivo4.view.screen.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.grinch.rivo4.R
import org.junit.Rule
import org.junit.Test

class ProviderKeyInputTest {
    @get:Rule val compose = createComposeRule()
    @Test fun keyIsMaskedAndCanBeShownHiddenAndPasted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            var value by remember { mutableStateOf("") }
            MaterialTheme { ProviderKeyInput(value, { value = it }, "API", true) }
        }
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("test-only-ui-secret")
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithText(context.getString(R.string.api_show)).performClick()
        field.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        field.assertTextContains("test-only-ui-secret")
        compose.onNodeWithText(context.getString(R.string.api_hide)).performClick()
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }
}
