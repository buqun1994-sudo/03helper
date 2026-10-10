# 手机到车机输入协议 v1

## 1. 唯一协议真值与职责

本文是 03helper 与 03desktop 的可选手机输入协议 owner。两端实现及测试必须按本文校验，不从 UI 文案、应用库存 Provider 或自启动协议推断输入能力。输入法服务包含在 03desktop APK；手机只传文字数据，不下发任意 shell。普通桌面、全局返回、应用抽屉不依赖助手或 ADB。

手机的输入状态、草稿、冻结发送请求、结果和提示只由 `InstallationSessionSnapshot.remoteInput` / `InstallationSession` 拥有；`InstallerRuntime` 持有既有真实 ADB 租约并编排副作用。后台服务只承载在线资源、通知和网络事件，不拥有第二套连接状态。车机的输入状态和提交资格由 `RemoteInputCoordinator`（或对应唯一协调器）拥有；无障碍、Provider、Socket、输入法都是其适配器。

## 2. 生命周期与性能

1. 手机在用户连接成功后启动 `connectedDevice` 前台服务，页面退后台和锁屏不关闭租约。主动断开停止服务和订阅。系统恢复服务时只消费私有端点检查点并重新验证同一 stableId；不把持久化信息恢复成已连接。
2. 输入订阅使用既有 ADB 租约的独立 multiplex stream，目标为 `localabstract:<实际桌面 applicationId>.remoteinput`，不占住设备操作互斥锁、不额外开放局域网端口。
3. ADB 握手使用有界连接/读取超时，订阅首条有效状态最多等 5 秒，成功后等待窗口事件不设置空闲读取截止；普通 stream 打开有 5 秒上限、I/O 失活有 120 秒上限；有实际传输进展的 APK 不会因总用时超过 120 秒被截断。超时关闭原始 socket 解除阻塞。TCP 使用内核低频保活（支持的系统设置 idle 30 秒、interval 10 秒、count 3），不发送 ADB 状态轮询。网络变化只触发同设备检查/有界恢复，不启动后台网段扫描。
4. 无障碍只订阅窗口变化/窗口状态/视图焦点三类事件并刷新元数据，设置 retrieveInteractiveWindows/reportViewIds。正常目标沿明确 focused/active 的 TYPE_APPLICATION 窗口确定；OEM 漏窗时，同一次 rootInActiveWindow 的 package/windowId 与单点 findFocus(FOCUS_INPUT) 节点身份必须一致，节点 refresh 成功且 focused/editable、具有可用身份 token，并排除已知 IME/SYSTEM/其它非应用窗口，才补出目标。root-only 不证明编辑器；每次从当前证据重证，不复制上一帧目标。同字段 token 去重，不同字段即使发生在窗口事件也失效冻结请求；真实 view-focused 无可验证 token 则保守失败，不以 applicationTargets 名单丢弃。仅读取 package/windowId/焦点、可编辑及节点身份/viewId，立即回收，不读取文字、截图、树遍历、按键、手势、后台索引或周期窗口扫描；平台自身的节点预取不等同于应用遍历。服务失联发布 unknown，不能当作 closed 或 open。手机连接时立即同步快照，此后按变化推送。车机 subscriber 数量与待发队列均有上限。
5. 在线服务只在会话内持有 Wi-Fi 网络锁，停止时释放；不持有持续 CPU 唤醒锁。不以正常锁屏通过推断强制 Doze 或所有 OEM 省电策略通过。
6. 真实 view-focused 的 source 经 refresh 后必须与同次字段 witness 的包名、窗口和 token 一致，不能用“同包同窗口”替换实际 source。提交前按需再取一次当前 root/findFocus/refresh 证明，与受理时冻结字段及输入连接对比，并在该查询后重查 10 秒期限；缺失或冲突时不提交。该查询不是周期轮询，未完成的请求最多消费已有有界 IME 回调机会；不同 OEM / WebView 的字段证明仍需真实设备验收。
7. 节点 hashCode/viewId 组合只作为进程内保守 token，并非碰撞绝对不可能的全局节点 ID；不持久化节点、事件、字段身份或文字。

## 3. 桥接与编码

Provider authority：`<实际桌面 applicationId>.remoteinput`；输入法组件：`<实际桌面 applicationId>/com.ninepointnine.desktop.input.RemoteInputMethodService`。助手仅适配已知桌面正式/测试身份，验证完整组件和 socket 名，不接受服务器自定义目的地。Provider 和本地 socket 仅接受 shell UID 2000 或 root UID 0。

