# 架构边界分析：scripts/generate_eval_contract_fixture.py

- **审查日期**：2026-11-04
- **审查方式**：只读静态分析（未改任何源码）；协作者逐一核实；数据结论用 `python3 -I` 对 `evaluation/dataset.large.json` 统计实测支撑
- **协作者清单**：
  - 上游数据：`evaluation/dataset.large.json`（240 条 case，由 `scripts/generate_eval_dataset.py` 生成）；CLI 可替换为任意 JSON 数组文件
  - 下游产物：`evaluation/predictions.contract.json` → `scripts/run_regression.py`（唯一消费者）→ `reports/evaluation-contract/latest.{json,md}`
  - 调用方（全部调用方，grep 证实仅 3 类）：`.github/workflows/ci.yml:52-81`（evaluator-contract job）、`.github/workflows/nightly-regression.yml:24-35`、文档 `docs/operations.md:63-67`、`docs/学习手册/12-评测体系.md:157-165`、`evaluation/README.md:67-79`（教学示例）
  - 标准库依赖：argparse / json / pathlib，零第三方依赖
- **约束遵守**：未改动源码、未 git commit/push、未删除文件、未执行 install/build；本报告为唯一新增文件
- **关联报告**：`reports/audit-generate_eval_contract_fixture.py.md`（同日缺陷审查，本报告的"单侧契约"结论与其互为印证）；同批脚本 arch 报告 `arch-eval_demo.py.md` / `arch-eval_live_runner.py.md` / `arch-e2e_chat_flow.py.md`（平行实现横向对表）

---

## 一、概述

### 先列做对的部分

1. **全库依赖最干净的脚本之一**：出边只有标准库三个模块 + 一个 JSON 文件读取；入边只有两个 workflow 和三份文档。作为 CLI 叶子节点，它在代码层面**零环、零越层、可独立执行**。
2. **输出确定性达标**：无时间戳、延迟按 `index % N` 合成（:35-36）、`ensure_ascii=False, indent=2` 固定序列化——同输入恒同输出，符合契约夹具的第一要求。
3. **自我标识字段在位**：每条记录写 `prediction_source: "synthetic_contract_fixture"`（:40），是三条预测生产线中 2/3 具备自我标识的一条（`eval_live_runner.py:189` 写 `live_api`；`eval_demo.py` 完全不写，归一化后为 `unknown`）。
4. **红线三重声明**：docstring（:2-5）、`evaluation/README.md:67-69`、`docs/operations.md:67` 三处一致声明"产物绝不是模型质量证据"，且两个 CI 调用方都**正确地没有**传 `--require-live-predictions`（契约 job 本就不该要求 live）。
5. **不碰 question/chatId**：夹具不发起任何会话，天然规避了 live 链路的 chatId 跨 run 污染问题（该问题在 eval_demo/EvaluationService 侧均已记档）。
6. **输出文件不入库**：`evaluation/predictions.contract.json` 仅在 CI 内生成，仓库中不存在陈旧副本（`evaluation/` 目录实测只有 `predictions.demo-paths.json` / `predictions.sample.json`）。

### 总体定性

该脚本是**契约自测链的合成数据生产端**：`generate_eval_dataset.py → dataset.large.json → 本脚本 → run_regression.py → CI 契约 job`。它的架构地位特殊——它是全仓库唯一"以通过评估器为目的而生产数据"的组件，因此**它和评估器之间不是普通的供需关系，而是同义反复（tautology）关系**。本文的核心结论是：这个同义反复在设计上是被文档承认的（contract test 本该如此），但它目前的覆盖范围只保护了评估器的**管道层**（参数、文件格式、汇总算术），对评估器的**语义层**（fail 方向、引用 token 匹配、门槛聚合）覆盖为零——且数据实测表明，这种零覆盖不只是数据偏向问题，而是**结构性不可达**（见 §三）。

---

## 二、依赖全景

