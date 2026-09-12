package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.ui.AddShortcutDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AddShortcutDialogTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun savesTrimmedKeywordAndPreservesPhrase() {
        var saved: Pair<String, String>? = null
        composeTestRule.setContent {
            MaterialTheme {
                AddShortcutDialog(onDismiss = {}, onSave = { keyword, phrase ->
                    saved = keyword to phrase
                })
            }
        }

        composeTestRule.onNodeWithText("الكلمة المفتاحية (مثل mn)")
            .performTextInput("  mn  ")
        composeTestRule.onNodeWithText("النص الممتد")
            .performTextInput("ينتهي الاشتراك %time+2h%")
        composeTestRule.onNodeWithText("حفظ").performClick()

        composeTestRule.runOnIdle {
            assertEquals("mn" to "ينتهي الاشتراك %time+2h%", saved)
        }
    }

    @Test
    fun doesNotSaveBlankKeywordOrPhrase() {
        var saved: Pair<String, String>? = null
        composeTestRule.setContent {
            MaterialTheme {
                AddShortcutDialog(onDismiss = {}, onSave = { keyword, phrase ->
                    saved = keyword to phrase
                })
            }
        }

        composeTestRule.onNodeWithText("حفظ").performClick()
        composeTestRule.runOnIdle { assertNull(saved) }

        composeTestRule.onNodeWithText("الكلمة المفتاحية (مثل mn)")
            .performTextInput("mn")
        composeTestRule.onNodeWithText("حفظ").performClick()
        composeTestRule.runOnIdle { assertNull(saved) }
    }
}
