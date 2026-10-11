#!/usr/bin/env python3
"""Check the production source boundaries of Syncthing Android's Superuser Mode.

The checks intentionally cover identifiable source structure. Runtime tests prove that an
admitted execution is actually owned, that signals target that execution, and that transitions
and root scripts preserve admission ordering. This checker does not infer those behaviors from
text patterns.
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path


SOURCE_ROOT = Path("app/src/main/java/com/nutomic/syncthingandroid")
APP_MAIN = Path("app/src/main")
LIBSU_TRANSPORT = {
    Path("runtime/LibsuRootShell.java"),
    Path("runtime/LibsuRootShellFactory.java"),
}
PROCESS_CREATORS = {
    Path("runtime/AppUidBackend.java"),
    Path("runtime/AppUidFolderOperations.java"),
    Path("runtime/LibsuRootShellFactory.java"),
    Path("util/Util.java"),
}
BINARY_OWNERS = {
    Path("service/Constants.java"),
    Path("runtime/AppUidBackend.java"),
    Path("runtime/RootBackend.java"),
}
ROOT_SHELL_SIGNATURES = {
    ("listProcesses", ()), ("readRunToken", ("int",)), ("pid", ()),
    ("sendSignal", ("int", "int")), ("execTerminalScript", ("String",)),
    ("hasExited", ()), ("awaitExit", ()),
    ("exitStatusBelongsToLaunchedProcess", ()), ("readBootId", ()),
    ("readStateFile", ("ManagedStateMember",)),
    ("writeStateFile", ("ManagedStateMember", "byte[]")),
    ("removeStateMember", ("ManagedStateMember",)),
    ("stateMemberExists", ("ManagedStateMember",)),
    ("stageManagedStateForApp", ("String",)),
    ("removeStagingDirectory", ("String",)),
    ("installStagedManagedState", ("String",)),
    ("repairManagedStateAccess", ()),
    ("probeFolderWriteability", ("String",)),
    ("discoverConflictFiles", ("String",)),
    ("readFolderIgnoreList", ("String",)),
    ("writeFolderIgnoreList", ("String", "byte[]")),
    ("runFolderScriptSet", ("String", "String")),
    ("applyIoPriority", ("ExecutionIdentity",)),
    ("applyInotifyWatchLimit", ("int",)), ("close", ()),
}
MODE_KEY_OWNERS = {
    Path("SyncthingModule.java"), Path("service/Constants.java"),
    Path("service/ExecutionModePreferenceCommit.java"),
    Path("service/SyncthingService.java"),
    Path("settings/SettingsBehaviorScreen.kt"),
    Path("onboarding/OnboardingActivity.kt"),
    Path("activities/FolderActivity.java"), Path("activities/DeviceActivity.java"),
    Path("util/ConfigRouter.java"),
}
GENERIC_PRIVILEGED_NAMES = re.compile(
    r"(?:execute|exec|run|read|write|delete|remove|copy)(?:Root|Privileged|Arbitrary)"
    r"(?:Command|Shell|Path|File|Operation)?|(?:execute|run)Command|"
    r"(?:read|write|delete|remove|copy)ArbitraryPath"
)
PROCESS_NAME_SIGNALS = re.compile(r"\b(?:pkill|killall|pidof)\b", re.I)
NAME_BASED_KILL = re.compile(r"\bkill\b.*\b(?:ps|grep|syncthing|libsyncthingnative)\b", re.I)


@dataclass(frozen=True)
class Token:
    """One source token and its original one-based line number."""

    value: str
    kind: str
    line: int


def tokenize(source: str) -> list[Token]:
    """Tokenize Java, Kotlin, or Gradle syntax without treating comments as code.

    Quoted arguments remain tokens so a command passed to a process API can be inspected.
    Quoted text elsewhere is excluded from structural expression checks.
    """
    tokens = []
    index = 0
    line = 1
    while index < len(source):
        start = index
        char = source[index]
        if char.isspace():
            line += char == "\n"
            index += 1
        elif source.startswith("//", index) or (char == "#"):
            end = source.find("\n", index)
            index = len(source) if end < 0 else end
        elif source.startswith("/*", index):
            end = source.find("*/", index + 2)
            if end < 0:
                raise ValueError(f"unterminated block comment at line {line}")
            index = end + 2
            line += source[start:index].count("\n")
        elif char in ('"', "'"):
            delimiter = char * (3 if source.startswith(char * 3, index) else 1)
            index += len(delimiter)
            while index < len(source):
                if source[index] == "\\" and len(delimiter) == 1:
                    index += 2
                elif source.startswith(delimiter, index):
                    index += len(delimiter)
                    break
                else:
                    index += 1
            else:
                raise ValueError(f"unterminated string at line {line}")
            tokens.append(Token(source[start:index], "string", line))
            line += source[start:index].count("\n")
        elif char.isalpha() or char in "_$":
            index += 1
            while index < len(source) and (source[index].isalnum() or source[index] in "_$"):
                index += 1
            tokens.append(Token(source[start:index], "name", line))
        else:
            tokens.append(Token(char, "symbol", line))
            index += 1
    return tokens


def has_sequence(tokens: list[Token], *values: str) -> bool:
    """Find an exact token sequence while ignoring formatting and comments."""
    return any(
        all(tokens[index + offset].value == value for offset, value in enumerate(values))
        for index in range(len(tokens) - len(values) + 1)
    )


def sequence_count(tokens: list[Token], *values: str) -> int:
    """Count exact call prefixes, including duplicate invocations in one file."""
    return sum(
        has_sequence(tokens[index:index + len(values)], *values)
        for index in range(len(tokens) - len(values) + 1)
    )


def call_arguments(tokens: list[Token], opening: int) -> list[Token]:
    """Return tokens inside a balanced invocation's outer parentheses."""
    depth = 1
    result = []
    for token in tokens[opening + 1:]:
        if token.value == "(":
            depth += 1
        elif token.value == ")":
            depth -= 1
            if depth == 0:
                return result
        result.append(token)
    return result