| 方向 | 目标 | 边的性质 | 证据 |
| --- | --- | --- | --- |
| 出边（读） | `evaluation/dataset.large.json`（默认，:15） | 数据契约：读取 `id` / `category` / `expected_keywords` / `expected_citations` 四键 | :22-31；实测 240 条 keys 恰为 `{id, category, chatId, question, expected_keywords, forbidden_keywords}`，**无 `expected_citations` 键** |
| 出边（写） | `evaluation/predictions.contract.json`（默认，:16） | 输出契约：8 字段 schema 的 7 字段（缺 `error`，评估器 `run_regression.py:66` 有默认值兜底，合法） | :38-46 |
| 出边（隐式） | `run_regression.py` 的**匹配语义** | 语义耦合：`answer = " ".join(keywords)`（:24）之所以能得满分，依赖评估器 `expected_hit` 用**子串包含**判定（`run_regression.py:79`）。若评估器改为分词/全词匹配，夹具可能在语义不变的条件下开始失分——夹具对评估器的耦合不止字段名，深达打分算法 | :24 ↔ run_regression.py:79 |
| 入边 | `ci.yml:59,67` | 显式 CLI 调用，双参数均显式传值 | — |
| 入边 | `nightly-regression.yml:25,28` | 同上 | — |
| 入边 | 文档 ×3 | 教学/运维示例命令 | — |
| 入边 | 无 | 没有任何 Python 代码 import 本脚本（grep 证实），纯 CLI 边界 | — |

**上游生成器关系**：`evaluation/README.md:8` 声明 `dataset.large.json` 为 "Auto-generated"，其唯一事实源是 `scripts/generate_eval_dataset.py`（模板化 5 类 case × 240）。因此本脚本处于一条**两级生成的数据流水线**中段，dataset 的 schema 漂移会先经过生成器、再传导到本脚本、最终在评估器暴露。

---

## 三、循环依赖评估

**代码层面：无环。** 本脚本不 import 仓库任何模块，是严格的叶子。不存在 import 环、Bean 环或包级环的讨论对象。

**语义层面存在一个被设计出来的"自确认环"，需要如实记录其边界**：

```
dataset.large.json ──读 expected_*──▶ 本脚本（夹具）──写 predictions──▶ run_regression.py（评估器）
        ▲                                                                      │
        └──────────────────────读 expected_* + forbidden_*──────────────────────┘
```

夹具与评估器读**同一份数据集的同一批字段**，夹具按构造保证通过。这个环本身是 contract test 的本意（用已知答案验证评估器的算术管道），**不是缺陷**；缺陷在于环上被验证的内容范围。逐项实测（`python3 -I` 统计）：

| 评估器检查项 | 夹具侧构造 | 实测结论 |
| --- | --- | --- |
| `expected_score`（:79-82 子串匹配） | answer = 关键词空格拼接（:24） | 每个关键词是自身拼接结果的子串，score 恒 1.0——**恒真式** |
| `hallucination_penalty`（:83） | answer 只含 expected_keywords | 实测 240 条 `forbidden ∩ answer`（子串方向）= **0** 命中，恒 1.0 |
| `citation_hit`（:87-94） | rag 类合成非空 citation（:30-31） | 240/240 无 `expected_citations` 键 → 评估器走 `len(citations)>0` 分支（:94），**token 匹配分支（:90-92）从契约链不可达** |
| `failed`（:96-98） | status 恒 "ok"、answer 恒非空 | 恒通过 |
| 延迟门槛 | 180-299 / 660-768 ms（:35-36） | 对 ci.yml 的 `--first-token-max-ms 1500` 恒通过 |

**因此契约 job 实际保护的是**：argparse 参数传递、JSON 文件读写、`normalize_prediction` 归一化、`summarize` 的比率算术（分母非零路径）、报告落盘与 artifact 上传、以及 `summarize` 的 `total==0 → gate_passed=False` 空数据集保护（:125-141，这条是真覆盖）。**保护不了的是**：hallucination penalty 逻辑回归、citation token 匹配逻辑回归、`gate_checks` 在非零违规率下的聚合行为、空答案/异常 status 路径、`--require-live-predictions` 的拒绝路径（审计报告 H 级结论，此处从架构侧确认其结构性成因）。

**裁定**：无环（代码）；有界自确认环（语义），环的覆盖缺口是"单侧契约测试"问题的架构成因——缺口不在数据偏向，而在**数据集本身不携带能让 fail 方向可达的字段**（`expected_citations` 键整体缺席），改数据或改夹具是二选一的前置。

---

## 四、越层评估

对脚本而言"层"即：数据层（dataset）→ 生产层（本脚本）→ 评估层（run_regression）→ 渲染层（render_markdown / 下游仪表盘）→ 消费层（CI artifact / 人 / generate_eval_studio）。逐层核查结果：

