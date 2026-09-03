# AGENTS.md

核心：用中文沟通；磁盘中的现有文件是工程真值。

## 1. 项目身份

1. 本仓库是 `03helper`，显示名称暂定“03应用安装助手”，负责在受用户授权的 Android 手机上编排 03 歌词、03桌面及可选组件的分发、ADB 安装、授权校验和维护操作。
2. `03helper` 是独立产品和独立 Git 仓库，不是 `03lyrics` 或 `03桌面` 的源码模块；两个产品的 APK、正式签名和业务实现仍由各自仓库负责。
3. 当前仓库承载安装助手底座和长期上下文；未在文档中确认的车机接口、网盘直链、权限或发布承诺不得写成已实现事实。
4. 默认远端为 `https://github.com/buqun1994-sudo/03helper.git`。未经用户明确要求，不修改原项目 `03lyrics` 的 remote。

## 2. 新对话入口

1. 第一轮必须先读取本文件和 `docs/README.md`。
2. 按任务信号读取产品、架构、验证、安全、运维或规则文档，不一次性加载全部历史。
3. 涉及本机构建、Android SDK、ADB、网络发布或设备时，先读取 `.codex/local-context.properties`；文件不存在时只能参考 `.codex/local-context.properties.example`，不得猜测个人路径、设备地址或凭据。
4. 开工前执行 `git status --short --branch`，保留用户已有改动。
5. 代码、配置与脚本行为以磁盘文件和已验证契约为准；旧 README、截图、聊天内容或终端乱码与源码冲突时，以源码为真值并同步文档。
6. 涉及 UI、连接方式、安装步骤、授权状态、维护动作或动效时，先读取对应产品与设计文档；不得在调用点复制第二套状态机。
7. 涉及 Cloud、官网、release index、R2、GitHub Releases、公开下载或云端配置时，先读取 `docs/architecture/Cloud项目能力接线.md`；该文档会继续路由到 `cloud` 仓库的对应真值，不得把 Cloud 现有的桌面发布协议误当成已完成的 Android 安装服务。
8. UI 已冻结为蓝底白字主链：首次安装使用整屏浅蓝背景与白色内容；维护态保持蓝色背景并使用大号、极简、按用途分组的分类按钮；页面、按钮、加载、进度和结果的进入 / 退出 / 点击全部必须使用 `docs/plans/V1应用功能与UI施工文案.md` 的基础非线性动效，禁止静态突变。

## 3. 物理边界

1. `src/` 或后续在长期总纲中声明的源码目录：安装助手领域、应用流程和界面实现；目录变更必须同步文档。
2. `docs/`：产品、架构、计划、验证、安全、运维、决策和进度真值。
3. `.agents/skills/`：需要根据上下文选择的高频 AI 工作流。
4. `scripts/`：可重复执行的仓库检查、生成、迁移和验证护栏；脚本不得写死个人路径、设备地址、密钥或网络口令。
5. `.codex/`：项目级 Codex 说明和可提交配置；`local-context.properties` 永不提交。
6. APK、签名文件、构建产物、日志、缓存、车机导出数据和其它外部项目源码不属于本仓库底座，除非任务文档明确规定可追踪的元数据格式。

## 4. 架构主链约束

1. 领域模型负责安装会话、设备身份、组件清单、授权结果、验证结果和维护状态的状态机，不让 UI 或脚本持有第二套状态。
2. 应用流程负责“发现 / 连接 / 选择 / 解析下载页 / 下载 ZIP / 校验 ZIP / 解压 APK / 删除 ZIP / 校验 APK / 安装 / 授权 / 验证 / 维护”的编排；每一步必须可观察、可取消、可恢复并保存失败原因。
3. ADB、局域网发现、OTG、下载源、应用内 WebView、ZIP 解压和系统命令都必须通过端口与适配器进入核心流程；核心层不得写死某个网盘协议、设备地址或任意 shell 文本。
4. 远端版本清单只能声明已签名的发布物、哈希和兼容范围，不能下发任意命令或扩大权限。
5. 授权动作采用版本控制的白名单，执行前读取原值，执行后回读验证；不得覆盖车机已有授权或静默执行破坏性清理。
6. 跨两个以上调用点复用的规则回到唯一 owner；不在页面、广播、脚本或临时异常分支中复制状态机。

## 5. 性能与设备边界

1. 默认采用事件驱动和有界超时；禁止高频扫描、持续截图、ADB 常驻轮询、后台自拉活和无障碍模拟操作。
2. 局域网发现必须有明确的广播范围、超时、去重和停止条件；OTG 只能在设备能力被实证后进入正式主链。
3. ADB 只作为用户主动发起的开发、安装、维护和验证通道，不成为 03歌词或03桌面的正式运行依赖。
4. 设备、网络或车机状态未知时必须保守失败并给出下一步，不得猜测成功或扩大操作范围。

