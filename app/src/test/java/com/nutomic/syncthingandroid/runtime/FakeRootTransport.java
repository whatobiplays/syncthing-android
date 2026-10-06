package com.nutomic.syncthingandroid.runtime;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic in-memory stand-in for a rooted device's process table and root shell transport.
 *
 * <p>Tests drive the {@link Device} state directly to model exactly what recovery has to classify:
 * bundled candidates with or without matching durable evidence, live or exited launches,
 * unavailable or denied root authorization, and unreadable process metadata. Every shell the fake
 * factory hands out observes that one shared state, so no test needs a rooted device, a real
 * {@code su} binary, or timing-dependent waits.</p>
 *
 * <p>The fake parses the audited launch script the production encoder produced, which makes the
 * tests exercise the real launch protocol: the spawned entry carries the executable path and run
 * token the script actually transported.</p>
 */
final class FakeRootTransport {

    /** Single quote the audited launch script uses around every transported value. */
    private static final String QUOTE = "'";

    private FakeRootTransport() {
    }

    /** One simulated kernel process entry. */
    static final class Entry {
        final int pid;
        final CountDownLatch exited = new CountDownLatch(1);
        /** Executable the process runs, replaced in place when the launch script execs. */
        String executablePath;
        final long startTicks;
        /** Run token the process carries, set when the launch script execs. */
        String runToken;
        boolean alive = true;
        int exitCode = -1;

        Entry(int pid, String executablePath, long startTicks, String runToken) {
            this.pid = pid;
            this.executablePath = executablePath;
            this.startTicks = startTicks;
            this.runToken = runToken;
        }

        /** Models the kernel state where the executable link cannot be resolved. */
        boolean hasUnreadableExecutable() {
            return executablePath == null;
        }

        /** Replaces the process image, as the launch script's terminal exec does. */
        void exec(String executablePath, String runToken) {
            this.executablePath = executablePath;
            this.runToken = runToken;
        }

        void exit(int code) {
            exitCode = code;
            alive = false;
            exited.countDown();
        }
    }

    /** Simulated device state shared by every shell and factory used in one test. */
    static final class Device {
        /** Boot identifier reported for every shell in one test. */
        static final String BOOT_ID = "boot-a";
        /** Executable path used for pre-existing bundled processes on the simulated device. */
        static final String BUNDLED_PATH = "/data/app/lib/libsyncthingnative.so";

        final Map<Integer, Entry> processes = new LinkedHashMap<>();
        final List<String> signals = new ArrayList<>();
        final List<String> launchScripts = new ArrayList<>();

        /** When false, no root transport exists, as on an unrooted device. */
        boolean rootAvailable = true;
        /** When set, every activation reports this typed failure instead of a shell. */
        RootFailure activationFailure;
        /** When true, writing the launch script spawns the process it describes. */
        boolean spawnOnLaunch = true;
        /**
         * Invoked right after the launch shell became the bundled process and its durable pre-exec
         * evidence exists.
         *
         * <p>A test uses it to change device state that only exists after process creation, such as
         * removing the operation-scoped output file before the execution opens its tail.</p>
         */
        Runnable afterProcessSpawned;
        /** When true, writing the launch script spawns a bundled process with another token. */
        boolean spawnCompetingOnLaunch;
        /** When true, listing processes fails, as when a shell cannot read {@code /proc}. */
        boolean failProcessListing;
        /** When true, reading the boot identifier fails. */
        boolean failBootIdRead;
        /** When true, reading a run token fails. */
        boolean failRunTokenRead;
        /** When true, the kernel rejects every signal. */
        boolean rejectSignals;

        /** Command-like operations each shell ran, as {@code "<shell index>:<operation>"}. */
        final List<String> shellOperations = new ArrayList<>();

        int acquisitions;
        int shellCloses;
        /** Closes of shells that had already written a launch script. */
        int launchShellCloses;

