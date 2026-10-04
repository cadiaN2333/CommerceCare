# CommerceCare 电商客服系统

CommerceCare 是一个从零搭建的 Java 电商客服学习项目。目标是基于 Adaptive-RAG 思路，根据问题复杂度选择检索策略，逐步加入多跳检索、业务工具、多智能体协作、证据检查、人工接管、评测和链路追踪。

当前阶段已提供基础后端代码：Spring Boot 启动入口、系统信息接口和 Actuator 健康检查。完整路线见 [技术方案与项目路线](技术方案与项目路线.md)。

## 技术栈

- Java 21、Maven 3.9+
- Spring Boot 3.5.16
- Spring MVC、Bean Validation、Actuator
- Spring AI 1.1.2 和 Spring AI Alibaba 1.1.2.2 的 BOM 版本管理

BOM 仅管理后续 AI 依赖版本；模型、数据库和 Graph 能力在后续阶段按路线接入。

## 项目结构

```text
backend/
├── pom.xml
└── src/main/
    ├── java/com/lzq/commercecare/
    │   ├── CommerceCareApplication.java
    │   └── system/
    │       ├── SystemController.java
    │       └── SystemInfoResponse.java
    └── resources/
        └── application.yml
```

## 本地启动

在项目根目录执行：

```powershell
Set-Location backend
mvn spring-boot:run
```

默认端口为 8081。

## 接口验证

另开一个 PowerShell 窗口执行：

```powershell
Invoke-RestMethod 'http://localhost:8081/actuator/health' |
    ConvertTo-Json

Invoke-RestMethod 'http://localhost:8081/api/v1/system/info' |
    ConvertTo-Json
```

健康检查返回 `{"status":"UP"}`。系统信息接口返回应用名称与实际运行的 Java 版本：

```json
{
  "application": "commercecare",
  "javaVersion": "以实际 Java 21 运行环境为准"
}
```

## 构建与运行 JAR

```powershell
Set-Location backend
mvn verify
java -jar target/commercecare-0.0.1-SNAPSHOT.jar
```

## 本地文件管理

仓库根目录的 .gitignore 忽略 IDE 配置、Maven 构建产物、日志、本地环境配置、证书私钥、运行时数据和本地模型权重。知识资料、评测数据以及 .env.example 配置样例可以提交。

密钥等本机设置使用环境变量或已忽略的 application-local.yml；Git 中只保留不含真实凭据的配置样例。

## 本阶段验证

2026-10-04 已执行 Maven verify，完成 Java 21 编译和可运行 JAR 打包；随后实际启动 JAR，验证 /actuator/health 返回 UP，/api/v1/system/info 返回 commercecare 与 Java 21 运行版本。接口验证使用临时端口，日常默认端口仍为 8081。

本阶段验证覆盖基础启动和 HTTP 接口。项目当前尚未添加自动化测试；AI BOM 已能解析，模型、检索与 Graph 的运行验证将在对应阶段进行。