## 6. 安全红线

1. 禁止提交真实签名文件、密码、token、私钥、`local.properties`、`keystore.properties`、本机绝对路径、设备地址、安装日志和构建产物。
2. 新增 Android 权限、ADB shell 动作、无障碍能力、文件读取或网络域名时，必须说明用户价值、版本边界、最小权限和运行级验证。
3. 外部下载源属于不可信输入；必须校验 HTTPS、签名 / 哈希、ZIP 与 APK 的大小、版本和目标包身份，不能把网盘返回页面当作稳定协议。
4. 安装助手不得保存或上传用户媒体、通讯录、车辆数据或完整 shell 历史；诊断导出默认脱敏并由用户主动触发。
5. 清除数据、卸载、降级、重启设备、修改签名、安装 release、发布和推送均需当次明确授权；普通验证只允许可回滚、保留数据的动作。

## 7. Skills 路由

1. 发现稳定复用规则、重复失败或模板级候选时，使用 `.agents/skills/rule-discovery/SKILL.md`。
2. 收尾、更新进度、检查 Git 状态或准备交接时，使用 `.agents/skills/task-closeout/SKILL.md`；该 Skill 不自动提交、推送或发布。
3. 创建、更新或审查项目级 Skill 时，使用 `.agents/skills/skill-authoring/SKILL.md`。
4. 将已验证的通用能力去专有化并回流 `NewProject` 时，使用 `.agents/skills/template-feedback/SKILL.md`。
5. 一次性事实写入 `docs/progress.md`，固定机械动作优先写脚本或测试，不为单次事实创建 Skill。
6. 涉及本产品包名、namespace、版本、签名、远端仓库、分发包或配置导出时，必须先使用 `.agents/skills/03-app-repository/SKILL.md` 路由到共享 `03-app-manager`；不得在本仓库维护第二份 03 APP 身份表。

## 8. 验证与收尾

1. 文档、规则、Skill 或脚本变更至少执行：
   - `node scripts/check-project-docs.mjs`
   - `node scripts/check-skills.mjs`
   - `git diff --check`
2. 代码或工程行为变更按“语法 / 类型或编译 / 直接相关自动化测试 / 可测试产物 / 最小运行检查 / 人工主测”的顺序闭环；编译通过不等于安装流程可用。Android 工程变更在本机环境和显式测试设备可用时，必须构建并执行保留数据覆盖安装最新 Debug APK，作为用户可测试的交付前置；安装不是运行 smoke，也不需要另行申请高成本测试授权。若运行检查会清理或替换目标包，所有自动化结束后必须再次覆盖安装并核对包身份与启动入口，最终交付状态以最后一次安装为准。
3. 安装后只在运行检查同时满足以下条件时自动执行：已有脚本或 instrumentation 入口、预计单条检查不超过 5 分钟、无清数据 / 卸载 / 降级 / 重启 / release 或车机写入等破坏性动作、且不依赖未具备的真实发布资料或不稳定外部链路。否则停止自动扩展，交付逐条人工用例、预期结果、停止条件和客观阻断；不得把 fixture 或模拟结果写成真实通过。运行检查结束后仍必须完成最终 Debug 覆盖安装和包身份核对。
4. 收尾必须说明实际改动、验证结果、Git 状态、未执行项及阻断；未经用户明确要求不提交、不推送、不发布。

## 9. Android 身份、版本与签名长期约束

1. `03helper` 的 `applicationId`、`namespace` 和 Kotlin 包根固定为 `com.ninepointnine.helper`，沿用 03 桌面 / 03 歌词的 `com.ninepointnine` 组织前缀，但不得复用它们的产品后缀、包名或业务源码。
2. 默认 Debug 继续使用开发机自动证书和当前 Debug 版本；staging 使用仓库外独立 `03helper` staging 证书；Release 使用仓库外独立 `03helper` production 证书。三者不得互相回退或跨产品复用。
3. staging / production 的 JKS、`signing.properties`、口令和私钥永不进入 Git；Gradle 只接受显式的仓库外属性文件路径，缺少材料必须 fail closed。
4. Release、Debug 和 staging/test 共用根目录 `release-version.properties` 作为唯一版本真值；Debug 只通过 `applicationIdSuffix=".test"` 与 `versionNameSuffix="-test"` 派生测试身份，`versionCode` 与 Release 相同。用户未指定版本时，运行 `node scripts/bump-release-version.mjs` 只递增 patch；用户明确指定时按完整 `major.minor.patch` 写入。每次递增后测试包即可在同一 `.test` 身份下覆盖更新。
5. 任何身份、签名或版本规则变更必须同步 `docs/architecture/项目长期总纲.md`、`docs/operations/本地开发环境.md`、`docs/security/安全与密钥边界.md`、`docs/testing/验证矩阵.md` 和对应规则文件，并完成 APK 包名、版本、签名摘要与 v2 校验。
