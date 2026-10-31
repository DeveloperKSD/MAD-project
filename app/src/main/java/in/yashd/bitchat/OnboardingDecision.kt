package `in`.yashd.bitchat

/**
 * First-run gating as a pure decision so it is unit-testable: durable identity evidence
 * decides, never install timestamps alone.
 */
object OnboardingDecision {
    /**
     * An established install has a non-empty identity file that either predates this process
     * or belongs to an upgraded app (whose legacy identity may have been rewritten on this
     * very launch). An upgrader who never finished setup has no file and is not existing.
     */
    fun isExistingInstall(identityFileExists: Boolean, identityPredatesProcess: Boolean, appUpgraded: Boolean): Boolean =
        identityFileExists && (identityPredatesProcess || appUpgraded)

    fun showFirstRun(onboardingDone: Boolean, identityFileExists: Boolean, identityPredatesProcess: Boolean, appUpgraded: Boolean): Boolean =
        !onboardingDone && !isExistingInstall(identityFileExists, identityPredatesProcess, appUpgraded)
}