        /**
         * When set, every activation announces that it started and then waits for
         * {@link #activationRelease}. A test uses this to hold a root preparation open while it
         * changes lifecycle state.
         */
        CountDownLatch activationPaused;
        CountDownLatch activationRelease;

        /**
         * When set, every liveness probe of a launch shell waits for this latch before answering.
         * A test uses it to hold the serve log pump in a known state, because the pump asks the
         * launch shell whether the process already exited.
         */
        CountDownLatch pauseLaunchShellLiveness;

        /** Executable path every simulated root shell runs before the terminal exec. */
        static final String ROOT_SHELL_PATH = "/system/bin/sh";

        /** Delivery phase a launch script reached before the transport reported failure. */
        enum DeliveryFailure {
            /** The transport delivered nothing usable and no evidence was written. */
            NONE,
            /** The shell accepted bytes but wrote no durable evidence. */
            BEFORE_EVIDENCE,
            /** The shell wrote its durable pre-exec evidence before the failure was reported. */
            AFTER_EVIDENCE,
            /** The shell had already replaced itself with the bundled binary. */
            AFTER_EXEC
        }

        /**
         * When true, {@code execTerminalScript} returns as soon as the shell accepted the script
         * bytes - exactly what libsu's terminal task does - and a test drives the evidence write and
         * the terminal exec through {@link #writeDeferredEvidence()} and
         * {@link #execDeferredLaunch()}.
         */
        boolean deferLaunchExecution;
        /** Reports a transport failure once delivery had reached this phase. */
        DeliveryFailure deliveryFailure = DeliveryFailure.NONE;
        /** Counts down when a launch accepted its script bytes. */
        CountDownLatch launchScriptAccepted;
        /** Counts down when a launch wrote its durable pre-exec evidence. */
        CountDownLatch launchEvidenceWritten;
        /** Counts down when a launch staged its complete pre-exec evidence. */
        CountDownLatch launchEvidenceStaged;
        /** Counts down when a launch executed the bundled binary. */
        CountDownLatch launchExecuted;
        /**
         * Lowest and highest acquisition index whose shell reads block on
         * {@link #gatedShellRelease}. A test uses it to hold one session's reads open while another
         * runtime reads the same device state. The gate is disabled while the limits are negative.
         */
        int gateShellsFrom = -1;
        /** Highest acquisition index whose shell reads block on {@link #gatedShellRelease}. */
        int gateShellsUpTo = -1;
        /**
         * When true, the gate applies only after a launch script was transported, so a test can
         * hold the reads that confirm a creation open without holding the ones that ran before
         * the script was delivered.
         */
        boolean gateOnlyAfterLaunch;
        /** Counts down when a gated shell reaches its first operation. */
        CountDownLatch gatedShellEntered;
        /** Releases the operations of every gated shell. */
        CountDownLatch gatedShellRelease;

        private DeferredLaunch deferredLaunch;
        /**
         * When set, only activations whose condition holds at the gate pause; every other
         * activation passes without waiting. A test uses this to pause one specific activation.
         */
        BooleanSupplier pauseWhen;

        private int nextPid = 4000;
        private long nextTicks = 9000;

        /** Adds a live bundled candidate, optionally carrying a run token. */
        Entry addBundledCandidate(String runToken) {
            return addProcess(BUNDLED_PATH, nextTicks++, runToken);
        }

        /** One accepted launch that has not executed its script yet. */
        private static final class DeferredLaunch {
            final Entry entry;
            final String executablePath;
            final String evidencePath;
            final String stagingPath;
            final String runToken;

            DeferredLaunch(
                    Entry entry,
                    String executablePath,
                    String evidencePath,
                    String stagingPath,
                    String runToken
            ) {
                this.entry = entry;
                this.executablePath = executablePath;
                this.evidencePath = evidencePath;
                this.stagingPath = stagingPath;
                this.runToken = runToken;
            }
        }

