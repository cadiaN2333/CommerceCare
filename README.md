# CommerceCare 电商客服系统

CommerceCare 是一个从零搭建的 Java 电商客服学习项目。目标是基于 Adaptive-RAG 思路，根据问题复杂度选择检索策略，逐步加入多跳检索、业务工具、多智能体协作、证据检查、人工接管、评测和链路追踪。

当前阶段提供基础 Spring Boot 后端：系统信息接口和 Actuator 健康检查。完整路线见 [技术方案与项目路线](技术方案与项目路线.md)。

## 技术栈

- Java 21、Maven 3.9+
- Spring Boot 3.5.16
- Spring MVC、Bean Validation、Actuator
- Spring AI 1.1.2 与 Spring AI Alibaba 1.1.2.2 BOM 版本管理

## 项目结构

```text
pom.xml                                  # Maven 根聚合工程
.run/CommerceCare.run.xml                # IDEA 共享启动配置
backend/pom.xml                          # Spring Boot 应用子模块
backend/src/main/java/com/lzq/commercecare/
├── CommerceCareApplication.java
└── system/
    ├── SystemController.java
    └── SystemInfoResponse.java
backend/src/main/resources/application.yml
```

## 在 IntelliJ IDEA 中打开与启动

在 IDEA 中打开 `D:\电商客服Agent` 根目录。进入 Maven 工具窗口并重新加载项目。如果 Maven 项目列表为空，在 Project 面板右键根目录的 `pom.xml`，选择 **Add as Maven Project**，然后点 Reload All Maven Projects。

在 File → Project Structure → Project 中选择 **JDK 21**。在 Settings → Build, Execution, Deployment → Maven → Importing 和 Runner 中也选择 JDK 21。

重新加载后，从右上角运行配置下拉框选择共享配置 **CommerceCare**，点击绿色运行按钮。该配置执行根 Maven 聚合工程的 `spring-boot:run`，再由 Maven 进入 backend 子模块。

## 在 PowerShell 中启动

在项目根目录 `D:\电商客服Agent` 执行：

```powershell
mvn spring-boot:run
```

如果只想直接运行后端子模块，也可以执行：

```powershell
mvn -f .\backend\pom.xml spring-boot:run
```

默认端口为 8081。

## 接口验证

应用启动后，在另一个 PowerShell 窗口执行：

```powershell
Invoke-RestMethod "http://localhost:8081/actuator/health" | ConvertTo-Json
Invoke-RestMethod "http://localhost:8081/api/v1/system/info" | ConvertTo-Json
```

健康检查返回 `{"status":"UP"}`。系统信息接口返回应用名称和 Java 运行版本。

## 构建

在项目根目录执行：

```powershell
mvn verify
```

生成的可运行 JAR 位于 `backend/target/commercecare-0.0.1-SNAPSHOT.jar`。

## 验证记录

2026-10-04 已从根目录执行 `mvn -B -ntp verify`，反应堆中的 backend 和根聚合工程均构建成功。随后从根目录启动 `mvn -B -ntp spring-boot:run`，真实 JAR 返回健康状态 `UP`，系统信息返回 `commercecare` 和 Java 21。当前还没有自动化测试。

## 本地文件管理

根目录 `.gitignore` 忽略 IDEA 本机配置、Maven 构建产物、日志、本地环境配置、证书私钥、运行时数据和本地模型权重。知识资料、评测数据和 `.env.example` 样例可以提交。