Provider 调用回执 `Bundle.frame` 为 Base64(NO_WRAP) 编码的 UTF-8 JSON。Socket 每帧是一行 UTF-8 JSON，协议版本字段 `v=1`，state/accepted 单帧最多 65536 bytes，begin 请求帧最多 262144 bytes（覆盖控制字符 JSON 转义的最坏开销）；文字最多 32768 UTF-8 bytes，不 trim，不解释为命令。capability 必须声明 `requestTransport:"socket"`、`restoreProtocol:"exclusive"` 及固定 `restoreMethods:["claim_restore","release_restore"]`。正式手机发送只用 socket JSON body，文字不进入 shell argv 或临时文件；Provider begin 仅保留同协调器的诊断兼容入口。请求 id 使用 UUID。拒绝未知版本、缺字段、截断、越界、不完整目标对和倒序事件。`targetPackage=""、targetWindow=-1` 是 canonical unknown target，即使 available=true 且 keyboard=open 也属于合法观察；手机保持不可发送并继续订阅，后续同代次有效目标才能恢复发送资格。

### 固定 Provider 方法

| 方法 | 参数 | 结果 |
|---|---|---|
| `get_capability` | 无 | capability、实际 ime/socket 及当前状态，按需建立本地事件端点 |
| `get_state` | 无 | 最新 state |
| `begin` | Base64 JSON `{v,id,epoch,text}` | accepted，同步冻结目标和文本，或明确拒绝 |
| `cancel` | Base64 JSON `{v,id}` | 结束尚未提交的请求；已提交不回滚、不再次提交 |
| `claim_restore` | Base64 JSON `{v,id}` | restore_claim，最新 accepted id 一次性取得排他恢复临界区，取消未完成提交并阻止新 begin |
| `release_restore` | Base64 JSON `{v,id}` | restore_release，只有持锁 id 可在恢复 worker 结束后释放，成功后才允许新 begin |

`claim_restore` 除 frame 外另有固定布尔 `restoreAllowed`，与 JSON `allowed` 一致；`release_restore` 的固定 `restoreReleased` 与 JSON `released` 一致。最新 accepted UUID、恢复 claim UUID / 诊断期限 / released 墓碑在回执前同步写入私有检查点，无文字或字段身份。获取时先持久化排他 fence，再取消尚未结束的同请求；claim 期间所有 begin（含重复 id）拒绝。只有同 id release 持久化成功才放行；墓碑阻止旧 guard 重新 claim，下一次 accepted 在同一个 owner commit 中移除墓碑。进程重建保留未释放 claim，损坏记录 / 读写失败保守阻断。16 秒期限只作诊断，不证明旧 worker 已退出，不会自动解锁。守护被强杀、claim 回执丢失或持久化故障导致漏 release 时，需要操作者先确认旧 worker 已停止，再主动处理同 UUID release；普通重连、等待、重启应用不得解除，当前无自动修复 UI。扩展仍为 v1，但 capability 必须显式声明 exclusive，旧的布尔检查桥接不兼容新版安全门禁。

Socket 订阅首帧请求：`{v:1,op:"subscribe"}`；立即回复完整 state，随后按状态变化推送。短请求首帧：`{v:1,op:"begin",id,epoch,text}`；只回单行 accepted 后关闭，不注册状态订阅。两种首帧都需换行完整且最多等待 5 秒；订阅认证完成后无 idle polling/截止。

### 状态字段

state JSON：`{v,type:"state",available,keyboard,epoch,targetPackage,targetWindow,sequence,sending}`。

- `keyboard` 仅为 `open/closed/unknown`；存在任何可见输入法类型窗口即 open，不按输入法包名过滤。
- `targetPackage/targetWindow` 只描述当前应用窗口，不包含车机输入框文字。输入资格需要可验证目标，不由键盘显示单独推断。
- `epoch` 随键盘/目标变化递增；`sequence` 单调递增。手机另用连接 generation 拒绝旧租约事件。
- `available` 表示监听/组件及恢复检查点可用且当前没有未释放恢复 fence，不代表编辑器已验证；`sending` 表示尚未终态的提交请求，用于区分自发输入法切换与用户新输入意图。

accepted JSON：`{v,type:"accepted",id,accepted,reason}`。结果读取路径 `content://<authority>/results/<id>` 输出一行 JSON `{v,type:"result",id,committed,reason}` 并关闭；已结束请求允许回读收据。请求单次执行、有界去重账本，提交后清除文本；相同 id 不重复提交。请求期限 10 秒。