        /**
         * Accepts one launch script without executing it.
         *
         * <p>This models the production transport faithfully: libsu's terminal task returns once the
         * script bytes reached the shell's standard input, which is not proof that the shell has
         * executed them.</p>
         */
        Entry acceptDeferredLaunch(
                Entry entry,
                String executablePath,
                String evidencePath,
                String stagingPath,
                String runToken
        ) {
            deferredLaunch = new DeferredLaunch(
                    entry, executablePath, evidencePath, stagingPath, runToken
            );
            if (launchScriptAccepted != null) {
                launchScriptAccepted.countDown();
            }
            return entry;
        }

        /** Executes the accepted launch's durable pre-exec evidence block into its staging file. */
        void stageDeferredEvidence() {
            writePreExecEvidence(
                    deferredLaunch.stagingPath,
                    deferredLaunch.entry,
                    deferredLaunch.executablePath,
                    deferredLaunch.runToken
            );
            if (launchEvidenceStaged != null) {
                launchEvidenceStaged.countDown();
            }
        }

        /**
         * Executes the accepted launch's atomic replacement of the pending run evidence with its
         * staged file, exactly as the audited launch script's rename does.
         */
        void replaceDeferredEvidence() {
            replaceStagedEvidence(deferredLaunch.stagingPath, deferredLaunch.evidencePath);
            if (launchEvidenceWritten != null) {
                launchEvidenceWritten.countDown();
            }
        }

        /**
         * Executes the accepted launch's complete durable pre-exec evidence transition: the staged
         * write followed by its atomic replacement.
         */
        void writeDeferredEvidence() {
            stageDeferredEvidence();
            replaceDeferredEvidence();
        }

        /** Executes the accepted launch's terminal exec into the bundled binary. */
        void execDeferredLaunch() {
            deferredLaunch.entry.exec(deferredLaunch.executablePath, deferredLaunch.runToken);
            if (launchExecuted != null) {
                launchExecuted.countDown();
            }
        }

        /** Models the kernel terminating the accepted launch. */
        void exitDeferredLaunch(int exitCode) {
            exit(deferredLaunch.entry, exitCode);
        }

        /** Returns the staging file the accepted launch writes before its atomic replacement. */
        String deferredLaunchStagingPath() {
            if (deferredLaunch == null) {
                throw new AssertionError("No launch script was accepted yet");
            }
            return deferredLaunch.stagingPath;
        }

        /** Returns the live process of an accepted launch that has not executed its script yet. */
        Entry deferredLaunchEntry() {
            if (deferredLaunch == null) {
                throw new AssertionError("No launch script was accepted yet");
            }
            return deferredLaunch.entry;
        }

