# 评测数据说明

`faq-cases.csv` 是 CommerceCare 第一版的种子问题集，用于比较固定 RAG、混合检索、自适应路由和多跳处理。

对应的售后知识位于 `../knowledge/return-and-warranty.md`。所有政策和订单都是项目演示用虚构数据，不代表真实商家规则。

`order-fixtures.json` 固定了评测日期和订单事实。运行时间必须使用 `reference_date`，避免订单天数随真实日期变化而改变。

| 字段 | 含义 |
|---|---|
| `case_id` | 用例编号 |
| `question` | 固定问题输入 |
| `intent` | 业务意图标签 |
| `complexity` | 知识问题的单跳/多跳复杂度 |
| `expected_strategy` | 预期路由策略 |
| `expected_source_ids` | 预期知识来源文件，多项使用 `|` 分隔 |
| `expected_tools` | 预期调用的业务工具，多项使用 `|` 分隔 |
| `test_user_id`、`test_fixture_id` | 固定的认证用户和模拟订单 |
| `reference_date` | 计算签收天数时使用的固定日期 |
| `answerable`、`expected_answer_points` | 是否有依据回答，以及核对答案时应包含的事实 |

当前只有 10 条种子用例，用来打通数据格式和调用路径，不用于宣称统计显著的效果提升。后续需要按 FAQ、长尾、多跳、无答案和工具失败等类别补充，并将调参集与最终留出的评测集分开。

多来源 TopK 来源召回基准见 [多来源检索基准说明.md](多来源检索基准说明.md) 和 multi-source-retrieval-cases.json。
多来源检索首轮结果见 [多来源检索评测报告.md](多来源检索评测报告.md)。

RRF 算法边界与三路同集对照见 [RRF混合检索验证报告.md](RRF混合检索验证报告.md)。
