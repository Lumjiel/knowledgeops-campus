package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceRuntimeTest {

    @TempDir
    Path workspace;

    // ── 既有测试（保持原样） ──────────────────────────────────────

    @Test
    void readsSearchesAndWritesInsideWorkspace() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "hello harness\nsecond line", StandardCharsets.UTF_8);
        WorkspaceRuntime runtime = new WorkspaceRuntime(workspace);

        AgentObservation read = runtime.execute(action("workspace_read_file", Map.of("path", "README.md")));
        AgentObservation search = runtime.execute(action("workspace_search_text",
                Map.of("query", "harness", "path", ".")));
        AgentObservation write = runtime.execute(action("workspace_apply_patch",
                Map.of("path", "notes/result.txt", "content", "done")));

        assertThat(read.toMap()).containsEntry("content", "hello harness\nsecond line");
        assertThat((List<?>) search.toMap().get("matches")).hasSize(1);
        assertThat(write.toMap()).containsEntry("status", "written");
        assertThat(Files.readString(workspace.resolve("notes/result.txt"))).isEqualTo("done");
    }

    @Test
    void rejectsPathsOutsideWorkspace() {
        WorkspaceRuntime runtime = new WorkspaceRuntime(workspace);

        AgentObservation observation = runtime.execute(action("workspace_read_file", Map.of("path", "../outside.txt")));

        assertThat(observation.toMap())
                .containsEntry("status", "error")
                .containsEntry("source", "workspace");
        assertThat((String) observation.toMap().get("message")).contains("path escapes workspace root");
    }

    @Test
    void runsOnlyAllowedCommandFamilies() {
        WorkspaceRuntime runtime = new WorkspaceRuntime(workspace);

        AgentObservation denied = runtime.execute(action("workspace_run_shell", Map.of("command", "rm -rf .")));
        AgentObservation deniedGitPush = runtime.execute(action("workspace_run_shell", Map.of("command", "git push")));
        AgentObservation allowed = runtime.execute(action("workspace_run_shell", Map.of("command", "pwd")));

        assertThat(denied.toMap()).containsEntry("status", "error");
        assertThat(deniedGitPush.toMap()).containsEntry("status", "error");
        assertThat(allowed.toMap()).containsEntry("exitCode", 0);
        String stdout = (String) allowed.toMap().get("stdout");
        assertThat(stdout).contains(workspace.toAbsolutePath().getFileName().toString());
    }

    @Test
    void proposesAndAppliesUnifiedDiffPatch() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "old line", StandardCharsets.UTF_8);
        WorkspaceRuntime runtime = new WorkspaceRuntime(workspace);

        AgentObservation preview = runtime.execute(action("workspace_propose_patch",
                Map.of("path", "README.md", "content", "new line")));
        String patch = String.valueOf(preview.toMap().get("patch"));
        AgentObservation applied = runtime.execute(action("workspace_apply_patch",
                Map.of("path", "README.md", "patch", patch)));

        assertThat(patch).contains("--- a/README.md", "+++ b/README.md");
        assertThat(applied.toMap()).containsEntry("status", "written");
        assertThat(Files.readString(workspace.resolve("README.md"))).isEqualTo("new line");
    }

    // ── action 面与未知动作 ───────────────────────────────────────

    @Test
    void supportsExposesExactlyTheSixWorkspaceActions() {
        WorkspaceRuntime runtime = new WorkspaceRuntime(workspace);

        assertThat(runtime.source()).isEqualTo("workspace");
        for (String action : List.of(
                "workspace_list_files", "workspace_read_file", "workspace_search_text",
                "workspace_propose_patch", "workspace_apply_patch", "workspace_run_shell")) {
            assertThat(runtime.supports(action)).as(action).isTrue();
        }
        assertThat(runtime.supports("workspace_unknown")).isFalse();
        assertThat(runtime.supports("read_file")).isFalse();
    }

    @Test
    void executeReturnsErrorObservationForUnknownAction() {
        WorkspaceRuntime runtime = new WorkspaceRuntime(workspace);

        AgentObservation observation = runtime.execute(action("workspace_unknown", Map.of()));

        assertThat(observation.toMap())
                .containsEntry("status", "error")
                .containsEntry("source", "workspace");
        assertThat((String) observation.toMap().get("message")).contains("unsupported action: workspace_unknown");
    }

    // ── listFiles：深度钳制与目录条目 ─────────────────────────────

    @Test
    void listFilesClampsDepthAndReportsDirectoryEntries() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "a", StandardCharsets.UTF_8);
        Files.createDirectories(workspace.resolve("dir1/nested"));
        Files.writeString(workspace.resolve("dir1/b.txt"), "b", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("dir1/nested/c.txt"), "c", StandardCharsets.UTF_8);
        WorkspaceRuntime runtime = new WorkspaceRuntime(workspace);

        Map<String, Object> depthZero = runtime.execute(
                action("workspace_list_files", Map.of("maxDepth", "-1"))).toMap();
        assertThat((List<?>) depthZero.get("files")).hasSize(1);
        assertThat((Map<?, ?>) ((List<?>) depthZero.get("files")).get(0))
                .containsEntry("path", ".")
                .containsEntry("type", "directory");

        // maxDepth 以字符串传入也会被解析，且超出 5 的上限被钳制
        Map<String, Object> deep = runtime.execute(
                action("workspace_list_files", Map.of("maxDepth", "99"))).toMap();
        List<?> files = (List<?>) deep.get("files");
        assertThat(files).hasSize(6); // 根 + a.txt + dir1 + b.txt + nested + c.txt
        Map<?, ?> nested = files.stream()
                .map(item -> (Map<?, ?>) item)
                .filter(item -> "dir1/nested/c.txt".equals(item.get("path")))
                .findFirst().orElseThrow();
        assertThat(nested).containsEntry("type", "file");
        assertThat(nested).containsEntry("size", 1L);
    }

    // ── readFile：属性上限、字符串 maxBytes、缺失文件 ─────────────

    @Test
    void readFileHonorsPropertyCapAndStringMaxBytes() throws Exception {
        Files.writeString(workspace.resolve("big.txt"), "x".repeat(100), StandardCharsets.UTF_8);
        WorkspaceRuntime runtime = runtimeWith(ws -> ws.setMaxFileBytes(50));

        Map<String, Object> defaultCap = runtime.execute(
                action("workspace_read_file", Map.of("path", "big.txt"))).toMap();
        assertThat(defaultCap).containsEntry("truncated", true);
        assertThat((String) defaultCap.get("content")).hasSize(50);

        // maxBytes 以字符串传入时按 intVal 解析，再与属性上限取小
        Map<String, Object> stringCap = runtime.execute(
                action("workspace_read_file", Map.of("path", "big.txt", "maxBytes", "30"))).toMap();
        assertThat((String) stringCap.get("content")).hasSize(30);
        assertThat(stringCap).containsEntry("truncated", true);
    }

    @Test
    void readFileReturnsErrorForMissingFile() {
        WorkspaceRuntime runtime = new WorkspaceRuntime(workspace);

        Map<String, Object> result = runtime.execute(
                action("workspace_read_file", Map.of("path", "nope.txt"))).toMap();

        assertThat(result).containsEntry("status", "error");
        assertThat((String) result.get("message")).isEqualTo("file not found: nope.txt");
    }

    // ── searchText：maxMatches 钳制与提前停止 ─────────────────────

    @Test
    void searchStopsCollectingAtMaxMatches() throws Exception {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            content.append("line ").append(i).append(" has needle here\n");
        }
        Files.writeString(workspace.resolve("haystack.txt"), content.toString(), StandardCharsets.UTF_8);
        WorkspaceRuntime runtime = new WorkspaceRuntime(workspace);

        Map<String, Object> result = runtime.execute(
                action("workspace_search_text", Map.of("query", "needle", "maxMatches", "2"))).toMap();

        List<?> matches = (List<?>) result.get("matches");
        assertThat(matches).hasSize(2);
        assertThat(matches.stream().map(item -> ((Map<?, ?>) item).get("lineNumber")).toList())
                .containsExactly(1, 2);
        assertThat(matches.stream().map(item -> ((Map<?, ?>) item).get("path")).toList())
                .containsOnly("haystack.txt");
    }

    // ── patch 尺寸上限：propose 三处 / apply 三处 ─────────────────

    @Test
    void proposePatchRejectsOversizedContentPatchAndExistingFile() throws Exception {
        Files.writeString(workspace.resolve("huge.txt"), "y".repeat(30), StandardCharsets.UTF_8);
        WorkspaceRuntime runtime = runtimeWith(ws -> ws.setMaxFileBytes(20));

        AgentObservation contentTooBig = runtime.execute(action("workspace_propose_patch",
                Map.of("path", "out.txt", "content", "a".repeat(21))));
        AgentObservation patchTooBig = runtime.execute(action("workspace_propose_patch",
                Map.of("path", "out.txt", "patch", "b".repeat(21))));
        AgentObservation existingTooBig = runtime.execute(action("workspace_propose_patch",
                Map.of("path", "huge.txt", "content", "")));

        assertThat((String) contentTooBig.toMap().get("message")).isEqualTo("content exceeds maxFileBytes (20)");
        assertThat((String) patchTooBig.toMap().get("message")).isEqualTo("patch exceeds maxFileBytes (20)");
        assertThat((String) existingTooBig.toMap().get("message")).isEqualTo("existing file exceeds maxFileBytes (20)");
        assertThat(Files.exists(workspace.resolve("out.txt"))).isFalse();
    }

    @Test
    void applyPatchRejectsPatchedResultOverCapWithoutWriting() throws Exception {
        // "resulting content" 防线的可达路径：applyPatch 不检查既有文件大小，
        // 大文件 + 手写最小 patch（只改第一行，其余行走 apply 的尾部拷贝）→
        // patch 本身远小于上限，但应用结果超限。
        // 注意 UnifiedDiffService.create 是全量替换式 diff（旧行全 - 新行全 +），
        // 其产物必然大于结果内容，走不到这个分支，所以这里手工构造 patch。
        String oldContent = "aaaa\n" + "0123456789\n".repeat(30); // ~335 字节
        String patch = "@@ -1,1 +1,1 @@\n-aaaa\n+bbbb\n"; // 27 字节
        Files.writeString(workspace.resolve("big.txt"), oldContent, StandardCharsets.UTF_8);
        WorkspaceRuntime runtime = runtimeWith(ws -> ws.setMaxFileBytes(200));

        Map<String, Object> result = runtime.execute(action("workspace_apply_patch",
                Map.of("path", "big.txt", "patch", patch))).toMap();

        assertThat(result).containsEntry("status", "error");
        assertThat((String) result.get("message")).isEqualTo("resulting content exceeds maxFileBytes (200)");
        assertThat(Files.readString(workspace.resolve("big.txt"))).isEqualTo(oldContent);
    }

    @Test
    void applyPatchRejectsOversizedDirectContent() {
        WorkspaceRuntime runtime = runtimeWith(ws -> ws.setMaxFileBytes(20));

        Map<String, Object> result = runtime.execute(action("workspace_apply_patch",
                Map.of("path", "out.txt", "content", "a".repeat(21)))).toMap();

        assertThat(result).containsEntry("status", "error");
        assertThat((String) result.get("message")).isEqualTo("content exceeds maxFileBytes (20)");
        assertThat(Files.exists(workspace.resolve("out.txt"))).isFalse();
    }

    // ── write/shell 开关关闭（@Autowired 装配的出厂默认是 false） ──

    @Test
    void applyPatchIsRefusedWhenWriteDisabledByProperties() {
        WorkspaceRuntime runtime = runtimeWith(ws -> ws.setWriteEnabled(false));

        Map<String, Object> result = runtime.execute(action("workspace_apply_patch",
                Map.of("path", "notes/denied.txt", "content", "nope"))).toMap();

        assertThat(result).containsEntry("status", "error");
        assertThat((String) result.get("message")).isEqualTo("workspace writes are disabled");
        assertThat(Files.exists(workspace.resolve("notes/denied.txt"))).isFalse();
    }

    @Test
    void runShellIsRefusedWhenShellDisabledByProperties() {
        WorkspaceRuntime runtime = runtimeWith(ws -> ws.setShellEnabled(false));

        Map<String, Object> result = runtime.execute(action("workspace_run_shell",
                Map.of("command", "pwd"))).toMap();

        assertThat(result).containsEntry("status", "error");
        assertThat((String) result.get("message")).isEqualTo("workspace shell is disabled");
    }

    // ── run_shell 策略拒绝（在 ProcessBuilder 启动之前返回，确定性强） ──

    @Test
    void runShellPolicyDenialsCoverMvnAgentInjectionGitOutputAndToolPreExec() {
        WorkspaceRuntime runtime = new WorkspaceRuntime(workspace);

        // mvn 白名单：-DargLine 可向测试 JVM 注入 hostile javaagent
        assertThat(shellMessage(runtime, "mvn test -DargLine=-javaagent:/tmp/evil.jar"))
                .isEqualTo("command is not allowed");
        assertThat(shellMessage(runtime, "mvn clean test")).isEqualTo("command is not allowed");
        assertThat(shellMessage(runtime, "mvn test -Dsurefire.suiteXmlFiles=/tmp/evil.xml"))
                .isEqualTo("command is not allowed");
        // git 拒绝名单：--output 可写宿主路径，--upload-pack 可注入命令
        assertThat(shellMessage(runtime, "git log --output=/tmp/evil HEAD"))
                .isEqualTo("command is not allowed");
        assertThat(shellMessage(runtime, "git show --upload-pack=evil HEAD"))
                .isEqualTo("command is not allowed");
        // ls/rg 非旗标参数必须是工作区内路径；rg 的 --pre* 可执行宿主命令
        assertThat(shellMessage(runtime, "ls ../../")).isEqualTo("command is not allowed");
        assertThat(shellMessage(runtime, "rg --pre-glob=*.md needle")).isEqualTo("command is not allowed");
    }

    @Test
    void runShellAllowsInWorkspaceArgumentsForLsAndGitSubcommand() {
        WorkspaceRuntime runtime = new WorkspaceRuntime(workspace);

        assertThat(runtime.execute(action("workspace_run_shell", Map.of("command", "ls"))).toMap())
                .containsEntry("exitCode", 0);
        // git status 通过策略层后真实执行：@TempDir 不是 git 仓库，退出码必为 128
        // （若仍是策略拒绝，这里会拿到 status=error 的 observation）
        assertThat(runtime.execute(action("workspace_run_shell", Map.of("command", "git status"))).toMap())
                .containsEntry("exitCode", 128);
    }

    // ── 命令输出截断 ──────────────────────────────────────────────

    @Test
    void runShellTruncatesStdoutToConfiguredMaxCommandOutputBytes() throws Exception {
        // 三个长文件名保证 ls 输出远超 10 字节
        for (int i = 1; i <= 3; i++) {
            Files.createFile(workspace.resolve("file-with-a-very-long-name-" + i + ".txt"));
        }
        WorkspaceRuntime runtime = runtimeWith(ws -> ws.setMaxCommandOutputBytes(10));

        Map<String, Object> result = runtime.execute(
                action("workspace_run_shell", Map.of("command", "ls"))).toMap();

        assertThat(result).containsEntry("exitCode", 0).containsEntry("truncated", true);
        assertThat((String) result.get("stdout")).hasSize(10);
        assertThat((String) result.get("output")).hasSize(10);
    }

    // ── 构造辅助 ──────────────────────────────────────────────────

    /** @Autowired 构造等价形态：出厂默认属性（write/shell=false）+ 自定义钩子。 */
    private WorkspaceRuntime runtimeWith(Consumer<AgentHarnessProperties.Workspace> customizer) {
        AgentHarnessProperties properties = new AgentHarnessProperties();
        properties.getWorkspace().setRoot(workspace.toString());
        customizer.accept(properties.getWorkspace());
        return new WorkspaceRuntime(properties, new UnifiedDiffService(), workspace);
    }

    private String shellMessage(WorkspaceRuntime runtime, String command) {
        return String.valueOf(runtime.execute(action("workspace_run_shell", Map.of("command", command)))
                .toMap().get("message"));
    }

    private AgentAction action(String action, Map<String, Object> input) {
        return new AgentAction(action, input, "prompt", "tenant", "chat",
                "balanced", "task-1", "step-1", true);
    }
}