        /** Writes the operation-scoped output the accepted launch produced. */
        void writeDeferredOutput(String text) throws IOException {
            File output = new File(
                    new File(deferredLaunch.evidencePath).getParentFile(),
                    RootRunSpool.OUTPUT_FILE
            );
            java.nio.file.Files.write(
                    output.toPath(),
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
        }

        /** Adds a live process entry with explicit metadata. */
        Entry addProcess(String executablePath, long startTicks, String runToken) {
            Entry entry = new Entry(nextPid++, executablePath, startTicks, runToken);
            processes.put(entry.pid, entry);
            return entry;
        }

        /** Adds the live process a launch script spawned, using the next start tick. */
        Entry addLaunchedProcess(String executablePath, String runToken) {
            return addProcess(executablePath, nextTicks++, runToken);
        }

        /** Returns the only live bundled process, failing the test when that is not the state. */
        Entry onlyLiveProcess() {
            List<Entry> live = new ArrayList<>();
            for (Entry entry : processes.values()) {
                if (entry.alive) {
                    live.add(entry);
                }
            }
            if (live.size() != 1) {
                throw new AssertionError("Expected exactly one live process but found " + live.size());
            }
            return live.get(0);
        }

        /** Models the kernel terminating a process, for example after a verified signal. */
        void exit(Entry entry, int code) {
            entry.exit(code);
        }

        /**
         * Writes the durable pre-{@code exec} evidence exactly as the audited launch protocol
         * writes it, so a test can model an application death before any Java-side persistence.
         */
        void writePreExecEvidence(String evidencePath, Entry entry) {
            writePreExecEvidence(evidencePath, entry, entry.executablePath, entry.runToken);
        }

        /**
         * Writes durable pre-{@code exec} evidence for one launch.
         *
         * <p>The audited launch script records the executable it is about to {@code exec}, not the
         * shell that runs it, so the two differ while a launch is still in its handoff window.</p>
         */
        void writePreExecEvidence(
                String evidencePath,
                Entry entry,
                String executablePath,
                String runToken
        ) {
            String evidence = "version=1\n"
                    + "pid=" + entry.pid + "\n"
                    + "start_ticks=" + entry.startTicks + "\n"
                    + "boot_id=" + BOOT_ID + "\n"
                    + "exe=" + executablePath + "\n"
                    + "token=" + runToken + "\n";
            try {
                java.nio.file.Files.write(
                        new java.io.File(evidencePath).toPath(),
                        evidence.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                );
            } catch (IOException e) {
                throw new AssertionError("The simulated launch could not write evidence", e);
            }
        }

        /**
         * Atomically replaces the pending run evidence with a staged file, exactly as the audited
         * launch script's rename does.
         */
        void replaceStagedEvidence(String stagingPath, String evidencePath) {
            java.io.File staged = new java.io.File(stagingPath);
            if (!staged.renameTo(new java.io.File(evidencePath))) {
                throw new AssertionError("The simulated launch could not replace staged evidence");
            }
        }
    }

    /** Root shell bound to one {@link Device}, either helper-scoped or a launch shell. */
    static final class Shell implements RootShell {
        private final Device device;
        /** Acquisition index of this shell, used by the gated-shell test seam. */
        private final int index;
        private Entry launched;
        private boolean closed;
        private boolean ranLaunchScript;
        private Entry transport;

        Shell(Device device, int index) {
            this.device = Objects.requireNonNull(device);
            this.index = index;
        }

        /** Records one command-like operation this shell ran, for role-based test evidence. */
        private void recordOperation(String operation) {
            device.shellOperations.add(index + ":" + operation);
        }

        /** Blocks the reads of a shell a test deliberately holds open. */
        private void awaitGate() {
            if (device.gateShellsFrom < 0 || index < device.gateShellsFrom
                    || index > device.gateShellsUpTo || device.gatedShellRelease == null
                    || (device.gateOnlyAfterLaunch && device.launchScripts.isEmpty())) {
                return;
            }
            if (device.gatedShellEntered != null) {
                device.gatedShellEntered.countDown();
            }
            boolean released = false;
            while (!released) {
                try {
                    released = device.gatedShellRelease.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    // A real shell read is not interruptible; keep waiting like the transport does.
                }
            }
        }


        /** Returns the kernel process of this shell, which a launch script replaces by exec. */
        private Entry transportEntry() {
            if (transport == null) {
                transport = device.addProcess(Device.ROOT_SHELL_PATH, device.nextTicks++, null);
            }
            return transport;
        }

        @Override
        public int pid() throws IOException {
            requireOpen();
            recordOperation("pid");
            return transportEntry().pid;
        }

        @Override
        public List<ProcessEntry> listProcesses() throws IOException {
            requireOpen();
            recordOperation("listProcesses");
            awaitGate();
            if (device.failProcessListing) {
                throw new IOException("Simulated failure while listing processes");
            }
            List<ProcessEntry> entries = new ArrayList<>();
            for (Entry entry : device.processes.values()) {
                if (entry.alive) {
                    entries.add(new ProcessEntry(entry.pid, entry.executablePath, entry.startTicks));
                }
            }
            return entries;
        }