### 越层-1（中）：`prediction_source` 在评估层被整体丢弃，渲染层与消费层结构性无从补救

`run_regression.py:62` 在 `normalize_prediction` 中保留了 `prediction_source`，但 `evaluate_case` 的返回 dict（:103-120）**不含该字段**——即 `prediction_source` 在进入报告之前就被丢弃，不仅 `render_markdown` 不展示，连 `latest.json` 里也没有。本脚本 :40 精心写入的自我标识字段在下游**零存活**（唯一消费点是 `--require-live-predictions` 的校验，:282-290，而两个契约 job 均未启用它）。

后果是跨链不可区分：`reports/evaluation-contract/latest.{json,md}` 与 live 质量报告 `reports/regression/latest.{json,md}` 文件名相同、JSON schema 相同、markdown 版式相同，事后看产物无法分辨哪份是合成数据的报告。三条防线（docstring / README 红线 / flag）全部在流程侧，无一在产物侧——与审计报告的结论闭环，本报告把丢弃点精确定位到 `evaluate_case:103-120`（比"渲染层不展示"更深一层：**修复必须在评估层加字段，渲染层无法自救**）。

### 越层-2（中）：`latest.json` 文件名跨链碰撞，下游仪表盘无来源守卫

`generate_eval_studio.py:152-156` 默认读 `reports/regression/latest.json` 并生成"可附到 releases、evidence packs、portfolio 页面"的质量仪表盘（含 quality band 分级）。契约链产物与它只差一个目录名，且 studio 只读 `summary` 结构（:41-43），对契约报告完全兼容。即：**一次手动传参偏差，合成数字就会穿上 quality band 外衣进入对外证据包**。这是生产层与消费层之间缺少"来源标识贯穿"的第二个结构性后果，与越层-1 同根。

### 越层-3（低）：对评估器匹配语义的深耦合未被声明

`answer = " ".join(keywords)` 的满分性依赖评估器的子串匹配语义（见 §二"出边（隐式）"）。docstring 只声明了"reads expected fields"（字段级耦合），未声明语义级耦合。评估器若将 :79 改为分词匹配（如按空白切 token 后集合求交），`expected_keywords` 中含空格或多词短语的行为会变化，夹具可能静默开始失分——这属于契约夹具的合理代价，但应在 docstring 中记一句"耦合至子串匹配语义"，否则评估器的合法重构会被夹具以"契约失败"的名义误伤。

### 越层-4（记录项）：数据层字段契约五方复制

dataset 六键名（`id/category/chatId/question/expected_keywords/forbidden_keywords`）的知情方共五处：`generate_eval_dataset.py`（生产者）、本脚本（读取 4 键）、`run_regression.py`（读取 6 键）、`eval_demo.py` / `eval_live_runner.py`（各自的 snake↔camel 映射层）。无共享 schema 模块，与 eval_demo 审计确认的"三方无共享 schema 平行漂移面"同型，此处实证扩大到五方。

---

## 五、平行实现

### 平行-1：三个预测生产者共享一套 8 字段 schema，无共享定义

| 生产者 | `prediction_source` | 容器形态 | 与 `normalize_prediction` 的对齐 |
| --- | --- | --- | --- |
| 本脚本 | `"synthetic_contract_fixture"`（:40）✓ | JSON 数组 | 7/8 字段（缺 `error`，有默认值兜底） |
| `eval_live_runner.py:189` | `"live_api"` ✓ | JSON 数组 | 8/8（同批审计已证全对齐） |
| `eval_demo.py` | **不写** → 归一化为 `"unknown"` | dict 包装兼容 | 靠平台 VO 字段对齐 |

schema 只存在于 `run_regression.py:60-69` 的消费端定义，生产端各自手写。`eval_demo` 缺 `prediction_source` 意味着：若有人对 demo 产物误开 `--require-live-predictions`，会被拒（行为正确但属巧合防御）；更重要的是 `unknown` 无法区分"demo 合成物"与"任何未知来源"，自我标识体系 2/3 覆盖。**修复方向**（不改对外签名）：`eval_demo.py` 补一行 `"prediction_source": "eval_demo_synthetic"` 即可拉齐，成本一行。

### 平行-2：夹具内部 id 兜底双写（潜在分叉点）

