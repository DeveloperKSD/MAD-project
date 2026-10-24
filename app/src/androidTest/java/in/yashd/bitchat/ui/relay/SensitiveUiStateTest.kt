package `in`.yashd.bitchat.ui.relay

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

/** Recreates saved-state registries, without a ViewModel or database re-supplying drafts. */
class SensitiveUiStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun messageDraftDoesNotRestoreFromAndroidSavedState() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CurrentChat(RelayUiState(storageLoading = false), "invalid") }
        compose.onNode(hasSetTextAction()).performTextInput("PRIVATE_UNSENT_BODY")
        compose.onNode(hasSetTextAction()).assertTextContains("PRIVATE_UNSENT_BODY")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("PRIVATE_UNSENT_BODY").assertDoesNotExist()
    }

    @Test fun unsavedContactNameDoesNotRestoreFromAndroidSavedState() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { `in`.yashd.bitchat.RelayDesignTheme { OnlineChatScreen({ _, _ -> null }, {}, {}, {}) } }
        compose.onNodeWithText("Name for this chat (optional)").performTextInput("PRIVATE_CONTACT_DRAFT")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("PRIVATE_CONTACT_DRAFT").assertDoesNotExist()
        compose.onNodeWithText("Name for this chat (optional)").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun sensitiveSearchDoesNotRestoreFromAndroidSavedState() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CurrentChats() }
        compose.onNode(hasSetTextAction()).performTextInput("PRIVATE_SEARCH_TEXT")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("PRIVATE_SEARCH_TEXT").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }
}