def closing_token(tokens: list[Token], opening: int, left: str, right: str) -> int:
    """Find the matching delimiter for a source-level declaration or block."""
    depth = 0
    for index in range(opening, len(tokens)):
        depth += (tokens[index].value == left) - (tokens[index].value == right)
        if depth == 0:
            return index
    return len(tokens)


def simple_assignments(tokens: list[Token], end: int,
                       kotlin: bool = False) -> dict[str, list[Token]]:
    """Resolve earlier simple assignments, including Kotlin's semicolon-free lines."""
    assignments = {}
    for index in range(1, end - 1):
        if tokens[index].value != "=" or tokens[index - 1].kind != "name":
            continue
        cursor = index + 1
        expression = []
        while cursor < end and tokens[cursor].value not in (";", "}", "{"):
            if (kotlin and expression and tokens[cursor].line > expression[-1].line and
                    expression[-1].value not in (".", "+") and
                    tokens[cursor].value not in (".", "+")):
                break
            expression.append(tokens[cursor])
            cursor += 1
        if expression and all(token.value not in ("(", ")", "?") for token in expression):
            assignments[tokens[index - 1].value] = expression
        else:
            assignments.pop(tokens[index - 1].value, None)
    return assignments


def resolved_arguments(tokens: list[Token], opening: int,
                       kotlin: bool = False) -> list[Token]:
    """Include literals or preference keys from straightforward earlier local assignments."""
    arguments = call_arguments(tokens, opening)
    assignments = simple_assignments(tokens, opening, kotlin)
    result = list(arguments)
    for argument in arguments:
        if argument.kind == "name" and argument.value in assignments:
            result.extend(assignments[argument.value])
    return result


