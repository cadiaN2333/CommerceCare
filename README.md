# CommerceCare 电商客服系统

CommerceCare 是一个从零搭建的 Java 电商客服学习项目。目标是基于 Adaptive-RAG 思路，根据问题复杂度选择检索策略，逐步加入多跳检索、业务工具、多智能体协作、证据检查、人工接管、评测和链路追踪。

完整路线见 [技术方案与项目路线](技术方案与项目路线.md)。

扩展评测一键入口：在后端启动后执行 `.\scripts\run-expanded-evaluation.ps1`，默认导入 60 份虚构知识并运行开发集三路检索。参数、指标口径和保留验证集说明见 [扩展评测说明](datasets/evaluation/expanded-v1/README.md)。

型号识别一键入口：执行 `.\scripts\evaluate-model-recognition.ps1`，测试独立预览接口的43条回归用例和5条参数校验。范围与实测见[型号识别验证报告](datasets/evaluation/model-recognition-v1/验证报告.md)。代码更新后先重启后端，默认端口8081。

## 技术栈

- Java 21、Maven 3.9+、Spring Boot 3.5.16
- Spring MVC、Spring Data JDBC、Flyway、Actuator
- PostgreSQL 16 + pgvector 0.8.6
- Spring AI 1.1.2 与 Spring AI Alibaba 1.1.2.2 BOM 版本管理
- DeepSeek Chat、DashScope text-embedding-v4（1024维）
- Apache Lucene 10.5.2 BM25、RRF混合检索

## 项目结构

```text
pom.xml                                      # Maven 根聚合工程
.run/CommerceCare.run.xml                    # IDEA 共享启动配置
compose.yaml                                 # PostgreSQL + pgvector
.env.example                                 # 本地数据库设置样例
backend/pom.xml                              # Spring Boot 应用子模块
backend/src/main/java/com/lzq/commercecare/
├── CommerceCareApplication.java
├── system/                                  # 健康与系统信息
├── knowledge/                               # 入库、三路检索与型号过滤
├── assistant/                               # 带来源聊天与证据不足兜底
└── routing/                                 # 完整型号识别预览与澄清状态
backend/src/main/resources/
├── application.yml
└── db/migration/V1__enable_pgvector.sql
datasets/
├── knowledge/                               # 演示售后知识
└── evaluation/                              # 检索与型号识别开发问题集
scripts/                                     # 一键离线评测
docs/                                        # 完整代码教程与阶段设计
```

## 启动数据库

项目根目录已有本机生成的 `.env`。新克隆仓库时，先复制样例并修改本地密码；不要提交 `.env`：

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
notepad .env
docker compose up -d postgres
docker compose ps
```

等 `postgres` 状态为 `healthy` 后，检查 pgvector 扩展：

```powershell
docker exec commercecare-postgres psql -U commercecare -d commercecare -c "SELECT extversion FROM pg_extension WHERE extname='vector';"
```

本机数据库地址为 `jdbc:postgresql://localhost:5433/commercecare`。Spring Boot 从项目根目录的 `.env` 读取用户名、端口和密码；Flyway 启动时执行 `V1__enable_pgvector.sql` 并启用 `vector` 扩展。

Embedding与VectorStore现已接入：DeepSeek Chat负责生成，DashScope text-embedding-v4生成1024维向量，pgvector持久化片段。启动前配置本地DEEPSEEK_API_KEY、DASHSCOPE_API_KEY及数据库信息，保持.env不提交。型号识别逻辑自身不调用模型，但整个应用仍按现有配置初始化模型适配器。

## 在 IntelliJ IDEA 中启动

在 IDEA 中打开 `D:\电商客服Agent` 根目录。Maven 工具窗口若尚未识别项目，右键根目录 `pom.xml` 并选择 **Add as Maven Project**，然后重新加载 Maven。将 Project SDK、Maven Importer 和 Maven Runner 设为 JDK 21。

重新加载后，在右上角运行配置中选择共享配置 **CommerceCare** 并启动。

## 在 PowerShell 中启动

在项目根目录执行：

```powershell
mvn spring-boot:run
```

默认 HTTP 端口为 8081。

## 接口验证

另开一个 PowerShell 窗口执行：

```powershell
Invoke-RestMethod "http://localhost:8081/actuator/health" | ConvertTo-Json
Invoke-RestMethod "http://localhost:8081/api/v1/system/info" | ConvertTo-Json
```

健康检查返回 `UP`；系统信息接口返回应用名和 Java 运行版本。

## 构建

在项目根目录执行 `mvn verify`。可运行 JAR 位于 `backend/target/commercecare-0.0.1-SNAPSHOT.jar`。

## 验证记录

2026-10-04验证初始后端、数据库与迁移。2026-10-08已验证型号过滤的三路开发集对照；新增独立型号识别预览接口后，`mvn -f backend/pom.xml verify`通过26项单元测试，43条识别HTTP回归与5项参数校验通过。型号识别尚未接入聊天，不宣称已完成Adaptive-RAG或多Agent系统。

根目录 `.gitignore` 忽略 IDEA 配置、Maven 构建产物、日志、本地环境配置、证书私钥、运行时数据和本地模型权重。
