"""Regression tests for the production Superuser Mode source boundary."""

import tempfile
import unittest
from pathlib import Path

from scripts.check_superuser_architecture import check_repository


class ArchitectureGuardrailTest(unittest.TestCase):
    """Exercise the checker against complete temporary production trees."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def source(self, relative_path, contents):
        target = self.root / "app/src/main/java/com/nutomic/syncthingandroid" / relative_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(contents, encoding="utf-8")

    def gradle(self, contents):
        target = self.root / "gradle/libs.versions.toml"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(contents, encoding="utf-8")

    def assert_violation(self, expected):
        self.assertIn(expected, "\n".join(check_repository(self.root)))

    def test_comments_strings_and_tests_do_not_trigger_rules(self):
        self.source("ordinary/Benign.kt", '''
            // Shell.getShell() and RootService are historical examples.
            val explanation = "pkill syncthing; Shell.getShell(); RootService"
            fun show() = explanation
        ''')
        test = self.root / "app/src/test/java/UnsafeExample.java"
        test.parent.mkdir(parents=True)
        test.write_text("Shell.getShell();", encoding="utf-8")
        self.assertEqual([], check_repository(self.root))

    def test_new_file_cannot_import_libsu_or_use_global_shell(self):
        self.source("new/RootShortcut.java", '''
            import com.topjohnwu.superuser.Shell;
            class RootShortcut { void run() { Shell.getShell(); } }
        ''')
        self.assert_violation("libsu import")
        self.assert_violation("global libsu shell")

    def test_new_package_outside_primary_namespace_is_scanned(self):
        target = self.root / "app/src/main/java/org/example/Shortcut.java"
        target.parent.mkdir(parents=True)
        target.write_text('''
            import com.topjohnwu.superuser.Shell;
            class Shortcut { void use() { Shell.getShell(); } }
        ''', encoding="utf-8")
        self.assert_violation("libsu import")

    def test_aidl_outside_java_tree_is_rejected(self):
        target = self.root / "app/src/main/aidl/org/example/IRootCommand.aidl"
        target.parent.mkdir(parents=True)
        target.write_text("interface IRootCommand {}", encoding="utf-8")
        self.assert_violation("RootService/AIDL")

    def test_multiline_java_import_fails(self):
        self.source("new/Shortcut.java", '''
            import com.topjohnwu
                .superuser.Shell;
            class Shortcut { Shell shell; }
        ''')
        self.assert_violation("libsu import")

    def test_static_global_route_fails_inside_allowed_transport(self):
        self.source("runtime/LibsuRootShell.java", '''
            import static com.topjohnwu.superuser.Shell.getShell;
            class LibsuRootShell { void use() { getShell(); } }
        ''')
        self.assert_violation("global libsu shell")

    def test_new_file_cannot_add_rootservice_or_generic_privileged_api(self):
        self.source("new/RootService.java", '''
            class RootService { public void executeRootCommand(String command) {} }
        ''')
        self.assert_violation("RootService/AIDL")
        self.assert_violation("generic privileged API")

    def test_arbitrary_path_api_in_new_privileged_file_is_rejected(self):
        self.source("runtime/PrivilegedFiles.java", '''
            class PrivilegedFiles { public byte[] read(String arbitraryPath) { return null; } }
        ''')
        self.assert_violation("generic privileged API")

    def test_new_file_cannot_write_mode_or_launch_bundled_binary(self):
        self.source("new/Shortcut.java", '''
            class Shortcut {
                void change(SharedPreferences preferences) {
                    preferences.edit().putBoolean(Constants.PREF_USE_ROOT, true).commit();
                }
                void launch(Context context) throws Exception {
                    new ProcessBuilder(Constants.getSyncthingBinary(context).getPath()).start();
                }
            }
        ''')
        self.assert_violation("mode preference writer")
        self.assert_violation("process creation")
        self.assert_violation("bundled binary access")

    def test_process_name_signal_in_execution_argument_is_detected(self):
        self.source("new/Shortcut.kt", '''
            fun stop() { ProcessBuilder("sh", "-c", "pkill syncthing").start() }
        ''')
        self.assert_violation("process-name signal")

    def test_process_name_signal_using_kill_and_process_lookup_is_detected(self):
        self.source("new/Shortcut.java", '''
            class Shortcut {
                void stop() { new ProcessBuilder("sh", "-c", "kill -9 $(ps | grep syncthing)").start(); }
            }
        ''')
        self.assert_violation("process-name signal")

    def test_benign_collection_string_does_not_count_as_shell_execution(self):
        self.source("ordinary/Labels.java", '''
            class Labels { void show(List<String> labels) { labels.add("pkill example"); } }
        ''')
        self.assertEqual([], check_repository(self.root))

    def test_process_name_signal_in_transport_script_is_detected(self):
        self.source("runtime/RootShellEncoder.java", '''
            class RootShellEncoder { String build() { return "pkill syncthing"; } }
        ''')
        self.assert_violation("process-name signal")

    def test_gradle_rejects_libsu_service_and_nio(self):
        self.gradle('''
            libsu-core = { module = "com.github.topjohnwu.libsu:core", version.ref = "libsu" }
            libsu-service = { module = "com.github.topjohnwu.libsu:service", version.ref = "libsu" }
        ''')
        self.assert_violation("libsu service/nio dependency")

    def test_new_root_shell_operation_and_mode_neutral_branch_fail(self):
        self.source("runtime/RootShell.java", '''
            interface RootShell { void execute(String command); }
        ''')
        self.source("runtime/NewRuntime.kt", '''
            fun choose() = preferences.getBoolean(Constants.PREF_USE_ROOT, false)
        ''')
        self.assert_violation("new RootShell operation")
        self.assert_violation("mode branching inside mode-neutral runtime")

    def test_app_cannot_select_backend_or_expose_unrestricted_root_command(self):
        self.source("settings/Shortcut.kt", '''
            fun onClick() { runtime.selectExecutionMode(true) }
        ''')
        self.source("runtime/RootShortcut.java", '''
            class RootShortcut { public void execute(String command) {} }
        ''')
        self.assert_violation("backend selection outside service authority")
        self.assert_violation("generic privileged API")

    def test_approved_import_order_and_transport_are_allowed(self):
        self.source("service/SyncthingService.java", '''
            class SyncthingService {
                boolean importConfig() {
                    try { SyncthingBackupArchive.extract(zipFile, staging); }
                    catch (Exception e) { return false; }
                    shutdownForFileMutation();
                    mRuntime.managedStateTransfer().installImportedState(staging);
                    return true;
                }
            }
        ''')
        self.source("runtime/LibsuRootShell.java", '''
            import com.topjohnwu.superuser.Shell;
            class LibsuRootShell { void use(Shell shell) { shell.newJob().add("id"); } }
        ''')
        self.assertEqual([], check_repository(self.root))

    def test_malformed_import_cannot_reach_live_mutation_before_validation(self):
        self.source("service/SyncthingService.java", '''
            class SyncthingService {
                public boolean importConfig() {
                    mRuntime.managedStateTransfer().installImportedState(staging);
                    SyncthingBackupArchive.extract(zipFile, staging);
                    return true;
                }
            }
        ''')
        self.assert_violation("import validation must precede live mutation")

    def test_import_preferences_must_keep_device_local_filter(self):
        self.source("service/SyncthingService.java", '''
            class SyncthingService {
                boolean importConfigSharedPrefs(Map<?, ?> archived) {
                    for (Map.Entry<?, ?> entry : archived.entrySet()) {
                        editor.putBoolean((String) entry.getKey(), (Boolean) entry.getValue());
                    }
                    return editor.commit();
                }
            }
        ''')
        self.assert_violation("import must filter and restore device-local preferences")

    def test_second_mode_writer_inside_allowed_class_is_rejected(self):
        self.source("service/ExecutionModePreferenceCommit.java", '''
            class ExecutionModePreferenceCommit {
                void commit() {
                    editor.putBoolean(Constants.PREF_USE_ROOT, true);
                    editor.putBoolean(Constants.PREF_USE_ROOT, false);
                    editor.remove(Constants.PREF_USE_ROOT);
                }
                void bypass() { editor.putBoolean(Constants.PREF_USE_ROOT, true); }
            }
        ''')
        self.assert_violation("mode preference write outside commit operation")

    def test_qualified_mode_key_write_is_rejected(self):
        self.source("new/Shortcut.java", '''
            class Shortcut {
                void change(SharedPreferences prefs) {
                    prefs.edit().putBoolean(
                        com.nutomic.syncthingandroid.service.Constants.PREF_USE_ROOT, true
                    ).commit();
                }
            }
        ''')
        self.assert_violation("mode preference writer")

    def test_unapproved_libsu_artifact_fails_even_if_not_service_or_nio(self):
        self.gradle('''
            libsu-extra = { module = "com.github.topjohnwu.libsu:extra", version.ref = "libsu" }
        ''')
        self.assert_violation("libsu core-only dependency")

    def test_aliased_mode_preference_write_in_new_class_fails(self):
        self.source("util/ModeShortcut.java", '''
            class ModeShortcut {
                void change(SharedPreferences preferences) {
                    String key = Constants.PREF_USE_ROOT;
                    preferences.edit().putBoolean(key, true).commit();
                }
            }
        ''')
        self.assert_violation("mode preference writer")

    def test_semicolon_free_kotlin_mode_key_alias_fails(self):
        self.source("settings/SettingsBehaviorScreen.kt", '''
            class SettingsBehaviorScreen {
                fun bypass(preferences: SharedPreferences) {
                    val key = Constants.PREF_USE_ROOT
                    preferences.edit().putBoolean(key, true).commit()
                }
            }
        ''')
        self.assert_violation("mode preference writer")

    def test_approved_import_restoration_remains_allowed(self):
        self.source("service/SyncthingService.java", '''
            class SyncthingService {
                boolean restoreLocalExecutionModePreference() {
                    return putPreferenceValue(editor, Constants.PREF_USE_ROOT, value);
                }
            }
        ''')
        self.assertEqual([], check_repository(self.root))

    def test_root_shell_overload_with_arbitrary_path_fails(self):
        self.source("runtime/RootShell.java", '''
            interface RootShell {
                byte[] readStateFile(ManagedStateMember member) throws IOException;
                byte[] readStateFile(String arbitraryPath) throws IOException;
            }
        ''')
        self.assert_violation("new RootShell operation")

    def test_new_raw_terminal_script_caller_fails(self):
        self.source("runtime/RootShortcut.java", '''
            class RootShortcut {
                void launch(RootShell shell, String script) throws IOException {
                    shell.execTerminalScript(script);
                }
            }
        ''')
        self.assert_violation("terminal script outside audited launch path")

    def test_hard_coded_syncthing_process_in_approved_helper_fails(self):
        self.source("util/Util.java", '''
            class Util {
                void start() throws IOException {
                    new ProcessBuilder("/data/app/libsyncthingnative.so").start();
                }
            }
        ''')
        self.assert_violation("bundled binary invocation outside selected backend")

    def test_assigned_process_name_termination_command_fails(self):
        self.source("util/Util.java", '''
            class Util {
                void stop() throws IOException {
                    String command = "pkill syncthing";
                    Runtime.getRuntime().exec(command);
                }
            }
        ''')
        self.assert_violation("process-name signal")

    def test_new_mode_neutral_utility_cannot_branch_on_preference(self):
        self.source("util/ModeShortcut.java", '''
            class ModeShortcut {
                void choose(SharedPreferences preferences) {
                    if (preferences.getBoolean(Constants.PREF_USE_ROOT, false)) {
                        useRoot();
                    } else {
                        useAppUid();
                    }
                }
            }
        ''')
        self.assert_violation("mode branching after backend selection")

    def test_authorized_mode_display_remains_allowed(self):
        self.source("settings/SettingsBehaviorScreen.kt", '''
            val selected = rememberPreferenceState(Constants.PREF_USE_ROOT, false)
        ''')
        self.assertEqual([], check_repository(self.root))

    def test_approved_root_shell_contract_and_terminal_delegation_remain_allowed(self):
        self.source("runtime/RootShell.java", '''
            interface RootShell {
                byte[] readStateFile(ManagedStateMember member) throws IOException;
                void execTerminalScript(String script) throws IOException;
            }
        ''')
        self.source("runtime/SessionOwnedRootShell.java", '''
            class SessionOwnedRootShell {
                void execTerminalScript(String script) throws IOException {
                    sessionShell.execTerminalScript(script);
                }
            }
        ''')
        self.assertEqual([], check_repository(self.root))

    def test_benign_assigned_command_does_not_trigger_signal_rule(self):
        self.source("util/Util.java", '''
            class Util {
                void show() throws IOException {
                    String command = "sh";
                    Runtime.getRuntime().exec(command);
                }
            }
        ''')
        self.assertEqual([], check_repository(self.root))

    def test_import_catch_must_abort_before_live_mutation(self):
        self.source("service/SyncthingService.java", '''
            class SyncthingService {
                boolean importConfig() {
                    try { SyncthingBackupArchive.extract(zipFile, staging); }
                    catch (Exception e) { log(e); }
                    shutdownForFileMutation();
                    mRuntime.managedStateTransfer().installImportedState(staging);
                    return true;
                }
            }
        ''')
        self.assert_violation("import validation failure must abort before live mutation")

    def test_conditional_import_catch_return_cannot_authorize_live_mutation(self):
        self.source("service/SyncthingService.java", '''
            class SyncthingService {
                boolean importConfig() {
                    try { SyncthingBackupArchive.extract(zipFile, staging); }
                    catch (Exception e) { if (shouldAbort) return false; }
                    shutdownForFileMutation();
                    mRuntime.managedStateTransfer().installImportedState(staging);
                    return true;
                }
            }
        ''')
        self.assert_violation("import validation failure must abort before live mutation")

    def test_implicit_public_kotlin_privileged_method_fails(self):
        self.source("new/RootShortcut.kt", '''
            class RootShortcut {
                fun executeRootCommand(command: String) { dispatch(command) }
            }
        ''')
        self.assert_violation("generic privileged API")

    def test_kotlin_semantic_operation_remains_allowed(self):
        self.source("new/FolderProbe.kt", '''
            class FolderProbe {
                fun probeFolderWriteability(folder: String): Boolean = true
            }
        ''')
        self.assertEqual([], check_repository(self.root))

    def test_root_shell_default_method_outside_contract_fails(self):
        self.source("runtime/RootShell.java", '''
            interface RootShell {
                byte[] readStateFile(ManagedStateMember member) throws IOException;
                default void execute(String command) { return; }
            }
        ''')
        self.assert_violation("new RootShell operation")

    def test_second_import_restoration_writer_fails(self):
        self.source("service/SyncthingService.java", '''
            class SyncthingService {
                boolean restoreLocalExecutionModePreference() {
                    return putPreferenceValue(editor, Constants.PREF_USE_ROOT, value);
                }
                boolean bypass() {
                    return putPreferenceValue(editor, Constants.PREF_USE_ROOT, false);
                }
            }
        ''')
        self.assert_violation("mode preference writer outside import restoration")


if __name__ == "__main__":
    unittest.main()