        @Override
        public String readRunToken(int pid) throws IOException {
            requireOpen();
            recordOperation("readRunToken");
            awaitGate();
            if (device.failRunTokenRead) {
                throw new IOException("Simulated failure while reading a run token");
            }
            Entry entry = device.processes.get(pid);
            return entry == null ? null : entry.runToken;
        }

        @Override
        public boolean sendSignal(int pid, int signal) throws IOException {
            requireOpen();
            recordOperation("sendSignal");
            if (device.rejectSignals) {
                return false;
            }
            device.signals.add(pid + ":" + signal);
            Entry entry = device.processes.get(pid);
            if (entry != null && entry.alive) {
                entry.exit(128 + signal);
            }
            return true;
        }

        @Override
        public void execTerminalScript(String script) throws IOException {
            requireOpen();
            recordOperation("execTerminalScript");
            ranLaunchScript = true;
            device.launchScripts.add(script);
            if (device.spawnCompetingOnLaunch) {
                device.addBundledCandidate("a-foreign-run-token");
            }
            if (device.deliveryFailure != Device.DeliveryFailure.NONE) {
                // The bytes reached the shell before the write or flush failed, so the launch may
                // have executed nothing, part of its script, or all of it.
                launched = device.acceptDeferredLaunch(
                        transportEntry(),
                        launchedExecutableOf(script),
                        evidencePathOf(script),
                        stagingPathOf(script),
                        runTokenOf(script)
                );
                if (device.deliveryFailure != Device.DeliveryFailure.BEFORE_EVIDENCE) {
                    device.writeDeferredEvidence();
                }
                if (device.deliveryFailure == Device.DeliveryFailure.AFTER_EXEC) {
                    device.execDeferredLaunch();
                }
                throw new IOException("Simulated failure while writing the root launch script");
            }
            if (device.deferLaunchExecution) {
                launched = device.acceptDeferredLaunch(
                        transportEntry(),
                        launchedExecutableOf(script),
                        evidencePathOf(script),
                        stagingPathOf(script),
                        runTokenOf(script)
                );
                return;
            }
            if (device.spawnOnLaunch) {
                // The launch shell becomes the bundled process by exec, so the process keeps the
                // kernel identity that the pre-delivery state recorded for it.
                launched = transportEntry();
                launched.exec(launchedExecutableOf(script), runTokenOf(script));
                device.writePreExecEvidence(stagingPathOf(script), launched);
                device.replaceStagedEvidence(stagingPathOf(script), evidencePathOf(script));
                Runnable afterProcessSpawned = device.afterProcessSpawned;
                if (afterProcessSpawned != null) {
                    afterProcessSpawned.run();
                }
            }
        }

        @Override
        public boolean hasExited() {
            CountDownLatch pause = device.pauseLaunchShellLiveness;
            if (pause != null) {
                try {
                    pause.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return launched != null && !launched.alive;
        }

        /** Binds this shell to an existing process entry, as a launch shell is bound to its launch. */
        void adopt(Entry entry) {
            this.launched = Objects.requireNonNull(entry);
        }

        @Override
        public int awaitExit() throws InterruptedException {
            if (launched == null) {
                throw new IllegalStateException("A helper shell has no launched process to await");
            }
            launched.exited.await();
            return launched.exitCode;
        }

        @Override
        public String readBootId() throws IOException {
            requireOpen();
            recordOperation("readBootId");
            if (device.failBootIdRead) {
                throw new IOException("Simulated failure while reading the boot identifier");
            }
            return Device.BOOT_ID;
        }

        @Override
        public void close() {
            closed = true;
            if (transport != null && transport.alive && launched == null) {
                // The shell never became the bundled process, so closing it terminates the process
                // this shell models as well. The process is released before the close is counted, so
                // a test that awaits the close count also observes the released process.
                device.processes.remove(transport.pid);
            }
            device.shellCloses++;
            if (ranLaunchScript) {
                device.launchShellCloses++;
            }
        }

        boolean isClosed() {
            return closed;
        }

        /** Rejects use after close, so a shell reused past its owner's lifetime fails loudly. */
        private void requireOpen() throws IOException {
            if (closed) {
                throw new IOException("Simulated use of a closed root shell");
            }
        }
    }

    /** Factory that hands out {@link Shell} instances without touching a real superuser transport. */
    static final class Factory implements RootShellFactory {
        private final Device device;
        private int acquireCalls;

        Factory(Device device) {
            this.device = Objects.requireNonNull(device);
        }

        @Override
        public RootShell acquire(long timeoutMillis) {
            acquireCalls++;
            device.acquisitions++;
            awaitTestGate();
            if (!device.rootAvailable) {
                throw new RootTransportException(
                        RootFailure.ROOT_UNAVAILABLE,
                        "Simulated absence of a root transport"
                );
            }
            if (device.activationFailure != null) {
                throw new RootTransportException(
                        device.activationFailure,
                        "Simulated typed activation failure"
                );
            }
            return new Shell(device, acquireCalls);
        }

        /** Holds one activation open until the test allows it to finish. */
        private void awaitTestGate() {
            if (device.activationPaused == null) {
                return;
            }
            if (device.pauseWhen != null && !device.pauseWhen.getAsBoolean()) {
                return;
            }
            device.activationPaused.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = device.activationRelease.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    // The real activation path is not interruptible while it waits for the shell, so
                    // a cancelled acquisition keeps waiting here exactly like the real one does.
                }
            }
        }

