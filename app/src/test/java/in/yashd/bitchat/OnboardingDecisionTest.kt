package `in`.yashd.bitchat

import org.junit.Assert.*
import org.junit.Test

class OnboardingDecisionTest {
    @Test fun freshInstallSeesFirstRun() {
        assertTrue(OnboardingDecision.showFirstRun(onboardingDone = false,
            identityFileExists = false, identityPredatesProcess = false, appUpgraded = false))
    }

    @Test fun completedOnboardingNeverRepeats() {
        assertFalse(OnboardingDecision.showFirstRun(onboardingDone = true,
            identityFileExists = false, identityPredatesProcess = false, appUpgraded = false))
        assertFalse(OnboardingDecision.showFirstRun(onboardingDone = true,
            identityFileExists = true, identityPredatesProcess = true, appUpgraded = true))
    }

    /** The reported bug: an upgrade alone must not skip setup for someone who never finished it. */
    @Test fun upgraderWithoutIdentityFileStillGetsFirstRun() {
        assertTrue(OnboardingDecision.showFirstRun(onboardingDone = false,
            identityFileExists = false, identityPredatesProcess = false, appUpgraded = true))
    }

    @Test fun identityFileFromBeforeProcessSkips() {
        assertFalse(OnboardingDecision.showFirstRun(onboardingDone = false,
            identityFileExists = true, identityPredatesProcess = true, appUpgraded = false))
    }

    @Test fun upgradedAppWithLegacyIdentityFileSkips() {
        assertFalse(OnboardingDecision.showFirstRun(onboardingDone = false,
            identityFileExists = true, identityPredatesProcess = false, appUpgraded = true))
    }

    /** An identity file created on this very launch is not evidence of an established install. */
    @Test fun sameLaunchIdentityFileOnFreshInstallDoesNotSkip() {
        assertTrue(OnboardingDecision.showFirstRun(onboardingDone = false,
            identityFileExists = true, identityPredatesProcess = false, appUpgraded = false))
    }
}