## 4. 发送与恢复

手机领域先冻结 id/文本/目标 epoch，再由受控适配器执行：读取并验证原输入法 → begin 接受 → 在车机启动 detached 15 秒恢复 guard → 启用/切换内置输入法 → 等系统 onStartInput 建立有效输入连接、核对目标 → commitText → 读取结果 → 恢复原输入法并回读 → 返回手机结果。

车机 shell 使用 EXIT/HUP/INT/TERM 恢复；独立 guard 不依赖手机最后一条命令。发送前验证系统 timeout/nohup 工具；提交子进程总截止 12 秒，guard 延迟 15 秒、claim 查询最多 4 秒，恢复子进程最多 9 秒、释放调用最多 4 秒，恢复读/写各最多 3 秒。guard 只有取得排他 claim 后且当前 IME 仍为本次临时组件才恢复；取得 claim 后忽略普通 HUP/INT/TERM 并以阻塞 wait 等待有界恢复 worker 结束，再 release，不能被主事务的正常 kill 提前放行新请求。强杀或系统级异常仍可能留下 fence，按上段保守处理。默认输入法恢复不会覆盖用户另行选择的非临时组件；不申请桌面 WRITE_SECURE_SETTINGS。

性能优化不改变每次发送恢复原 IME 的协议：原 IME 每次实时读；首次在同一前置 shell 中用 `cmd input_method ime list -s` 的成功退出码和完整原组件行证明原生命令能力，租约内缓存该能力，不支持则回退固定 `ime` 入口。终态 JSON / id / 类型 / committed-reason 语义与唯一恢复标记全部验证后才省去冗余 cancel，`unknown_request` 和畸形回执仍取消且不重放；恢复成功标记独立于 JSON 解析，坏结果不能抹掉已确认恢复证据。主事务用已有 `restore_ok` 杀 guard，省掉再次 settings 查询。原生 help 的单条时间只说明 CLI 启动成本，实际系统切换、commit 与恢复总耗时必须另测；不声称因此已达到可接受速度。

切换输入法产生的短暂关闭/开启不改变冻结目标；真正目标变化、失联、有效输入连接缺失或超时则拒绝提交。提交不自动执行回车/搜索。实际提交由 InputConnection 回执证明，不以 ADB 命令成功代替文字提交结果。

手机区分 committed 与 restored：提交成功才清除本次未被编辑的草稿；恢复失败单独提示。结果账本重建/驱逐产生 `unknown_request` 时一律投影为未知提交，不能把 committed=false 解释为可证明未提交。未知提交结果保留草稿、不自动重放，要求查看车机；新的真实订阅同步后才重新允许用户主动发送。页面返回不取消已开始的恢复事务。

## 5. 页面与通知

维护首页为常用/应用/输入分组：应用只保留管理已安装应用和安装应用；安装应用二级页提供推荐/本机两个来源，复用原安装与授权主链。输入页顶部只有输入文字分页项，预留后续页结构但无终端执行能力。进入自动聚焦并弹出手机键盘；输入框自适应、内部滚动、发送按钮保持可见，回车用于换行。

可发送必须同时满足：当前租约与事件已确认、keyboard open、有效目标、文字非空、无其它设备动作、车机 sending=false、无未确认发送结果。状态通道中断时，当前提交/恢复仍由原冻结请求占用，先健康检查而不替换请求代次；独立收据完成后才恢复事件订阅。真正租约断线立即保留未知结果。非兼容桌面只标不可用，通道故障每租约最多 3 次延迟重试，无周期复活。键盘关闭时按钮灰色但保留解释点击，提示“请在车机上点击输入框。”。断线/同步/不兼容分别提示，不缓存按钮可用结论。

通知分安静的在线状态和输入提醒两个渠道。通知许可开启且输入可用时，首次同步 open 或由关到开发布同一条输入通知；点击直接进入输入页并重新检查状态。输入页可见则不重复提醒；关闭键盘/断线撤掉提醒；临时输入法切换不重复发提醒。蓝底白字和全部非线性动效沿用 UI owner。

## 6. 验证范围

确定性单测覆盖旧 generation/sequence、断线失效、冻结文本、发送中编辑、重复 id、未知回执、目标改变、多个输入法窗口、权限失联、有限队列、恢复 guard owner。两端编译、相关测试、Lint、规则检查通过后构建 Debug 并保留数据交付。车机键盘开关、临时切换的焦点/光标、中文/多行/emoji、锁屏通知、断线恢复必须单列设备证据或用户主测，不将 fixture 写成实车通过。