def root_shell_declarations(tokens: list[Token]):
    """Yield direct interface methods, including default methods with bodies.

    Nested types have their own methods and are outside the RootShell contract.
    """
    interface = next((index for index in range(len(tokens) - 1)
                      if tokens[index].value == "interface" and
                      tokens[index + 1].value == "RootShell"), None)
    if interface is None:
        return
    opening = next((index for index in range(interface + 2, len(tokens))
                    if tokens[index].value == "{"), None)
    if opening is None:
        return
    depth = 1
    for index in range(opening + 1, len(tokens) - 1):
        token = tokens[index]
        if token.value == "{":
            depth += 1
            continue
        if token.value == "}":
            depth -= 1
            if depth == 0:
                break
            continue
        if depth != 1:
            continue
        if token.kind != "name" or tokens[index + 1].value != "(":
            continue
        end = closing_token(tokens, index + 1, "(", ")")
        if end == len(tokens):
            continue
        cursor = end + 1
        while cursor < len(tokens) and tokens[cursor].value not in (";", "{"):
            cursor += 1
        if cursor == len(tokens):
            continue
        # A method declaration has a return type before its name. Calls do not.
        if index == 0 or tokens[index - 1].value in (".", "return", "=", "("):
            continue
        parameters = []
        segment = []
        for part in tokens[index + 2:end] + [Token(",", "symbol", token.line)]:
            if part.value == ",":
                if segment:
                    parameters.append("".join(piece.value for piece in segment[:-1]))
                    segment = []
            else:
                segment.append(part)
        yield token.value, tuple(parameters)


def import_validation_aborts(body: list[Token]) -> bool:
    """Require extraction's catch to end in a top-level, unconditional false return."""
    values = [token.value for token in body]
    try:
        extraction = values.index("extract")
        shutdown = values.index("shutdownForFileMutation")
        installation = values.index("installImportedState")
    except ValueError:
        return False
    if not extraction < shutdown < installation:
        return False
    for index, value in enumerate(values[:extraction]):
        if value != "try" or index + 1 >= len(body) or values[index + 1] != "{":
            continue
        end = closing_token(body, index + 1, "{", "}")
        if not index < extraction < end or end >= shutdown:
            continue
        catch = end + 1
        if catch + 2 >= shutdown or values[catch:catch + 2] != ["catch", "("]:
            continue
        parameter_end = closing_token(body, catch + 1, "(", ")")
        if "Exception" not in values[catch + 2:parameter_end]:
            continue
        opening = parameter_end + 1
        if opening >= shutdown or values[opening] != "{":
            continue
        catch_end = closing_token(body, opening, "{", "}")
        if catch_end >= shutdown:
            continue
        catch_body = body[opening + 1:catch_end]
        if [token.value for token in catch_body[-3:]] != ["return", "false", ";"]:
            continue
        return_index = len(catch_body) - 3
        depth = sum((token.value == "{") - (token.value == "}")
                    for token in catch_body[:return_index])
        if depth == 0 and (return_index == 0 or
                           catch_body[return_index - 1].value in (";", "}")):
            return True
    return False


def method_body(tokens: list[Token], name: str) -> list[Token]:
    """Return one named method's brace-delimited body, including nested blocks."""
    for index, token in enumerate(tokens[:-1]):
        if token.value != name or tokens[index + 1].value != "(":
            continue
        if index == 0 or tokens[index - 1].value not in ("boolean", "void", "String"):
            continue
        parentheses = 1
        cursor = index + 2
        while cursor < len(tokens) and parentheses:
            parentheses += (tokens[cursor].value == "(") - (tokens[cursor].value == ")")
            cursor += 1
        while cursor < len(tokens) and tokens[cursor].value != "{":
            if tokens[cursor].value == ";":
                break
            cursor += 1
        if cursor >= len(tokens) or tokens[cursor].value != "{":
            continue
        depth = 1
        start = cursor + 1
        cursor = start
        while cursor < len(tokens) and depth:
            depth += (tokens[cursor].value == "{") - (tokens[cursor].value == "}")
            cursor += 1
        return tokens[start:cursor - 1]
    return []


