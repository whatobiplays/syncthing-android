package com.nutomic.syncthingandroid.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Encodes the single audited root launch script used by the superuser backend.
 *
 * <p>This class is the only place that converts an approved bundled Syncthing invocation into
 * shell text. The produced script:</p>
 *
 * <ol>
 * <li>exports the shared structured environment, plus the private run token;</li>
 * <li>refuses to continue unless the shell really runs as UID 0;</li>
 * <li>writes the durable execution evidence that recovery depends on, using the PID that the
 * shell keeps while it replaces itself with the bundled binary, into an app-owned staging file
 * and renames that file over the pre-delivery run evidence, so the durable record changes from
 * pending state to complete pre-exec evidence atomically;</li>
 * <li>finally {@code exec}s the bundled binary with its standard output and standard error
 * redirected into the app-owned per-run spool.</li>
 * </ol>
 *
 * <p>Environment values, executable paths, and arguments are single-quoted, so no
 * caller-controlled text can add a second command. The script must stay a single terminal
 * {@code exec} with no trailing command, because libsu's job framing assumes the process keeps
 * behaving like a shell.</p>
 */
final class RootShellEncoder {
    /** Exit status used when the shell refuses to launch because it is not UID 0. */
    static final int UID_GUARD_EXIT_CODE = 90;
    /** Exit status used when the durable execution evidence could not be written. */
    static final int EVIDENCE_WRITE_EXIT_CODE = 91;
    /** Exit status used when the shell cannot read its own execution evidence. */
    static final int EVIDENCE_READ_EXIT_CODE = 92;

    private static final String EVIDENCE_HEADER = "version=1";
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private RootShellEncoder() {
    }

    /**
     * Builds the audited launch script.
     *
     * @param argv approved command line whose element 0 is the bundled binary
     * @param environment structured environment shared with Normal Mode execution
     * @param evidencePath app-private file that ends up carrying the durable execution evidence
     * @param stagingPath app-private file the evidence is written into before its atomic rename
     * @param outputPath app-private per-run spool that receives standard output and error
     */
    static String launchScript(
            String[] argv,
            Map<String, String> environment,
            String evidencePath,
            String stagingPath,
            String outputPath
    ) {
        Objects.requireNonNull(argv);
        Objects.requireNonNull(environment);
        Objects.requireNonNull(evidencePath);
        Objects.requireNonNull(stagingPath);
        Objects.requireNonNull(outputPath);
        if (argv.length == 0) {
            throw new IllegalArgumentException("The approved command line must name a binary");
        }
        String token = environment.get(LibsuRootShell.RUN_TOKEN_ENVIRONMENT);
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("The approved environment needs the private run token");
        }

        StringBuilder script = new StringBuilder();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            requireEnvironmentName(entry.getKey());
            script.append("export ")
                    .append(entry.getKey())
                    .append('=')
                    .append(quote(entry.getValue()))
                    .append('\n');
        }
        script.append("[ \"$(id -u)\" = \"0\" ] || exit ")
                .append(UID_GUARD_EXIT_CODE)
                .append('\n');
        script.append("standroid_pid=$$\n");
        script.append("standroid_boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\n");
        script.append("standroid_stat=$(cat /proc/$$/stat 2>/dev/null)\n");
        script.append("standroid_rest=${standroid_stat##*)}\n");
        script.append("set -f\n");
        script.append("set -- $standroid_rest\n");
        script.append("standroid_ticks=${20}\n");
        script.append("standroid_stat=\n");
        script.append("standroid_rest=\n");
        script.append("[ -n \"$standroid_pid\" ] && [ -n \"$standroid_boot_id\" ]")
                .append(" && [ -n \"$standroid_ticks\" ] || exit ")
                .append(EVIDENCE_READ_EXIT_CODE)
                .append('\n');
        script.append("{\n");
        script.append("echo ").append(quote(EVIDENCE_HEADER)).append('\n');
        script.append("echo \"pid=$standroid_pid\"\n");
        script.append("echo \"start_ticks=$standroid_ticks\"\n");
        script.append("echo \"boot_id=$standroid_boot_id\"\n");
        script.append("echo ").append(quote("exe=" + argv[0])).append('\n');
        script.append("echo ").append(quote("token=" + token)).append('\n');
        script.append("} > ").append(quote(stagingPath))
                .append(" || exit ")
                .append(EVIDENCE_WRITE_EXIT_CODE)
                .append('\n');
        script.append("mv -f ")
                .append(quote(stagingPath))
                .append(' ')
                .append(quote(evidencePath))
                .append(" || exit ")
                .append(EVIDENCE_WRITE_EXIT_CODE)
                .append('\n');
        script.append("exec ").append(quote(argv[0]));
        for (int index = 1; index < argv.length; index++) {
            script.append(' ').append(quote(argv[index]));
        }
        script.append(" > ").append(quote(outputPath)).append(" 2>&1\n");
        return script.toString();
    }

    /** Single-quotes one value so it cannot terminate its argument or start a new command. */
    static String quote(String value) {
        Objects.requireNonNull(value);
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void requireEnvironmentName(String name) {
        if (name == null || !ENVIRONMENT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Unsupported environment name: " + name);
        }
    }
}
