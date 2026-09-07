# 运维规则

1. 触发条件：需要本机构建、ADB 或设备验证。动作：先读取 `.codex/local-context.properties`，再执行环境检查；脚本参数必须显式指定目标设备。验证：环境快检和定向设备列表。边界：不猜测个人路径，不枚举全部在线设备执行破坏性任务。
2. 触发条件：准备安装、覆盖安装或卸载。动作：先读取目标包、版本、签名和设备状态；Android 工程交付默认构建最新 Debug 并执行显式设备的保留数据覆盖安装，再按快速 smoke 门槛决定是否启动已有检查；若检查框架会清理目标包，检查结束后必须再次覆盖安装并核对启动入口。验证：安装输出、包身份和进程 / 日志。边界：清数据、降级、卸载、重启和 release 安装需当次授权。
3. 触发条件：发布或更新应用。动作：通过 Cloud 发布资料提取器读取真实 APK，生成根目录单 APK ZIP，先上传核对，再保存 V5 启用快照。Cloud 配置服务是准入唯一 owner，客户端对缓存与下载文件做同一身份、版本、大小和 SHA-256 核验。验证：目录枚举、未知文件忽略、单项失败、ZIP / APK 校验和更新缓存；复杂真实链路交付精确人工用例。边界：不得恢复客户端发布者名单、下发命令或先配后传。
4. 触发条件：脚本失败。动作：保留失败证据并定位真实根因，禁止删断言、静默重试或扩大权限换取通过。验证：修复后重跑直接相关检查。边界：外部网络不稳定必须与确定性测试分离。
5. 触发条件：准备 Android Debug、staging 或 Release 构建。动作：正式包固定 `com.ninepointnine.helper`；Debug/staging 通过 `.test` 派生 `com.ninepointnine.helper.test` 并追加 `-test` 版本名；默认 Debug 保持开发证书，staging 显式读取仓库外 `helperStagingSigningPropertiesFile`，Release 显式读取 `helperProductionSigningPropertiesFile`；缺少材料立即失败。所有变体读取同一 `release-version.properties`，未指定版本由 `node scripts/bump-release-version.mjs` 只递增 patch。验证：三类构建的 APK 包名、版本、单 signer、v2 和证书摘要核对，缺材料负向构建必须失败。边界：不复用 03 桌面 / 03 歌词证书，不把 Debug 或 staging 身份当 production。
