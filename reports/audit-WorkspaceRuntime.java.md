# 缺陷审查报告：WorkspaceRuntime.java

- **审查对象**：`src/main/java/com/enterprise/iqk/agent/harness/WorkspaceRuntime.java`（499 行，全文通读）
- **审查方式**：只读静态审查；直接协作者已逐一核实——`AgentHarnessProperties`（含 Workspace 默认值）、`UnifiedDiffService`（全文）、`AgentAction` record、`AgentObservation`、`HarnessEventRecorder.shouldRecord`；并用 grep 确认 `new WorkspaceRuntime(Path)` 便捷构造器仅测试引用
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件
- **关联报告**：`reports/audit-WorkflowReactAgentService.java.md`、`reports/audit-ReactAgentService.java.md`

---

## 概览

该类是 agent harness 的文件系统/shell 执行面，实现 6 个动作（list/read/search/propose/apply/run_shell），带词法路径围栏、写与 shell 的配置开关（默认均 **false**）、命令白名单、git flag 黑名单、mvn -D 前缀白名单。整体防御思路清晰且有注释说明威胁模型（`--pre`、`--output`、`-DargLine` 均有针对性防线），但仍存在 **3 个 shell 面沙箱绕过/资源缺陷**（前提：`shellEnabled=true`）与若干中低问题。所有发现均不需变更 `AgentRuntime` 接口签名即可修复。

---

## 高严重度

> H-1/H-2/H-3 的共同前提是 `app.agent-harness.workspace.shell-enabled=true`（默认 false）。一旦生产环境开启 shell 执行，以下均为 LLM agent 经 `workspace_run_shell` 直接可达。

### H-1 `rg --config` 绕过 flag 黑名单，恢复任意命令执行

- **位置**：`WorkspaceRuntime.java:430-459`（`argsWithinWorkspace` 的选项黑名单：仅拦 `--pre*`、`--hostname-bin`、`--regexp-file`）、`:351-356`（rg 分支）
- **描述**：黑名单拦截了 ripgrep 的 `--pre`（每文件前置 shell 命令），但 **`--config <file>` 未被拦截**。`rg --config=/host/path/x.conf pattern` 允许从宿主任意路径加载 rg 配置文件，而 rg 配置文件的语法就是命令行参数——其中可以设置 `--pre`、`--pre-glob` 等被黑名单挡掉的选项。这构成"从配置文件侧绕过 `--pre` 防线"的完整链：被拒的 `rg --pre cmd` 换成 `rg --config=/tmp/x.conf` 即可执行任意 shell 命令（以服务进程身份）。同族未拦截项：`--files-from <file>`（从宿主文件读文件列表）、`--hostname-bin` 的等价物经由配置同样可达。
- **触发条件**：`shellEnabled=true` + agent 调用 `workspace_run_shell`，command 为 `rg --config=<宿主路径> ...`。flag 的值不会经过非 flag 路径校验（`argsWithinWorkspace` 只校验不以 `-` 开头的位置参数）。
- **影响**：沙箱内任意命令执行；代码注释明示的威胁模型（防 `--pre`）被旁路。
- **修复建议**：将 rg 从黑名单改为**白名单**（仅放行 `-i/-n/-l/-c/--json/--max-count/-t/-g` 等枚举选项）；至少把 `--config`、`--files-from`、`--flags-file` 等一切"从文件系统加载内容"的选项加入黑名单。

### H-2 `git diff --no-index` 读取宿主任意文件并回显给 agent

- **位置**：`WorkspaceRuntime.java:357-361`（git 分支：校验子命令白名单 + `argsAreSafeGitFlags`）、`:419-423`（`argsAreSafeGitFlags` 只检查以 `-` 开头的 flag，**非 flag 参数不做路径校验**——`argsWithinWorkspace` 仅用于 ls/rg）
- **描述**：git 子命令白名单含 `diff`，而 `git diff --no-index /etc/passwd /dev/null` 可在仓库外对宿主任意可读文件做 diff，结果经 stdout 回传给 agent。`argsAreSafeGitFlags` 的黑名单（`--output`/`--exec`/`--upload-pack` 等）不包含 `--no-index`，其后的宿主路径是非 flag 参数，两层校验都放行。同族：`git log --find-object=<sha>`（有限）、`git grep` 未在白名单（安全）。
- **触发条件**：`shellEnabled=true` + `workspace_run_shell` 的 `git diff --no-index <宿主路径> ...`。
- **影响**：工作区沙箱外的任意文件读取（配置、密钥文件），内容直接进入模型上下文与观测记录。
- **修复建议**：git 分支对非 flag 参数也做 `argsWithinWorkspace` 校验，并显式拒绝 `--no-index`、`--git-dir`、`--work-tree`、`-c <key>=<value>`（config 内联可设 fsmonitor，见 M-1）。

