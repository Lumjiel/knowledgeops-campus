package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * File: WorkspaceRuntime.java
 * <p>
 * Sandbox {@link AgentRuntime} that gives the agent harness read/write access to a
 * dedicated workspace directory. It implements six actions:
 * <ul>
 *   <li>{@code workspace_list_files} — bounded-depth file tree listing</li>
 *   <li>{@code workspace_read_file} — size-capped file read</li>
 *   <li>{@code workspace_search_text} — bounded text search across workspace files</li>
 *   <li>{@code workspace_propose_patch} — build a unified-diff preview without writing</li>
 *   <li>{@code workspace_apply_patch} — write file content, optionally via a unified diff</li>
 *   <li>{@code workspace_run_shell} — run a strictly allow-listed command</li>
 * </ul>
 * <p>
 * Security model: every path is resolved against the workspace root and rejected if it
 * escapes that root ({@link #resolvePath}); file content, patch, search and shell output
 * sizes are capped via {@link AgentHarnessProperties.Workspace} limits; writes and shell
 * execution are gated by the {@code writeEnabled} / {@code shellEnabled} flags; and shell
 * commands are restricted to a whitelist (pwd/ls/rg/git/mvn) with additional flag and
 * argument validation.
 * <p>
 * All failures — unsupported actions, disabled features, size caps, timeouts, IO errors —
 * are reported as error {@link AgentObservation}s instead of being thrown, so one failing
 * action never aborts the surrounding agent loop.
 */
@Component
public class WorkspaceRuntime implements AgentRuntime {
    /** Action names dispatched by {@link #execute}; kept in sync with {@link #supports}. */
    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
            "workspace_list_files",
            "workspace_read_file",
            "workspace_search_text",
            "workspace_propose_patch",
            "workspace_apply_patch",
            "workspace_run_shell"
    );

    private final AgentHarnessProperties harnessProperties;
    private final UnifiedDiffService diffService;
    private final Path workspaceRoot;

    /**
     * Spring constructor: resolves the workspace root from configuration.
     *
     * @param harnessProperties harness limits and feature flags
     * @param diffService       unified-diff builder/applier used by the patch actions
     */
    @Autowired
    public WorkspaceRuntime(AgentHarnessProperties harnessProperties, UnifiedDiffService diffService) {
        this(harnessProperties, diffService, Path.of(harnessProperties.getWorkspace().getRoot()));
    }

    /**
     * Test convenience constructor: enables writes and shell by default and
     * uses the given directory as workspace root.
     */
    WorkspaceRuntime(Path workspaceRoot) {
        this(defaultProperties(workspaceRoot), new UnifiedDiffService(), workspaceRoot);
    }

    /** Full constructor; the root is made absolute and normalized so path checks are stable. */
    WorkspaceRuntime(AgentHarnessProperties harnessProperties, UnifiedDiffService diffService, Path workspaceRoot) {
        this.harnessProperties = harnessProperties;
        this.diffService = diffService;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /** Runtime identifier reported on every {@link AgentObservation} produced here. */
    @Override
    public String source() {
        return "workspace";
    }

    /** {@return true if the harness should route the given action to this runtime} */
    @Override
    public boolean supports(String action) {
        return SUPPORTED_ACTIONS.contains(action);
    }

    /**
     * Dispatches one workspace action and always returns an observation:
     * {@code success} with the action payload, or {@code error} with a message
     * for unsupported actions, disabled features, size/timeout violations, and
     * IO or runtime failures (including re-interrupt on {@link InterruptedException}).
     */
    @Override
    public AgentObservation execute(AgentAction action) {
        long startedNs = System.nanoTime();
        if (action == null || !StringUtils.hasText(action.action())) {
            return AgentObservation.error(source(),
                    "workspace action is required (no action name supplied)", elapsedMs(startedNs));
        }
        String actionName = action.action();
        // Null input must not NPE inside the handlers; treat it as empty payload.
        Map<String, Object> input = action.actionInput() != null ? action.actionInput() : Map.of();
        try {
            Map<String, Object> payload = switch (actionName) {
                case "workspace_list_files" -> listFiles(input);
                case "workspace_read_file" -> readFile(input);
                case "workspace_search_text" -> searchText(input);
                case "workspace_propose_patch" -> proposePatch(input);
                case "workspace_apply_patch" -> applyPatch(input);
                case "workspace_run_shell" -> runCommand(input);
                default -> Map.of("status", "error", "message", "unsupported action: " + actionName);
            };
            if ("error".equals(payload.get("status"))) {
                Object message = payload.get("message");
                return AgentObservation.error(source(), String.valueOf(message), elapsedMs(startedNs));
            }
            return AgentObservation.success(source(), payload, elapsedMs(startedNs));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return AgentObservation.error(source(), failureMessage(actionName, ex), elapsedMs(startedNs));
        } catch (IOException ex) {
            return AgentObservation.error(source(), failureMessage(actionName, ex), elapsedMs(startedNs));
        } catch (RuntimeException ex) {
            return AgentObservation.error(source(), failureMessage(actionName, ex), elapsedMs(startedNs));
        }
    }

    /**
     * Builds one failure message naming the action and the reason; falls back
     * to the exception class name when {@code getMessage()} is blank (e.g.
     * NPE) so the reported cause is never just "null".
     */
    private String failureMessage(String actionName, Exception ex) {
        String reason = StringUtils.hasText(ex.getMessage())
                ? ex.getMessage()
                : ex.getClass().getSimpleName() + " (no message)";
        return "workspace action '" + actionName + "' failed: " + reason;
    }

    /**
     * workspace_list_files: walks the given path (default {@code .}) up to
     * {@code maxDepth} (clamped to 0..5, default 2) and returns up to 200
     * entries sorted by path, plus a {@code truncated} flag so a caller can
     * tell when entries beyond the cap were silently dropped.
     */
    private Map<String, Object> listFiles(Map<String, Object> input) throws IOException {
        Path root = resolvePath(stringVal(input, "path", "."));
        int maxDepth = Math.min(Math.max(intVal(input.get("maxDepth"), 2), 0), 5);
        List<Map<String, Object>> files = new ArrayList<>();
        boolean truncated;
        try (Stream<Path> stream = Files.walk(root, maxDepth)) {
            // One entry past the cap detects truncation; sorted() already
            // materializes the whole walk, so this costs nothing extra.
            List<Path> entries = stream.sorted(Comparator.naturalOrder()).limit(201).toList();
            truncated = entries.size() > 200;
            entries.stream().limit(200).forEach(path -> files.add(fileSummary(path)));
        }
        return Map.of("root", relative(root), "files", files, "truncated", truncated);
    }

    /**
     * workspace_read_file: reads at most {@code maxBytes} (default and ceiling:
     * configured {@code maxFileBytes}) UTF-8 bytes of the file and reports
     * whether it was truncated. Returns an error payload if the file is missing.
     */
    private Map<String, Object> readFile(Map<String, Object> input) throws IOException {
        Path path = resolvePath(stringVal(input, "path", ""));
        if (!Files.isRegularFile(path)) {
            return Map.of("status", "error", "message", "file not found: " + relative(path));
        }
        int maxFileBytes = Math.max(1, harnessProperties.getWorkspace().getMaxFileBytes());
        int maxBytes = Math.min(Math.max(intVal(input.get("maxBytes"), maxFileBytes), 1), maxFileBytes);
        byte[] bytes = readPrefix(path, maxBytes);
        return Map.of(
                "path", relative(path),
                "content", new String(bytes, StandardCharsets.UTF_8),
                "truncated", Files.size(path) > bytes.length
        );
    }

    /**
     * workspace_search_text: substring search over up to the configured
     * {@code maxSearchFiles} regular files under the given path (depth 8),
     * skipping files larger than 1 MB. Returns at most {@code maxMatches}
     * (clamped to 1..100, default 50) matches as path/lineNumber/line tuples.
     * A blank query is rejected with an error payload instead of matching
     * every line.
     */
    private Map<String, Object> searchText(Map<String, Object> input) throws IOException {
        String query = stringVal(input, "query", "");
        if (!StringUtils.hasText(query)) {
            // A blank query would make every line match and flood the result;
            // reject it instead of returning an arbitrary prefix of one file.
            return Map.of("status", "error", "message", "search query is required");
        }
        Path root = resolvePath(stringVal(input, "path", "."));
        int maxMatches = Math.min(Math.max(intVal(input.get("maxMatches"), 50), 1), 100);
        List<Map<String, Object>> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 8)) {
            List<Path> candidates = stream.filter(Files::isRegularFile)
                    .limit(Math.max(1, harnessProperties.getWorkspace().getMaxSearchFiles()))
                    .toList();
            for (Path path : candidates) {
                // Already collected enough matches; stop walking the candidate
                // list instead of continuing to open and skip every remaining
                // file.
                if (matches.size() >= maxMatches) {
                    break;
                }
                long fileSize;
                try {
                    fileSize = Files.size(path);
                } catch (IOException unreadable) {
                    // One unreadable file must not fail the whole search; skip it.
                    continue;
                }
                if (fileSize > 1_000_000) {
                    continue;
                }
                List<String> lines;
                try {
                    lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    continue;
                }
                for (int i = 0; i < lines.size() && matches.size() < maxMatches; i++) {
                    String line = lines.get(i);
                    if (line.contains(query)) {
                        matches.add(Map.of(
                                "path", relative(path),
                                "lineNumber", i + 1,
                                "line", line
                        ));
                    }
                }
            }
        }
        return Map.of("query", query, "matches", matches);
    }

    /**
     * workspace_propose_patch: builds a write preview (unified diff via
     * {@link UnifiedDiffService#create} unless a {@code patch} is supplied)
     * without touching the filesystem. Enforces the {@code maxFileBytes} cap
     * on content, patch and existing file size so oversized proposals are
     * rejected before they can be applied.
     */
    private Map<String, Object> proposePatch(Map<String, Object> input) throws IOException {
        Path path = resolvePath(stringVal(input, "path", ""));
        if (path.equals(workspaceRoot)) {
            // Blank/absent path resolves to the root itself; a proposal against
            // the root directory is always caller error, so say so explicitly.
            return Map.of("status", "error", "message", "path is required (resolves to workspace root)");
        }
        String content = stringVal(input, "content", "");
        String patch = stringVal(input, "patch", "");
        // Cap the proposed file size the same way readFile caps read size.
        // Without this an LLM-driven agent invocation of workspace_propose_patch
        // could submit a multi-megabyte content / patch and cause the worker
        // to allocate a matching string, then applyPatch to write the file.
        int maxFileBytes = Math.max(1, harnessProperties.getWorkspace().getMaxFileBytes());
        if (content.getBytes(StandardCharsets.UTF_8).length > maxFileBytes) {
            return Map.of("status", "error", "message",
                    "content exceeds maxFileBytes (" + maxFileBytes + ")");
        }
        if (patch.getBytes(StandardCharsets.UTF_8).length > maxFileBytes) {
            return Map.of("status", "error", "message",
                    "patch exceeds maxFileBytes (" + maxFileBytes + ")");
        }
        if (Files.exists(path) && Files.isRegularFile(path)
                && Files.size(path) > maxFileBytes) {
            return Map.of("status", "error", "message",
                    "existing file exceeds maxFileBytes (" + maxFileBytes + ")");
        }
        String oldContent = Files.exists(path) && Files.isRegularFile(path)
                ? Files.readString(path, StandardCharsets.UTF_8)
                : "";
        String diff = StringUtils.hasText(patch)
                ? patch
                : diffService.create(relative(path), oldContent, content);
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("path", relative(path));
        proposal.put("summary", stringVal(input, "summary", ""));
        proposal.put("contentBytes", content.getBytes(StandardCharsets.UTF_8).length);
        proposal.put("patch", diff);
        proposal.put("wouldCreate", !Files.exists(path));
        proposal.put("applyAction", "workspace_apply_patch");
        return proposal;
    }

    /**
     * workspace_apply_patch: the only filesystem-mutating action. Refuses when
     * writes are disabled; final content comes from {@code content}, or from
     * applying {@code patch} onto the current file content via
     * {@link UnifiedDiffService#apply}. Re-checks the {@code maxFileBytes} cap
     * on the resulting content, creates parent directories, and overwrites the
     * file (create/truncate).
     */
    private Map<String, Object> applyPatch(Map<String, Object> input) throws IOException {
        if (!harnessProperties.getWorkspace().isWriteEnabled()) {
            return Map.of("status", "error", "message", "workspace writes are disabled");
        }
        Path path = resolvePath(stringVal(input, "path", ""));
        if (path.equals(workspaceRoot)) {
            // Writing to the root itself can only fail later with a confusing
            // "is a directory" IO error; reject it with an explicit reason.
            return Map.of("status", "error", "message", "path is required (resolves to workspace root)");
        }
        String content = stringVal(input, "content", "");
        String patch = stringVal(input, "patch", "");
        // Cap the same way proposePatch does so a caller cannot bypass the
        // preview cap by going straight to apply.
        int maxFileBytes = Math.max(1, harnessProperties.getWorkspace().getMaxFileBytes());
        if (content.getBytes(StandardCharsets.UTF_8).length > maxFileBytes) {
            return Map.of("status", "error", "message",
                    "content exceeds maxFileBytes (" + maxFileBytes + ")");
        }
        if (patch.getBytes(StandardCharsets.UTF_8).length > maxFileBytes) {
            return Map.of("status", "error", "message",
                    "patch exceeds maxFileBytes (" + maxFileBytes + ")");
        }
        String nextContent = content;
        if (StringUtils.hasText(patch)) {
            String oldContent = Files.exists(path) && Files.isRegularFile(path)
                    ? Files.readString(path, StandardCharsets.UTF_8)
                    : "";
            nextContent = diffService.apply(oldContent, patch);
        }
        if (nextContent.getBytes(StandardCharsets.UTF_8).length > maxFileBytes) {
            return Map.of("status", "error", "message",
                    "resulting content exceeds maxFileBytes (" + maxFileBytes + ")");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, nextContent, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return Map.of(
                "status", "written",
                "path", relative(path),
                "bytes", nextContent.getBytes(StandardCharsets.UTF_8).length
        );
    }

    /**
     * workspace_run_shell: runs an allow-listed command (see
     * {@link #isAllowedCommand}) in the workspace root with stdout/stderr read
     * concurrently so the child cannot block on full pipes. Timeout is clamped
     * to 1..30 seconds; on timeout the child is killed and an error payload is
     * returned. {@code destroyForcibly} in {@code finally} reaps the child in
     * every exit path. Stream readers are awaited with the same bounded
     * deadline because an exited process does not guarantee stream EOF (a
     * forked grandchild can inherit the pipe and keep it open).
     */
    private Map<String, Object> runCommand(Map<String, Object> input) throws IOException, InterruptedException {
        if (!harnessProperties.getWorkspace().isShellEnabled()) {
            return Map.of("status", "error", "message", "workspace shell is disabled");
        }
        List<String> command = tokenizeCommand(stringVal(input, "command", ""));
        if (!isAllowedCommand(command)) {
            return Map.of("status", "error", "message", "command is not allowed");
        }
        int defaultTimeout = Math.max(1, harnessProperties.getWorkspace().getCommandTimeoutSeconds());
        int timeoutSeconds = Math.min(Math.max(intVal(input.get("timeoutSeconds"), defaultTimeout), 1), 30);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspaceRoot.toFile());
        Process process = builder.start();
        try {
            CompletableFuture<ProcessOutput> stdout = CompletableFuture.supplyAsync(
                    () -> readProcessOutput(process.getInputStream()));
            CompletableFuture<ProcessOutput> stderr = CompletableFuture.supplyAsync(
                    () -> readProcessOutput(process.getErrorStream()));
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                return Map.of("status", "error", "message",
                        "command timed out after " + timeoutSeconds + "s and was killed");
            }
            ProcessOutput stdoutOutput = awaitOutput(stdout, "stdout", timeoutSeconds);
            ProcessOutput stderrOutput = awaitOutput(stderr, "stderr", timeoutSeconds);
            String stdoutText = new String(stdoutOutput.bytes(), StandardCharsets.UTF_8);
            String stderrText = new String(stderrOutput.bytes(), StandardCharsets.UTF_8);
            return Map.of(
                    "exitCode", process.exitValue(),
                    "stdout", stdoutText,
                    "stderr", stderrText,
                    "output", stdoutText + stderrText,
                    "truncated", stdoutOutput.truncated() || stderrOutput.truncated()
            );
        } finally {
            // No-op for an already-exited process; guarantees the child is reaped even
            // when waitFor is interrupted, so no handle is left behind.
            process.destroyForcibly();
        }
    }

    /**
     * Waits for one output-reader future with a bounded deadline. {@code join()}
     * would block forever if a forked grandchild inherits the pipe and keeps it
     * open after the parent exits, so on timeout (or reader failure) the action
     * degrades to a marker payload instead of hanging. The caller's
     * {@code destroyForcibly} in {@code finally} closes the underlying streams.
     */
    private ProcessOutput awaitOutput(CompletableFuture<ProcessOutput> future, String streamName,
                                      int timeoutSeconds) throws InterruptedException {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            return new ProcessOutput(("output not available: " + streamName
                    + " stream read timed out after " + timeoutSeconds + "s")
                    .getBytes(StandardCharsets.UTF_8), true);
        } catch (InterruptedException ex) {
            // Propagate so the caller can convert the interruption into an
            // error observation with the interrupt flag kept set.
            Thread.currentThread().interrupt();
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            String reason = StringUtils.hasText(cause.getMessage())
                    ? cause.getMessage() : cause.getClass().getSimpleName();
            return new ProcessOutput(("output not available: failed to read " + streamName + ": " + reason)
                    .getBytes(StandardCharsets.UTF_8), false);
        }
    }

    /** Builds one listFiles entry: relative path, type, and best-effort file size. */
    private Map<String, Object> fileSummary(Path path) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", relative(path));
        result.put("type", Files.isDirectory(path) ? "directory" : "file");
        if (Files.isRegularFile(path)) {
            try {
                result.put("size", Files.size(path));
            } catch (IOException ignored) {
                result.put("size", null);
            }
        }
        return result;
    }

    /**
     * Resolves {@code raw} against the workspace root (defaulting to {@code .})
     * and throws {@link IllegalArgumentException} if the normalized path escapes
     * the root — the central guard against path traversal via {@code ..} or
     * absolute paths.
     */
    private Path resolvePath(String raw) {
        Path resolved = workspaceRoot.resolve(StringUtils.hasText(raw) ? raw : ".").normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            // Include the offending input so the caller sees which path failed.
            throw new IllegalArgumentException("path escapes workspace root: " + raw);
        }
        return resolved;
    }

    /** {@return the given path relative to the workspace root, or "." for the root itself} */
    private String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(workspaceRoot)) {
            return ".";
        }
        return workspaceRoot.relativize(normalized).toString();
    }

    /** Reads at most {@code maxBytes} leading bytes from the file. */
    private byte[] readPrefix(Path path, int maxBytes) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return input.readNBytes(maxBytes);
        }
    }

    /**
     * Drains one process stream into memory, truncating to the configured
     * {@code maxCommandOutputBytes} cap. Reads in bounded chunks and keeps
     * draining past the cap (discarding the excess) so a chatty child can
     * neither blow up memory with an unbounded buffer nor block on a full
     * pipe. Read failures are degraded to a text marker so output problems
     * never crash the command action.
     */
    private ProcessOutput readProcessOutput(InputStream stream) {
        try (var input = stream) {
            int maxOutputBytes = maxCommandOutputBytes();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8_192];
            boolean truncated = false;
            int read;
            while ((read = input.read(chunk)) != -1) {
                if (buffer.size() >= maxOutputBytes) {
                    truncated = true; // keep draining, discard the excess
                    continue;
                }
                int keep = Math.min(read, maxOutputBytes - buffer.size());
                buffer.write(chunk, 0, keep);
                if (keep < read) {
                    truncated = true;
                }
            }
            return new ProcessOutput(buffer.toByteArray(), truncated);
        } catch (IOException ex) {
            String reason = StringUtils.hasText(ex.getMessage())
                    ? ex.getMessage() : ex.getClass().getSimpleName();
            return new ProcessOutput(("failed to read process output: " + reason)
                    .getBytes(StandardCharsets.UTF_8), false);
        }
    }

    /** Splits the command string on whitespace into non-empty tokens. */
    private List<String> tokenizeCommand(String command) {
        if (!StringUtils.hasText(command)) {
            return List.of();
        }
        return Stream.of(command.trim().split("\\s+")).filter(StringUtils::hasText).toList();
    }

    /**
     * Shell allow-list: only whitelisted executables pass, and each has its own
     * argument rules — {@code pwd} takes no args; {@code ls}/{@code rg} args
     * must stay inside the workspace; {@code git} needs a whitelisted
     * subcommand and safe flags; {@code mvn} only supports {@code test} with
     * safe tokens. Everything else fails closed.
     */
    private boolean isAllowedCommand(List<String> command) {
        if (command.isEmpty()) {
            return false;
        }
        String executable = command.get(0);
        // Fail closed on a null/misconfigured allow-list instead of NPE-ing.
        Set<String> allowedCommands = harnessProperties.getWorkspace().getAllowedCommands();
        if (allowedCommands == null || !allowedCommands.contains(executable)) {
            return false;
        }
        if ("pwd".equals(executable)) {
            return command.size() == 1;
        }
        if ("ls".equals(executable) || "rg".equals(executable)) {
            // Non-flag arguments are filesystem paths (for rg the first non-flag token
            // is the pattern, which can only fail closed); reject anything resolving
            // outside the workspace root so the shell cannot be used to read host files.
            return argsWithinWorkspace(command.subList(1, command.size()));
        }
        if ("git".equals(executable)) {
            Set<String> gitSubcommands = harnessProperties.getWorkspace().getAllowedGitSubcommands();
            return command.size() >= 2
                    && gitSubcommands != null && gitSubcommands.contains(command.get(1))
                    && argsAreSafeGitFlags(command.subList(2, command.size()));
        }
        if ("mvn".equals(executable)) {
            return command.stream().anyMatch("test"::equals)
                    && command.stream().allMatch(this::isAllowedMvnTestToken);
        }
        return false;
    }

    /**
     * mvn test allow-list: -q, -D&lt;name&gt;=&lt;value&gt; where the property
     * name starts with one of the known-safe prefixes. Without this, a
     * prompt-injected or misconfigured LLM-driven agent invocation of
     * workspace_run_shell could pass
     *   mvn test -DargLine="-javaagent:/tmp/evil.jar"
     *   mvn test -Dsurefire.suiteXmlFiles=/tmp/evil.xml
     * to make the Surefire test JVM load a hostile Java agent or run a
     * custom suite XML — bypassing the workspace sandbox from inside the
     * test process.
     */
    private static final java.util.Set<String> SAFE_MVN_PROPERTY_PREFIXES = java.util.Set.of(
            // Surefire test selection knobs the user would actually want to
            // pass through. Anything else (argLine, exec.executable,
            // surefire.suiteXmlFiles, maven.compiler, etc.) can influence
            // the test JVM classpath or execution and is refused.
            "test=",
            "groups=",
            "excludedGroups=",
            "failIfNoTests=",
            "skipTests=",
            "maven.test.skip="
    );

    /** Accepts only mvn, test, -q, and {@code -D<prop>=<value>} props whose name has a safe prefix. */
    private boolean isAllowedMvnTestToken(String token) {
        if ("mvn".equals(token) || "test".equals(token) || "-q".equals(token)) {
            return true;
        }
        if (token.startsWith("-D")) {
            String property = token.substring(2);
            for (String prefix : SAFE_MVN_PROPERTY_PREFIXES) {
                if (property.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * git flag deny-list: every flag passed to a whitelisted git subcommand
     * must not match any of these. The main concern is --output which makes
     * git log / git show write to a host path, defeating the workspace
     * sandbox. --exec / --upload-pack / --receive-pack accept an
     * attacker-controlled command and are also refused.
     */
    private static final java.util.Set<String> UNSAFE_GIT_FLAGS = java.util.Set.of(
            "--output", "-o", "--exec", "--upload-pack", "--receive-pack",
            "--ssh-command");

    /** True when no git flag (value part stripped) appears in {@link #UNSAFE_GIT_FLAGS}. */
    private boolean argsAreSafeGitFlags(java.util.List<String> args) {
        return args.stream().filter(a -> a.startsWith("-"))
                .map(this::stripOptionValue)
                .noneMatch(UNSAFE_GIT_FLAGS::contains);
    }

    /** Strips a {@code =value} suffix so {@code --output=x} and {@code --output} match equally. */
    private String stripOptionValue(String arg) {
        int eq = arg.indexOf('=');
        return eq < 0 ? arg : arg.substring(0, eq);
    }

    /**
     * Validates non-flag arguments of ls/rg: each must resolve inside the
     * workspace root (fail closed on resolution errors). Also rejects the
     * dangerous flags listed inline below.
     */
    private boolean argsWithinWorkspace(List<String> args) {
        for (String arg : args) {
            if (arg.startsWith("-")) {
                // Reject options that cause the underlying tool to execute
                // commands or read arbitrary host files. ripgrep's --pre and
                // --pre-glob run a shell command before each file is
                // searched; --hostname-bin and --regexp-file read files
                // from the host filesystem. ls / git do not currently have
                // equivalents, so the option-list approach keeps the
                // allow-list narrow.
                String normalized = stripOptionValue(arg);
                if (normalized.startsWith("--pre")
                        || normalized.startsWith("--pre-glob")
                        || normalized.equals("--hostname-bin")
                        || normalized.equals("--regexp-file")) {
                    return false;
                }
                continue;
            }
            try {
                Path resolved = workspaceRoot.resolve(arg).normalize();
                if (!resolved.startsWith(workspaceRoot)) {
                    return false;
                }
            } catch (Exception ex) {
                return false;
            }
        }
        return true;
    }

    /** Output cap from configuration, floored at 1 byte. */
    private int maxCommandOutputBytes() {
        return Math.max(1, harnessProperties.getWorkspace().getMaxCommandOutputBytes());
    }

    /** Reads a trimmed string from the action payload, falling back when absent or blank. */
    private String stringVal(Map<String, Object> input, String key, String fallback) {
        Object raw = input.get(key);
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw).trim();
        return StringUtils.hasText(value) ? value : fallback;
    }

    /** Parses an int from the payload value, falling back when absent or unparsable. */
    private int intVal(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /** Elapsed wall time in ms since the given start stamp, for observation timing. */
    private long elapsedMs(long startedNs) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
    }

    /** Minimal properties for the test constructor: given root, writes and shell enabled. */
    private static AgentHarnessProperties defaultProperties(Path workspaceRoot) {
        AgentHarnessProperties properties = new AgentHarnessProperties();
        properties.getWorkspace().setRoot(workspaceRoot.toString());
        properties.getWorkspace().setWriteEnabled(true);
        properties.getWorkspace().setShellEnabled(true);
        return properties;
    }

    /** Captured bytes of one process stream plus whether they were truncated to the cap. */
    private record ProcessOutput(byte[] bytes, boolean truncated) {
    }
}