def process_invocations(tokens: list[Token],
                        kotlin: bool = False) -> list[tuple[str, list[Token]]]:
    """Identify process creation and command dispatch entrypoints."""
    invocations = []
    for index, token in enumerate(tokens):
        if token.value == "ProcessBuilder" and index + 1 < len(tokens):
            if tokens[index + 1].value == "(":
                invocations.append(("process creation", resolved_arguments(tokens, index + 1,
                                                                           kotlin)))
        if has_sequence(tokens[index:index + 7], "Runtime", ".", "getRuntime", "(", ")", ".", "exec"):
            opening = index + 7
            if opening < len(tokens) and tokens[opening].value == "(":
                invocations.append(("process creation", resolved_arguments(tokens, opening,
                                                                           kotlin)))
        if token.value == "add" and index >= 5 and has_sequence(
                tokens[index - 5:index + 1], ".", "newJob", "(", ")", ".", "add"):
            if index + 1 < len(tokens) and tokens[index + 1].value == "(":
                invocations.append(("shell job", resolved_arguments(tokens, index + 1, kotlin)))
    return invocations


def mode_write_count(tokens: list[Token], kotlin: bool = False) -> int:
    """Count direct and simple local-alias writes of the execution-mode key."""
    count = 0
    for index, token in enumerate(tokens[:-1]):
        if token.value not in ("putBoolean", "putString", "putInt", "remove"):
            continue
        if tokens[index + 1].value != "(":
            continue
        arguments = resolved_arguments(tokens, index + 1, kotlin)
        key_tokens = []
        for argument in arguments:
            if argument.value == ",":
                break
            key_tokens.append(argument)
        assignments = simple_assignments(tokens, index, kotlin)
        for argument in list(key_tokens):
            if argument.kind == "name" and argument.value in assignments:
                key_tokens.extend(assignments[argument.value])
        if any(argument.value in ('"use_root"', "PREF_USE_ROOT") for argument in key_tokens):
            count += 1
    return count