### H-3 子进程输出先全量缓冲再截断，可造成堆耗尽（DoS）

- **位置**：`WorkspaceRuntime.java:317-331`（`readProcessOutput`：`input.transferTo(buffer)` 读完**整个**流后才按 `maxCommandOutputBytes`（默认 12KB）截断）、`:255-266`（两个 `supplyAsync` 读取器）
- **描述**：截断是事后式的：读取器把 stdout/stderr 全部读进内存，超时窗口（上限 30s，`:250`）内 `git log -p`、大结果 `rg`、`mvn` 详细输出可轻松产生数百 MB 甚至 GB 级数据，在截断发生前堆已被打满。超时分支（`:260-262`）不 join 两个 future，`destroyForcibly`（finally，`:277`）杀进程前输出仍在无界累积。
- **触发条件**：`shellEnabled=true` + 允许列表内命令产生超大输出（无需任何越权，一条 `git log -p --stat` 即可）。
- **影响**：服务进程 OOM / 长时间 Full GC，波及全部请求——这是并发服务里的拒绝服务面。
- **修复建议**：读取端限幅——`input.readNBytes(maxOutputBytes + 1)`，超出即标记 `truncated=true` 并继续消费（丢弃）剩余字节以让进程正常退出；或用 `ProcessBuilder.redirectReadTo(...)`/管道限量读取。

---

## 中严重度

### M-1 `applyPatch` 可写 `.git/` 等敏感路径，形成"受限写 → 任意命令执行"升级链

- **位置**：`WorkspaceRuntime.java:199-239`（applyPatch 对 root 下任意路径写入，仅 `resolvePath` 词法校验；`Files.createDirectories`（:230）还会新建任意子目录）
- **描述**：`workspace_apply_patch` 未排除 `.git/` 目录。开启 `writeEnabled` 后，agent 可覆写 `.git/config` 设置 `core.fsmonitor=<任意命令>`，随后白名单内的 `git status`（FsMonitor 机制会自动执行 fsmonitor 钩子命令）即以服务进程身份执行该命令——从"写工作区文件"升级为"任意命令执行"，绕过 `runCommand` 的整套白名单。同族：覆写 `.git/hooks/*`（白名单子命令不触发大部分 hook，但 fsmonitor 是确定性的）。
- **触发条件**：`writeEnabled=true`（默认 false）+ agent 两步调用：apply_patch 写 `.git/config` → run_shell `git status`。
- **影响**：沙箱逃逸升级链；即使不开 shell，`.git` 破坏也会污染仓库完整性。
- **修复建议**：applyPatch（与 proposePatch）拒绝 `.git/`、`.svn/` 等元数据目录（resolvePath 后追加目录段检查）；写文件用临时文件 + `ATOMIC_MOVE`（同时解决中断/磁盘满时 `TRUNCATE_EXISTING` 已截断原文件的丢数据问题，`:232-233`）。

### M-2 路径围栏只做词法归一化，符号链接可逃逸（读与写双向）

- **位置**：`WorkspaceRuntime.java:295-301`（`resolvePath` 用 `normalize()` + `startsWith`，不解析符号链接）；`Files.readString/writeString/readAllLines` 默认跟随 symlink
- **描述**：`workspaceRoot.resolve(raw).normalize()` 挡住了 `..` 词法穿越，但工作区内若存在指向宿主的符号链接：`workspace_read_file` 会读取链接目标（宿主任意文件），`applyPatch` 的 `Files.writeString` 会**穿透链接写到宿主路径**，`searchText` 的 `Files.readAllLines`（`:142`，`isRegularFile` 对 symlink→regular 返回 true）同样跟随。`Files.walk`（listFiles/searchText 枚举）默认 NOFOLLOW，能挡住目录枚举逃逸，但挡不住对"已存在链接"的直接寻址。
- **触发条件**：工作区内出现恶意/误置 symlink。默认配置下 LLM agent 缺少直接创建 symlink 的途径（git 白名单无 checkout、applyPatch 只写普通文件），属防御纵深缺口而非直接可达；但 `mvn test`（运行工作区代码）或运维误置均可埋入。
- **影响**：配置依赖的宿主任意文件读/写。
- **修复建议**：`resolvePath` 用 `path.toRealPath()` 与 `workspaceRoot.toRealPath()` 比较；读写前对路径各段做 `NOFOLLOW_LINKS` 打开（`Files.newInputStream(..., LinkOption.NOFOLLOW_LINKS)`），拒绝 symlink。

