# 文档索引

## 1. 定位

1. 本目录是 `03helper` 的长期上下文包，让新对话快速判断“项目是什么、主链在哪里、如何验证、哪些边界不能碰”。
2. 文档只记录稳定事实、长期规则、阶段方案和可追溯结果；一次性聊天判断不直接升级为长期真值。
3. 代码和脚本行为与文档冲突时，以磁盘文件为准，并在同一轮更新对应文档。

## 2. 新对话最小读取

1. 根 `AGENTS.md`。
2. 本文件。
3. 根据任务信号读取下表中直接相关的文档。
4. 涉及本机构建、ADB、设备或下载发布时，再读取 `.codex/local-context.properties`（不存在时只能参考 example）。

## 3. 文档路由

| 任务信号 | 必读文档 |
|---|---|
| 项目定位、模块边界、状态流、长期不变量 | `docs/architecture/项目长期总纲.md` |
| 用户路径、功能范围、非目标、验收口径 | `docs/product/产品需求基线.md` |
| 首版安装流程、分发决策、阶段门槛和发布阻断 | `docs/plans/03helper首版安装流程计划.md` |
| V1 首次安装、维护态、可见文案、页面状态和 UI 施工 | `docs/plans/V1应用功能与UI施工文案.md` |
| Cloud 官网视觉、release index、R2、GitHub Releases、公开下载或云端配置 | `docs/architecture/Cloud项目能力接线.md` |
| Android 助手动态配置协议、apps[]、修订号与目录发布顺序 | `docs/protocols/android-helper-config-contract.md` |
| 单测、构建、ADB 安装、运行级 smoke | `docs/testing/验证矩阵.md` |
| JDK、Android SDK、ADB、共享工具链 | `docs/operations/本地开发环境.md` |
| 权限、签名、密钥、下载源、发布 | `docs/security/安全与密钥边界.md` |
| 任务输入、范围、完成标准 | `docs/plans/codex-task-intake-template.md` |
| 当前事实、最近验证、未完成事项 | `docs/progress.md` |
| 可复用代码、设计、产品、验证、安全、运维或 AI 协作规则 | `docs/architecture/rules/README.md` |
| 规则沉淀 | `.agents/skills/rule-discovery/SKILL.md` |
| 任务收尾 | `.agents/skills/task-closeout/SKILL.md` |
| Skill 创建或维护 | `.agents/skills/skill-authoring/SKILL.md` |
| 通用能力回流 `NewProject` | `.agents/skills/template-feedback/SKILL.md` |
| 本次复制的物理来源 | `docs/来源锚点.md` |

## 4. 写作口径

1. 产品基线写用户可见目标、范围和验收结果。
2. 架构总纲写 owner、状态流、物理锚点、不变量和确定阈值。
3. 安装流程计划写阶段、决策、发布门槛和阻断项；所有尚未施工或验证的内容必须标记为方案 / 待实施。
4. 验证矩阵写可执行命令、运行级证据和剩余最小手测。
5. 进度只写已经发生的事实、验证结果、客观阻断和下一步，不把未来计划写成已完成。
6. 本机路径、设备地址和凭据不进入提交文档，只在本机上下文中维护。