def check_source(relative: Path, source: str) -> list[str]:
    """Report forbidden structural additions in one production source file."""
    tokens = tokenize(source)
    code = [token for token in tokens if token.kind != "string"]
    values = [token.value for token in code]
    findings = []

    def report(message):
        findings.append(f"{relative}: {message}")

    for index, token in enumerate(code):
        if token.value != "import":
            continue
        imported = []
        for following in code[index + 1:]:
            if following.value == ";" or (relative.suffix == ".kt" and
                                            following.line > token.line):
                break
            imported.append(following.value)
            if len(imported) > 30:
                break
        name = "".join(imported)
        if "com.topjohnwu.superuser" in name and relative not in LIBSU_TRANSPORT:
            report("libsu import outside the two root transport files")
        if "com.topjohnwu.superuser" in name and (".service" in name or ".nio" in name):
            report("libsu service/nio import")
        if name.startswith("static") and any(
                name.endswith(f".Shell.{method}") for method in (
                    "getShell", "cmd", "setDefaultBuilder", "setFlags", "setTimeout"
                )):
            report("global libsu shell routing")

    if relative.suffix == ".aidl" or "RootService" in values or "IRootService" in values:
        report("RootService/AIDL privileged runtime")
    for method in ("getShell", "cmd", "setDefaultBuilder", "setFlags", "setTimeout"):
        if has_sequence(code, "Shell", ".", method, "("):
            report("global libsu shell routing")
            break
    if relative != Path("runtime/LibsuRootShell.java") and has_sequence(code, ".", "newJob", "("):
        report("libsu job outside root transport")

    for index, token in enumerate(code):
        named_generic = GENERIC_PRIVILEGED_NAMES.fullmatch(token.value)
        bare_generic = (any(marker in relative.stem for marker in (
            "Root", "Privileged", "Privilege", "Shell"
        )) and token.value in (
            "execute", "run", "read", "write", "delete", "remove", "copy"
        ))
        if not named_generic and not bare_generic:
            continue
        if index + 1 >= len(code) or code[index + 1].value != "(":
            continue
        prior = []
        for preceding in reversed(code[:index]):
            if preceding.value in ("{", "}", ";"):
                break
            prior.append(preceding.value)
        arguments = call_arguments(code, index + 1)
        kotlin_exposed = (relative.suffix == ".kt" and index > 0 and
                          code[index - 1].value == "fun" and
                          "private" not in prior and "internal" not in prior)
        if ("public" in prior or "protected" in prior or kotlin_exposed) and (
                named_generic or any(argument.value in ("String", "File", "Path")
                                     for argument in arguments)):
            report("generic privileged API")
            break
    if relative == Path("runtime/RootShell.java"):
        # Parameter types are authority: an overload can widen an approved method's scope.
        if any(signature not in ROOT_SHELL_SIGNATURES
               for signature in root_shell_declarations(code)):
            report("new RootShell operation outside its closed semantic API")

    terminal_calls = [index for index in range(1, len(code) - 1)
                      if has_sequence(code[index - 1:index + 2], ".", "execTerminalScript", "(")]
    permitted_terminal = {
        Path("runtime/RootBackend.java"): ("launchShell", "launchScript"),
        Path("runtime/SessionOwnedRootShell.java"): ("sessionShell", "script"),
    }
    expected_terminal = permitted_terminal.get(relative)
    for index in terminal_calls:
        arguments = call_arguments(code, index + 1)
        if (expected_terminal is None or len(terminal_calls) != 1 or
                index < 2 or code[index - 2].value != expected_terminal[0] or
                [argument.value for argument in arguments] != [expected_terminal[1]]):
            report("terminal script outside audited launch path")
            break

    if relative.parent == Path("runtime") and "PREF_USE_ROOT" in values:
        report("mode branching inside mode-neutral runtime")
    if "PREF_USE_ROOT" in values and relative not in MODE_KEY_OWNERS:
        report("mode branching after backend selection")
    if relative in (Path("service/SyncthingRunnable.java"), Path("util/ConfigXml.java")):
        if "PREF_USE_ROOT" in values or "isSuperuserModeSelected" in values:
            report("mode branching after backend selection")
    if "selectExecutionMode" in values and relative not in (
            Path("service/SyncthingService.java"), Path("runtime/DefaultSyncthingRuntime.java")):
        report("backend selection outside service authority")

    writes = mode_write_count(tokens, relative.suffix == ".kt")
    if writes and relative != Path("service/ExecutionModePreferenceCommit.java"):
        report("mode preference writer outside transition authority")
    if relative == Path("service/ExecutionModePreferenceCommit.java"):
        commit_writes = mode_write_count(method_body(tokens, "commit"))
        if writes != commit_writes:
            report("mode preference write outside commit operation")
    restore_writer = ("putPreferenceValue", "(", "editor", ",",
                      "Constants", ".", "PREF_USE_ROOT")
    restore_count = sequence_count(tokens, *restore_writer)
    if restore_count:
        restore_body = method_body(tokens, "restoreLocalExecutionModePreference")
        authorized_count = sequence_count(restore_body, *restore_writer)
        if (relative != Path("service/SyncthingService.java") or
                restore_count != 1 or authorized_count != 1):
            report("mode preference writer outside import restoration")

    for name, arguments in process_invocations(tokens, relative.suffix == ".kt"):
        if name == "process creation" and relative not in PROCESS_CREATORS:
            report("process creation outside approved backend or normal-UID helper")
        command_strings = " ".join(token.value for token in arguments if token.kind == "string")
        if PROCESS_NAME_SIGNALS.search(command_strings) or NAME_BASED_KILL.search(command_strings):
            report("process-name signal in execution argument")
        if "libsyncthingnative.so" in command_strings:
            report("bundled binary invocation outside selected backend")
    if relative in (Path("runtime/RootShellEncoder.java"),
                    Path("runtime/LibsuRootShell.java")):
        if any(token.kind == "string" and (PROCESS_NAME_SIGNALS.search(token.value)
                                          or NAME_BASED_KILL.search(token.value))
               for token in tokens):
            report("process-name signal in root transport script")
    if "getSyncthingBinary" in values and relative not in BINARY_OWNERS:
        report("bundled binary access outside selected backend")
    if "FILENAME_SYNCTHING_BINARY" in values and relative not in (
            BINARY_OWNERS | {Path("service/SyncthingService.java")}):
        report("bundled binary access outside selected backend")
    if has_sequence(code, "Process", ".", "killProcess", "(") or has_sequence(
            code, "Process", ".", "sendSignal", "("):
        report("direct process signal outside exact ownership")
    if relative == Path("service/SyncthingService.java"):
        body = method_body(code, "importConfig")
        expressions = " ".join(token.value for token in body)
        extraction = expressions.find("SyncthingBackupArchive . extract (")
        shutdown = expressions.find("shutdownForFileMutation (")
        installation = expressions.find("installImportedState (")
        if body and (extraction < 0 or shutdown < 0 or installation < 0 or
                     not extraction < shutdown < installation):
            report("import validation must precede live mutation")
        if body and not import_validation_aborts(body):
            report("import validation failure must abort before live mutation")
        preference_body = method_body(code, "importConfigSharedPrefs")
        if preference_body and (not has_sequence(preference_body, "if", "(",
                                                 "isDeviceLocalPreference", "(")
                                or not has_sequence(preference_body,
                                                    "restoreLocalDevicePreferences", "(")):
            report("import must filter and restore device-local preferences")
    return findings