### M-3 子进程继承服务进程完整环境变量

- **位置**：`WorkspaceRuntime.java:251-253`（`ProcessBuilder` 未 `environment().clear()` 或过滤）
- **描述**：允许的命令（尤其 `mvn`，会拉起任意插件代码）可见服务进程全部环境变量——数据库口令、JWT secret、第三方 API Key 等。部分插件/错误信息可能将其回显进 stdout/stderr，进而进入 agent 观测与 SSE 响应。
- **触发条件**：`shellEnabled=true` + 任意白名单命令执行。
- **影响**：机密横向暴露面；与 H-1/H-2 叠加时即为完整泄露链。
- **修复建议**：为子进程构造白名单环境（PATH、HOME 指向工作区、JAVA_HOME、必要代理变量），或至少剥离以 `SECRET/PASSWORD/TOKEN/KEY` 命名的变量。

### M-4 `listFiles` 遇到不可读目录会让整个动作失败

- **位置**：`WorkspaceRuntime.java:98-102`（`Files.walk` 流在迭代中抛 `UncheckedIOException` → `execute` 的 RuntimeException catch 转 error observation）
- **描述**：遍历中任何一个无权限目录（或并发被删的文件）都会中断整次枚举，agent 拿到的是"workspace action failed: ..."而非部分结果。
- **触发条件**：工作区含权限受限/瞬态消失的目录。
- **影响**：可用性问题，agent 无法自愈重试（换目录参数可能继续踩雷）。
- **修复建议**：自定义 `FileVisitor`，在 `visitFileFailed`/`preVisitDirectory` 失败时跳过并计数，返回部分结果。

---

## 低严重度

### L-1 `searchText` 空 query 触发全树无意义扫描

- **位置**：`WorkspaceRuntime.java:122, 148`（`contains("")` 恒 true，直到凑满 maxMatches）
- **修复建议**：query 为空白时直接返回 error observation。

### L-2 `tokenizeCommand` 不支持引号/转义，含空格参数被拆散

- **位置**：`WorkspaceRuntime.java:333-338`（按 `\s+` 切分）
- **描述**：`rg "foo bar" src` 被拆成 4 个 token，模式与位置参数全部错位（带引号的路径会因 `resolve("\"foo")` 校验失败而被拒——安全无碍，纯功能缺陷）。
- **修复建议**：注释明示"仅支持无引号命令"，或实现最小引号解析。

### L-3 `actionInput` 为 null 时 NPE 依赖外层 catch 兜底

- **位置**：`WorkspaceRuntime.java:70-77`（`input.get(...)` 直接解引用）
- **描述**：`AgentAction.actionInput()` 可为 null（record 无约束）；NPE 被 `execute` 的 RuntimeException catch 转成 "workspace action failed: null"，错误信息不可读。
- **修复建议**：`execute` 入口对 null actionInput 归一化为 `Map.of()`。

### L-4 `readFile` 的 `truncated` 判定与 `Files.size` 二次查询存在 TOCTOU

- **位置**：`WorkspaceRuntime.java:113-117`
- **描述**：`truncated` 依据读后另查的 `Files.size`；文件在两步之间变化时判定可能错；`Files.size` 抛 IOException 时整个动作失败（明明内容已读到）。
- **修复建议**：用 `readNBytes(maxBytes + 1)` 的实际读取结果判定截断（读满 maxBytes+1 即 truncated），一次 I/O 完成判定。

### L-5 `searchText` 的 `Files.size` 预检与 `readAllLines` 之间存在 TOCTOU

- **位置**：`WorkspaceRuntime.java:137-142`
- **描述**：1MB 上限检查后文件可能增长；另 `Files.readAllLines` 对二进制文件以 UTF-8 严格解码，依赖 IOException catch 跳过——功能正确但逐文件试探低效。
- **修复建议**：读取端限幅（`readNBytes(1MB+1)`）；可用魔数粗判二进制后跳过。

### L-6 便捷构造器 `WorkspaceRuntime(Path)` 默认打开写与 shell

