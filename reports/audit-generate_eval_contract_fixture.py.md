# 审查报告：scripts/generate_eval_contract_fixture.py

- **审查日期**：2026-11-04
- **审查方式**：只读通读 + 静态交叉审查（未执行脚本、未改动任何源码；对数据集做了只读统计核实，结论全部以下钻协作者源码/数据为据）
- **审查对象**：`scripts/generate_eval_contract_fixture.py`（55 行，evaluator-contract 契约校验的夹具生成器——`.github/workflows/ci.yml:55-68` 与 `nightly-regression.yml:22-27` 两个 CI job 的第一步）
- **协作者清单**（均已逐一核对）：
  - 消费方 `run_regression.py` — `normalize_prediction`（:44-69）字段契约、`evaluate_case` 评分链（:72-120）、`summarize` 门槛算术（:123-197）、`render_markdown`（:200-249）；`prediction_source` 全文件仅 :62（归一保留）与 :286（live 校验）两处出现，**报告渲染层从不展示**
  - 数据集 `evaluation/dataset.large.json`（实测 240 条裸数组）：`expected_keywords` 240/240、`forbidden_keywords` 240/240、**`expected_citations` 0/240**、id 缺失 0/240；类别分布 rag_recall/rag_multi_hop/tool_routing/hallucination_guard/cross_kb 各 48；expected∩forbidden 无重叠（精确匹配与子串两个方向均验证为 0）
  - 上游生成器 `generate_eval_dataset.py` — 输出同为裸数组 + snake_case 字段（nightly job 先重生数据集再跑本脚本，schema 已对上）
  - CI 接线：`ci.yml` evaluator-contract job 用 `--first-token-max-ms 1500`（夹具最大 first_token 实测值 299ms，余量充足）；`nightly-regression.yml` 仅传 `--threshold 0.75`（其余门槛走默认值）
  - `operations.md:60-68` — 本脚本定位为"只校验评估器契约，不是模型质量证据"；真实质量证据链是 `eval_live_runner.py` + `--require-live-predictions`
  - 姊妹脚本：`eval_demo.py`（dict 包装兼容）、`eval_live_runner.py`（不容错）——同族输入归一行为漂移
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、未变更任何对外 API 签名
- **关联报告**：`audit-eval_live_runner.py.md`、`audit-eval_demo.py.md`、`audit-e2e_chat_flow.py.md`

## 概述

先说做对的部分——作为 55 行的确定性生成器，它的"正面契约"做得很扎实：

1. **输出 schema 与消费方逐字段对齐**：id/prediction_source/status/answer/citations/first_token_latency_ms/total_latency_ms 与 `run_regression.py::normalize_prediction`（:44-69）完全匹配，无缺字段、无类型偏差。
2. **`prediction_source="synthetic_contract_fixture"` 边界清晰**（:40）：与 `live_api` 区分开，`--require-live-predictions`（`run_regression.py:283-289`）能据此拒绝夹具冒充真实质量证据——live/contract 的隔离在数据层成立。
3. **真确定性**：延迟值由 `index % n` 推导（:35-36），无时间、无随机，nightly 可重复跑且产物 diff 稳定。
4. **延迟值与 CI 门槛匹配**：max first_token 299ms < CI 的 1500ms 阈值，不会误触延迟门槛。
5. **字段名与整条数据链一致**：静态 `dataset.large.json` 与 nightly 上游 `generate_eval_dataset.py` 的输出均为 snake_case（expected_keywords/forbidden_keywords/id/category），:23,:26 的取值键全部命中。
6. **is_rag 判定与消费方镜像一致**（:27 vs `run_regression.py:87` 同为 `startswith("rag")`）：rag 类案例给 citation、非 rag 类不给，恰好对上消费方 `citation_required` 的推导——两处重复逻辑目前是同步的（漂移风险见 M1）。
7. 数据事实核实：当前数据下夹具输出必然全通过（关键词全命中、无 forbidden 误触、citation 恒存在）——这本身是设计使然，但它引出本报告的头号发现。

## 高严重度

### H1. 单侧契约测试：夹具只生产"全通过"数据，评估器 fail 方向的契约从未被校验