def check_repository(root: Path) -> list[str]:
    """Inspect all present production sources and relevant dependency declarations."""
    findings = []
    production = root / APP_MAIN
    if production.exists():
        for path in sorted(production.rglob("*")):
            if path.is_file() and path.suffix in (".java", ".kt", ".aidl"):
                package_root = root / SOURCE_ROOT
                relative = (path.relative_to(package_root) if path.is_relative_to(package_root)
                            else path.relative_to(production))
                try:
                    findings.extend(check_source(relative, path.read_text(encoding="utf-8")))
                except ValueError as error:
                    findings.append(f"{relative}: {error}")
    for relative in (Path("gradle/libs.versions.toml"), Path("app/build.gradle.kts")):
        path = root / relative
        if not path.is_file():
            continue
        contents = path.read_text(encoding="utf-8")
        for line_number, line in enumerate(contents.splitlines(), 1):
            declaration = line.split("#", 1)[0]
            artifact = re.search(r"com\.github\.topjohnwu\.libsu:([\w-]+)\b", declaration)
            if artifact and artifact.group(1) != "core":
                findings.append(f"{relative}:{line_number}: libsu core-only dependency")
                if artifact.group(1) in ("service", "nio"):
                    findings.append(f"{relative}:{line_number}: libsu service/nio dependency")
            if re.search(r"libs\.libsu\.(?:service|nio)\b", declaration):
                findings.append(f"{relative}:{line_number}: libsu service/nio dependency")
            if relative == Path("gradle/libs.versions.toml"):
                version = re.search(r'^\s*libsu\s*=\s*"([^"]+)"', declaration)
                if version and version.group(1) != "6.0.0":
                    findings.append(f"{relative}:{line_number}: libsu core version must remain 6.0.0")
    return sorted(set(findings))


def main() -> int:
    """Check the repository containing this script and print actionable failures."""
    root = Path(__file__).resolve().parent.parent
    findings = check_repository(root)
    for finding in findings:
        print(finding, file=sys.stderr)
    if findings:
        return 1
    print("Superuser architecture guardrails passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