        int acquireCalls() {
            return acquireCalls;
        }
    }

    /** Extracts the executable path the audited launch script ends up executing. */
    static String launchedExecutableOf(String script) {
        String marker = "exec '";
        int start = script.lastIndexOf(marker);
        if (start < 0) {
            throw new AssertionError("The launch script carried no terminal exec");
        }
        int valueStart = start + marker.length();
        int end = script.indexOf(QUOTE, valueStart);
        if (end < 0) {
            throw new AssertionError("The launch script executable was not quoted");
        }
        return script.substring(valueStart, end);
    }

    /** Extracts the staging file the audited launch script fills before its atomic replacement. */
    static String stagingPathOf(String script) {
        String marker = "} > '";
        int start = script.lastIndexOf(marker);
        if (start < 0) {
            throw new AssertionError("The launch script carried no evidence redirect");
        }
        int valueStart = start + marker.length();
        int end = script.indexOf(QUOTE, valueStart);
        if (end < 0) {
            throw new AssertionError("The launch script staging path was not quoted");
        }
        return script.substring(valueStart, end);
    }

    /**
     * Extracts the evidence path the audited launch script atomically replaces with its staged
     * file before the terminal exec.
     */
    static String evidencePathOf(String script) {
        String marker = "mv -f '";
        int start = script.lastIndexOf(marker);
        if (start < 0) {
            throw new AssertionError("The launch script carried no evidence replacement");
        }
        int stagingEnd = script.indexOf(QUOTE, start + marker.length());
        if (stagingEnd < 0) {
            throw new AssertionError("The launch script staging path was not quoted");
        }
        int evidenceStart = script.indexOf(QUOTE, stagingEnd + 1);
        if (evidenceStart < 0) {
            throw new AssertionError("The launch script evidence path was not quoted");
        }
        int evidenceEnd = script.indexOf(QUOTE, evidenceStart + 1);
        if (evidenceEnd < 0) {
            throw new AssertionError("The launch script evidence path was not quoted");
        }
        return script.substring(evidenceStart + 1, evidenceEnd);
    }

    /** Extracts the private run token the audited launch script exports. */
    static String runTokenOf(String script) {
        String marker = "export " + LibsuRootShell.RUN_TOKEN_ENVIRONMENT + "='";
        int start = script.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("The launch script carried no run token");
        }
        int valueStart = start + marker.length();
        int end = script.indexOf(QUOTE, valueStart);
        if (end < 0) {
            throw new AssertionError("The launch script run token was not quoted");
        }
        return script.substring(valueStart, end);
    }
}