:31 的 citation 合成用 `case.get('id', index)`（int 兜底），:39 的输出 id 用 `case.get("id")`（None 兜底）。若未来数据集出现无 id 的 case，同一 case 在 citations 与 id 两处得到不同的兜底值，且 None id 经 `normalize_prediction` 后落入 `pred_map["None"]` 键，与数据集侧 `str(case.get("id",""))=""` 查找键错位 → 全部判 missing。当前数据 240/240 有 id 且无重复（实测），分支不可达，记为潜伏不一致。

### 平行-3：默认数据集路径与评估器默认值漂移

本脚本默认 `evaluation/dataset.large.json`（:15），`run_regression.py` 默认 `evaluation/dataset.json`（:256）。两个 CI 调用方均显式传参所以无害，但"文档里抄一半命令手动跑"（README:72-79 的示例就是夹具显式 + 评估器显式，安全；operations.md:63-64 同）之外的场景下，两条默认值指向不同数据集，配对使用会静默产出 id 全部 missing 的报告。属"成对工具默认值不对称"的轻性漂移。

### 平行-4：合成 citation 格式是夹具私有方言（无害，记录性质）

:31 的 `source=contract/{id}.md, chunk=1` 与平台真实 citation 格式（前端 `citationCards.file` 已剥 `.pdf` 等）无任何对应关系。因契约链只检查非空（§三），格式无消费者，暂无害；但若未来给 `dataset.large.json` 补 `expected_citations` 键，评估器将切换到 token 匹配分支（:90-92），届时这条私有方言会立刻变成必须对齐的真实契约——本条是"数据集补键"这一修复路径的隐藏成本提示。

---

## 六、修复优先级建议

全部建议不要求变更任何对外 API 签名（CLI 参数只增不改，评估器仅增字段）：

1. **P1（对应越层-1/2）**：`run_regression.py` 的 `evaluate_case` 返回 dict 增加 `"prediction_source": prediction["prediction_source"]` 一行（纯增字段），`render_markdown` 的 Case Details 表加一列。这是最小改动且同时修复两个越层问题——契约报告与 live 报告在产物层即可区分，`generate_eval_studio` 侧可另行加守卫（发现非 live 来源时在 quality band 旁标注 "synthetic"，或直接拒绝渲染），但评估层加字段是下游一切补救的结构前提。
2. **P1（对应 §三覆盖缺口）**：本脚本增加 `--inject-failures N`（默认 0，保持既有 CI 行为不变），按取模把 N 条 case 改写为 `status: "error"` / 注入 forbidden 关键词 / 清空 citations / 清空 answer，各占四分之一，使 fail 方向的四类语义路径获得覆盖；CI 侧在契约 job 追加一次 `--inject-failures 8` 且断言 `gate_passed=False` 的负向运行（新增一个 step，不改既有 step）。审计报告已提出该方案，本报告从架构侧确认其前置是"或改数据集补 `expected_citations` 键、或接受 citation token 分支继续零覆盖"——注入 forbidden/answer/citation-miss 三类不需要动数据集，token 匹配分支的覆盖则必须动 `generate_eval_dataset.py` 补键（连带触发平行-4 的方言对齐成本）。
3. **P2（平行-1）**：`eval_demo.py` 补 `prediction_source` 字段一行，拉齐三生产者自我标识。
4. **P3（平行-2/3）**：:31 与 :39 的 id 兜底统一为同一表达式；两个脚本的默认 dataset 路径对齐或在 README 的手动命令示例中保持双显式传参（现状已满足，加一行说明即可）。
5. **P3（越层-3）**：docstring 补一句"answer 构造耦合评估器的子串匹配语义（run_regression.py expected_hit 判定）"。

---

## 附：核实方式备忘

- 目标文件 54 行完整读取；`run_regression.py` 333 行完整读取；`generate_eval_dataset.py`、`generate_eval_studio.py`、`eval_live_runner.py:189` 上下文抽样读取
- 全仓库 grep `predictions.contract|generate_eval_contract_fixture` → 命中 ci.yml / nightly-regression.yml / operations.md / 12-评测体系.md / evaluation/README.md / 本脚本，无其他调用方
- 数据统计（`python3 -I`）：dataset.large.json n=240；keys={id, category, chatId, question, expected_keywords, forbidden_keywords}；expected_citations 缺席 240/240；rag 类 96；forbidden_keywords 覆盖 240/240；expected∩forbidden 双方向（精确+子串）命中均 0；id 无缺失无重复