- **位置**：`scripts/generate_eval_contract_fixture.py:24`（answer = 关键词拼接 → 关键词分恒 1.0）、:28-33（citations 恒命中）、:41（status 恒 `"ok"`）、:35-36（延迟恒达标）
- **描述**：contract job 的存在意义是"防评估器契约回归"（`operations.md:60-68`），但夹具让 `run_regression.py` 的全部失败路径零覆盖：`hallucination_penalty`（:83，forbidden 命中时归零）、citation 未命中分支（:89-94）、空答案判定（:97-98）、`status` 非 ok 判定（:96）、`score < threshold`（:100）、以及 `summarize` 里真正拦截发布的失败侧算术（:143-169 的 hallucination_rate/failure_rate 分子分母）。这些恰恰是评估器最容易改坏的方向。
- **触发条件**：任何人在 `run_regression.py` 改坏失败方向逻辑——例如把 `hallucination_penalty` 误写成加项、把 `citation_hit` 缺省 True 改 False、把 `failure_rate` 分母换成 `totalSafe` 之外的值、把 `gate_passed = all(checks.values())` 改成 `any`。evaluator-contract job 与 nightly job 照样全绿。
- **影响**：两个 CI job 给出"评估器契约已验证"的假象，实际只验证了"管道连通 + 及格侧算术正确"。契约测试最忌单侧：门只在放行方向被测试过。
- **修复建议**：新增 `--inject-failures N`（默认 0，保持现有 CI 行为不变——对外行为兼容），开启时按确定性序号注入四类失败样本：①缺一半关键词（score 过线）与②缺全部关键词（score 不过线）、③answer 含 forbidden（hallucination 命中）、④status=error、⑤first_token 超过 `--fail-latency-ms`（可默认 1500 对齐 CI）；同时在夹具旁输出期望的 gate_checks JSON 供 CI 步骤断言比对。全部改动在本脚本内，`run_regression.py` 无需改动。

## 中严重度

### M1. 伪造 citation 的假路径 + citation 打分的两个分支在本数据集上双双零覆盖

- **位置**：`scripts/generate_eval_contract_fixture.py:28-33`
- **描述**：两层问题。其一，`dataset.large.json` 实测 **240 条全部没有 `expected_citations`**：:28-29 的"复制 expected_citations"分支在本数据上是死代码；96 条 rag 案例走 :30-31 的伪造分支（`source=contract/{id}.md, chunk=1`），消费方对此类案例的判定是"仅存在即命中"（`run_regression.py:94` 的 `len(citations) > 0`）——**token 匹配分支（:92 的 `all(token in citation_pool)`）在整个 contract job 上从不执行**。其二，伪造的 `contract/{id}.md` 是不存在的路径，混在产物里对读夹具的人有误导性。
- **触发条件**：任何人改坏 `to_lower_set` 或 citation token 匹配逻辑（:91-92）——契约 job 不报警。
- **影响**：契约 job 对 citation 打分的实际校验强度远低于表面印象；与 H1 同属"校验覆盖面虚高"。
- **修复建议**：并入 H1 的注入机制——为少量 rag 案例写死 `expected_citations`（夹具 citations 精确包含 token，命中样本；注入模式下再放一条不包含的未命中样本）。顺带把伪造路径改成明确的占位标记（如 `synthetic://contract-fixture/{id}`），避免像真实证据。

### M2. 契约产物与真实回归报告在产出物层面不可区分，防误用只靠源码里的 docstring

- **位置**：`scripts/generate_eval_contract_fixture.py:4-5`（docstring 警告"绝不能当模型质量证据"）与 :48-50（产出无任何水印）；消费方证据：`run_regression.py::render_markdown`（:200-249）与报告 JSON 均不展示 `prediction_source`（grep 证实全文件仅 :62、:286 两处出现，渲染层为零）
- **描述**：夹具跑出的报告与 live 报告同标题（"Regression Report"）、同结构、同文件名模式（`latest.json`/`latest.md`）。防误用的三道防线全部在流程侧：docstring、`operations.md` 的口头约定、live 链路要记得加 `--require-live-predictions`。任何一道被忘掉（例如有人拿夹具复现回归问题后忘了换回真实预测文件），就得到一份 100% pass、看起来与真报告一模一样的"质量证据"。
- **触发条件**：`run_regression.py --predictions evaluation/predictions.contract.json` 未加 `--require-live-predictions`。
- **影响**：与 `audit-eval_live_runner.py.md` H2 同族的假绿灯风险，只是这里源头是合成数据而非降级回答。
- **修复建议**：脚本侧——stdout 打印显著警告（"此文件仅供 evaluator-contract 校验，禁止作为质量证据"）并在预测文件首条记录保留 `prediction_source`（已具备）；根治在 `run_regression.py` 渲染层为报告加一行 `Prediction Source` 分布（超出本脚本范围，仅指出方向，本次不改动）。

