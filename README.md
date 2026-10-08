# CommerceCare 电商客服系统

CommerceCare 是一个从零搭建的 Java 电商客服学习项目。目标是基于 Adaptive-RAG 思路，根据问题复杂度选择检索策略，逐步加入多跳检索、业务工具、多智能体协作、证据检查、人工接管、评测和链路追踪。

完整路线见 [技术方案与项目路线](技术方案与项目路线.md)。

扩展评测一键入口：在后端启动后执行 `.\scripts\run-expanded-evaluation.ps1`，默认导入 60 份虚构知识并运行开发集三路检索。参数、指标口径和保留验证集说明见 [扩展评测说明](datasets/evaluation/expanded-v1/README.md)。

## 技术栈

- Java 21、Maven 3.9+、Spring Boot 3.5.16
- Spring MVC、Spring Data JDBC、Flyway、Actuator
- PostgreSQL 16 + pgvector 0.8.6
- Spring AI 1.1.2 与 Spring AI Alibaba 1.1.2.2 BOM 版本管理

## 项目结构

```text
pom.xml                                      # Maven 根聚合工程
.run/CommerceCare.run.xml                    # IDEA 共享启动配置
compose.yaml                                 # PostgreSQL + pgvector
.env.example                                 # 本地数据库设置样例
backend/pom.xml                              # Spring Boot 应用子模块
backend/src/main/java/com/lzq/commercecare/
├── CommerceCareApplication.java
└── system/
    ├── SystemController.java
    └── SystemInfoResponse.java
backend/src/main/resources/
├── application.yml
└── db/migration/V1__enable_pgvector.sql
datasets/
├── knowledge/                               # 演示售后知识
└── evaluation/                              # FAQ 问题集与模拟订单
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

**Embedding 模型尚未接入。** DeepSeek Chat 负责生成文本，pgvector 写入向量时还需要独立的 Spring AI `EmbeddingModel`。选择并配置该模型后，再加入 `spring-ai-starter-vector-store-pgvector` 并初始化 `vector_store` 表。目前数据库和 pgvector 扩展已就绪，VectorStore Bean 还未创建。

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

2026-10-04 已在根目录验证 `mvn verify`、`mvn spring-boot:run`、数据库连接池、Flyway V1 迁移及 pgvector 0.8.6 扩展。项目当前尚无自动化测试。

根目录 `.gitignore` 忽略 IDEA 配置、Maven 构建产物、日志、本地环境配置、证书私钥、运行时数据和本地模型权重。
