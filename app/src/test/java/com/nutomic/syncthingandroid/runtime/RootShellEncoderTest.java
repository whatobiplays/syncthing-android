package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class RootShellEncoderTest {
    private static final String TOKEN = "4f6a1c1e-1c1e-4f6a-9c1e-7c1e4f6a1c1e";
    private static final String EVIDENCE = "/data/user/0/app/no_backup/superuser-runtime/runs/"
            + TOKEN + "/evidence";
    private static final String STAGING = "/data/user/0/app/no_backup/superuser-runtime/runs/"
            + TOKEN + "/evidence.staging";
    private static final String OUTPUT = "/data/user/0/app/no_backup/superuser-runtime/runs/"
            + TOKEN + "/output";

    @Test
    public void exportsTheSharedStructuredEnvironment() {
        String script = launchScript();

        assertTrue(script.contains("export HOME='/data/user/0/app/files'\n"));
        assertTrue(script.contains("export STNOUPGRADE='1'\n"));
        assertTrue(script.contains(
                "export " + LibsuRootShell.RUN_TOKEN_ENVIRONMENT + "='" + TOKEN + "'\n"));
    }

    @Test
    public void refusesToLaunchUnlessTheShellRunsAsUidZero() {
        String script = launchScript();

        assertTrue(script.contains(
                "[ \"$(id -u)\" = \"0\" ] || exit "
                        + RootShellEncoder.UID_GUARD_EXIT_CODE + "\n"));
        assertTrue(script.indexOf("id -u") < script.indexOf("exec "));
    }

    @Test
    public void usesExactlyOneTerminalExecWithoutMountMaster() {
        String script = launchScript();

        assertEquals(1, countOccurrences(script, "\nexec "));
        assertTrue(script.endsWith(" > '" + OUTPUT + "' 2>&1\n"));
        assertFalse(script.contains("--mount-master"));
        assertFalse(script.contains("sh -c"));
    }

    @Test
    public void writesVersionedEvidenceBeforeTheExec() {
        String script = launchScript();

        int evidence = script.indexOf("version=1");
        int exec = script.indexOf("exec ");

        assertTrue(evidence > 0);
        assertTrue(evidence < exec);
        assertTrue(script.contains("echo \"pid=$standroid_pid\"\n"));
        assertTrue(script.contains("echo \"start_ticks=$standroid_ticks\"\n"));
        assertTrue(script.contains("echo \"boot_id=$standroid_boot_id\"\n"));
        assertTrue(script.contains("} > '" + STAGING + "' || exit "
                + RootShellEncoder.EVIDENCE_WRITE_EXIT_CODE + "\n"));
        assertTrue("the staged evidence atomically replaces the pending record", script.contains(
                "mv -f '" + STAGING + "' '" + EVIDENCE + "' || exit "
                        + RootShellEncoder.EVIDENCE_WRITE_EXIT_CODE + "\n"));
        assertTrue("the rename completes before the terminal exec",
                script.indexOf("mv -f '") < script.indexOf("exec '"));
        assertFalse(
                "the durable pending record is never truncated in place",
                script.contains("> '" + EVIDENCE + "'")
        );
        assertNotEquals(
                "the staging file is a distinct app-owned file",
                EVIDENCE,
                STAGING
        );
    }

    @Test
    public void singleQuotesEveryArgument() {
        String script = RootShellEncoder.launchScript(
                new String[] {"/data/app/lib/libsyncthingnative.so", "serve", "--no-browser"},
                environment(),
                EVIDENCE,
                STAGING,
                OUTPUT
        );

        assertTrue(script.contains(
                "exec '/data/app/lib/libsyncthingnative.so' 'serve' '--no-browser' > '"
                        + OUTPUT + "' 2>&1\n"));
    }

    @Test
    public void escapesSingleQuotesInsideValues() {
        assertEquals("'a'\\''b'", RootShellEncoder.quote("a'b"));

        Map<String, String> values = environment();
        values.put("STTRACE", "all'; touch /data/local/tmp/pwned; echo '");
        String script = RootShellEncoder.launchScript(
                new String[] {"/data/app/lib/libsyncthingnative.so", "serve"},
                values,
                EVIDENCE,
                STAGING,
                OUTPUT
        );

        assertTrue(script.contains(
                "export STTRACE='all'\\''; touch /data/local/tmp/pwned; echo '\\'''\n"));
        assertEquals(1, countOccurrences(script, "\nexec "));
    }

    @Test
    public void requiresThePrivateRunToken() {
        Map<String, String> values = environment();
        values.remove(LibsuRootShell.RUN_TOKEN_ENVIRONMENT);

        try {
            RootShellEncoder.launchScript(
                    new String[] {"/data/app/lib/libsyncthingnative.so", "serve"},
                    values,
                    EVIDENCE,
                    STAGING,
                    OUTPUT
            );
            fail("Expected the missing run token to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("run token"));
        }
    }

    @Test
    public void rejectsAnEmptyCommandLine() {
        try {
            RootShellEncoder.launchScript(new String[0], environment(), EVIDENCE, STAGING, OUTPUT);
            fail("Expected the empty command line to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("binary"));
        }
    }

    @Test
    public void rejectsUnsupportedEnvironmentNames() {
        Map<String, String> values = environment();
        values.put("BAD NAME; rm -rf /", "value");

        try {
            RootShellEncoder.launchScript(
                    new String[] {"/data/app/lib/libsyncthingnative.so", "serve"},
                    values,
                    EVIDENCE,
                    STAGING,
                    OUTPUT
            );
            fail("Expected the unsupported environment name to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("environment name"));
        }
    }

    @Test
    public void hostileEnvironmentVariablesAreInstalledOnlyAfterTheProtocol() {
        Map<String, String> values = environment();
        values.put("PATH", "/data/local/tmp:/system/bin");
        values.put("LD_PRELOAD", "/data/local/tmp/libhook.so");
        values.put("STTRACE", "all'; id -u; echo '");

        String script = RootShellEncoder.launchScript(
                new String[]{"/data/app/lib/libsyncthingnative.so", "serve", "--no-browser"},
                values,
                EVIDENCE,
                STAGING,
                OUTPUT
        );

        int firstExport = script.indexOf("\nexport ");
        int exec = script.indexOf("\nexec ");
        assertTrue("the environment has to be installed at all", firstExport > 0);
        assertTrue(
                "the UID guard runs under the root shell's own environment",
                script.indexOf("\"$(id -u)\"") < firstExport
        );
        assertTrue(
                "the durable evidence protocol completes before any export",
                script.indexOf("mv -f '") < firstExport
        );
        assertTrue("the environment is installed right before the exec", firstExport < exec);
        for (String line : script.substring(firstExport + 1, exec).split("\n", -1)) {
            assertTrue(
                    "no protocol command may run under the custom environment: " + line,
                    line.startsWith("export ")
            );
        }

        assertTrue("PATH parities Normal Mode and Superuser Mode",
                script.contains("export PATH='/data/local/tmp:/system/bin'\n"));
        assertTrue("LD_PRELOAD parities Normal Mode and Superuser Mode",
                script.contains("export LD_PRELOAD='/data/local/tmp/libhook.so'\n"));
        assertTrue("the run token still reaches the launched process",
                script.contains("export " + LibsuRootShell.RUN_TOKEN_ENVIRONMENT
                        + "='" + TOKEN + "'\n"));
        assertTrue(
                "value quoting still escapes single quotes",
                script.contains("export STTRACE='all'\\''; id -u; echo '\\'''\n")
        );
        assertTrue(
                "the terminal executable stays the fixed absolute bundled path",
                script.contains("\nexec '/data/app/lib/libsyncthingnative.so' 'serve' '--no-browser'"
                        + " > '" + OUTPUT + "' 2>&1\n")
        );
        assertEquals(1, countOccurrences(script, "\nexec "));
    }

    private static String launchScript() {
        return RootShellEncoder.launchScript(
                new String[] {"/data/app/lib/libsyncthingnative.so", "serve"},
                environment(),
                EVIDENCE,
                STAGING,
                OUTPUT
        );
    }

    private static Map<String, String> environment() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("HOME", "/data/user/0/app/files");
        values.put("STNOUPGRADE", "1");
        values.put(LibsuRootShell.RUN_TOKEN_ENVIRONMENT, TOKEN);
        return values;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
