package com.nutomic.syncthingandroid.onboarding;

/** Applies the onboarding rules that protect an existing Syncthing identity from replacement. */
public final class InstallationRecognition {

    private InstallationRecognition() {
    }

    /**
     * Reports whether onboarding must skip first-run key generation.
     *
     * <p>A durable app marker or selected Superuser Mode identifies a prior installation even
     * when app-UID configuration access is unavailable. An existing configuration path also
     * blocks replacement when its contents cannot currently be read.</p>
     */
    public static boolean shouldSkipKeyGeneration(
            boolean installationMarkerPresent,
            boolean superuserModeSelected,
            boolean configurationExists
    ) {
        return installationMarkerPresent || superuserModeSelected || configurationExists;
    }

    /** Reports when the installation marker can be recorded without inferring from readability. */
    public static boolean shouldRecordMarker(
            boolean superuserModeSelected,
            boolean configurationParseable
    ) {
        return superuserModeSelected || configurationParseable;
    }

    /**
     * Reports whether Normal Mode is unsafe until Managed State access is repaired.
     *
     * <p>An inaccessible path is expected on a fresh installation before Syncthing generates its
     * configuration. Repair is required only after there is evidence of an existing installation.
     * Root-selected state is intentionally outside this application-UID check.</p>
     */
    public static boolean shouldRequireStateRepair(
            boolean applicationAccessAvailable,
            boolean installationMarkerPresent,
            boolean superuserModeSelected,
            boolean configurationExists
    ) {
        return !applicationAccessAvailable
                && !superuserModeSelected
                && shouldSkipKeyGeneration(
                        installationMarkerPresent,
                        false,
                        configurationExists
                );
    }
}