- **位置**：`WorkspaceRuntime.java:46-48, 489-495`（`defaultProperties` 硬编码 `writeEnabled=true`、`shellEnabled=true`）
- **描述**：当前仅测试引用（grep 确认），但该构造器是 package-private，将来生产代码误用会**静默**绕过默认安全配置（Spring 构造器走配置默认 false）。
- **修复建议**：在 `defaultProperties` 加注释声明"仅供测试"；或让测试显式传属性。

### L-7 root 配置为空白串时静默退化为进程工作目录

- **位置**：`WorkspaceRuntime.java:42-44`（`Path.of(root)`）；`AgentHarnessProperties.Workspace.root` 默认 `"."`
- **描述**：root 配成 `""` 或相对路径时，`toAbsolutePath().normalize()` 落到服务进程 CWD——围栏范围扩大到整个进程工作目录。
- **修复建议**：构造器校验 root 非空白且建议绝对路径，否则抛出启动期异常。

### L-8 `searchText` 不区分二进制/忽略 `.git`

- **位置**：`WorkspaceRuntime.java:126-156`
- **描述**：匹配会命中 `.git` 对象文件（≤1MB 的 pack/loose 对象）与二进制内容的乱码行，浪费匹配额度。
- **修复建议**：候选过滤排除 `.git/`；配合 L-5 的魔数检查。

### L-9 `mvn test` 等价于执行工作区任意代码（设计权衡记录）

- **位置**：`WorkspaceRuntime.java:362-365`（`isAllowedCommand` mvn 分支）、`:380-406`（-D 前缀白名单已正确封堵 argLine/suiteXml 注入，值得肯定）
- **描述**：Maven 生命周期会下载并执行插件字节码、编译并运行测试代码——在 `shellEnabled=true` 下这本身就是"以服务身份运行工作区代码"。-D 白名单封得很好，但这是有意的信任边界而非沙箱边界，建议在配置文档中显式说明：`shell-enabled=true` 的机器应视为可被工作区内容完全接管。
- **修复建议**：文档标注；可选：为 mvn 子进程套独立低权限用户/容器。

---

## 已核实为「非问题」的点

| 疑点 | 结论 |
|---|---|
| `../` 词法路径穿越 | `resolvePath`（:295-301）`normalize()+startsWith` 正确拦截，抛 IllegalArgumentException 后由 `execute` 转 error observation |
| shell 元字符注入（`;`、`&&`、反引号） | `ProcessBuilder` 不经 shell，token 作为字面 argv 传递，无注入 |
| `Files.walk` 经 symlink 目录逃逸 | `Files.walk(start, maxDepth)` 默认 NOFOLLOW，枚举层不逃逸（仅 M-2 所述"直接寻址已存在链接"例外） |
| 写/执行默认态 | `AgentHarnessProperties.Workspace` 默认 `writeEnabled=false`、`shellEnabled=false`——默认配置下本类仅暴露只读动作 |
| `mvn -DargLine` / `-Dsurefire.suiteXmlFiles` 注入 | `SAFE_MVN_PROPERTY_PREFIXES`（:380-406）前缀白名单正确封堵 |
| `git --output` / `--upload-pack` 写文件与命令代执行 | `UNSAFE_GIT_FLAGS`（:415-423）覆盖，`stripOptionValue` 对 `--flag=value` 形式也生效 |
| 超时后僵尸进程 | `finally { destroyForcibly() }`（:274-278）保证回收；`waitFor` 中断时 `execute` 重置中断位（:84-86） |
| `maxFileBytes` 绕过（propose 预览小、apply 直接大） | applyPatch 独立做 content/patch/结果内容三重上限（:208-227），无法借道绕过 |
| 输出读取死锁 | stdout/stderr 异步读取（:255-258），不会因管道满卡死 `waitFor` |
| `ProcessOutput` 读取器异常 | IOException 在读取器内消化为错误文本（:327-329），`join()` 不会抛出导致漏记 |

---

## 修复优先级建议

1. **开启 shell 前必须修**：H-1（rg 白名单化）、H-2（git 参数围栏 + 拒绝 `--no-index`/`-c`）、H-3（读取端限幅）。若短期无法修复，保持 `shell-enabled=false` 即可全部规避。
2. **开启写之前修**：M-1（排除 `.git/` + 原子写）、M-2（realPath 围栏）。
3. **随后**：M-3（子进程环境白名单）、M-4（部分结果容错）；L 项随重构处理。

> 注：所有修复均位于本类私有方法与配置默认值内，`AgentRuntime` 接口（`source/supports/execute`）与构造器对外签名无需变更。