## 低严重度

### L1. 数据集形态不容错：dict 包装 → 迭代出字符串键 → AttributeError

- **位置**：`scripts/generate_eval_contract_fixture.py:19-23`
- **描述**：`json.loads` 后直接 `enumerate(dataset)`，若数据集是 `{"cases": [...]}` 包装则迭代出字符串键，`case.get` 抛 `AttributeError`。同族漂移：`eval_demo.py:51-55` 兼容包装与非 dict 条目，`eval_live_runner.py` 与本脚本都不兼容——同一 `evaluation/` 目录三个脚本三种行为。camelCase 键同理（eval_demo 兼容 `expectedKeywords`，本脚本只认 snake_case）。
- **触发条件**：数据集改用包装形态，或条目混入非对象。
- **影响**：裸 traceback；CI 上报错信息不指向真实原因。
- **修复建议**：3 行归一（dict 取 `cases`、过滤非 dict 并计数告警），或抽公共模块供三脚本共用。

### L2. id 兜底值两处不一致：citation 里兜成序号，prediction id 里兜成 None

- **位置**：`scripts/generate_eval_contract_fixture.py:31`（`case.get('id', index)`）与 :39（`case.get("id")` → None）
- **描述**：id 缺失时 :39 产出 `None`，消费方 `pred_map` 键变成 `"None"`（`str(None)`），而 dataset 侧键是 `""`（`run_regression.py:104` 的 `case.get("id", "")`）——全部错配为 `missing`，门槛以 failure_rate 报警，但根因（数据缺 id）不可见。当前数据 id 全存在（实测 0/240 缺失），纯潜伏。
- **触发条件**：上游生成器或手工数据漏 id / id 为 null。
- **影响**：轻微但排障绕远。
- **修复建议**：两处统一兜底为 `f"case-{index:03d}"`，或 id 缺失时直接 `SystemExit`（标注集缺 id 宁可拒绝）。

### L3. 空数据集静默产出空夹具，退出码 0

- **位置**：`scripts/generate_eval_contract_fixture.py:20,48-50`
- **描述**：数据集为 `[]` 时写出空数组、打印 `0 records`、正常退出。下游 `run_regression.py` 对 total=0 的处理是 `gate_passed=False`（:125-141）——能拦住，但失败原因显示为"门槛不过"，与"夹具生成端数据为空"的真实原因相去甚远。
- **触发条件**：nightly 的 `generate_eval_dataset.py` 行为变化（如输出键改名导致本脚本读空）后残留/产出空文件。
- **影响**：CI 失败归因被带偏。
- **修复建议**：`len(predictions) == 0` 时 `SystemExit("no cases loaded from <path>")`，把错误拦在生成端。

## 修复优先级建议

1. **H1 + M1**（同一改动：`--inject-failures` 注入失败样本 + 真实 expected_citations 样本 + 期望 gate_checks 断言）——这是 contract job 存在意义的补全，收益最大；CI 侧加一步断言即可，`run_regression.py` 无需改动。
2. **M2**——脚本侧警告先行；报告渲染层的 `Prediction Source` 展示建议在 `run_regression.py` 的审查/修改中跟进。
3. **L1-L3**——随任一次顺手修。

以上修复建议均不要求变更任何对外 API 签名；H1/M1/M2（脚本侧）/L1-L3 全部可仅在 `scripts/generate_eval_contract_fixture.py` 内完成（M2 的根治项与 H1 的 CI 断言步骤属可选配套，分别落在 `run_regression.py` 与 workflow 文件，本次均未改动）。
