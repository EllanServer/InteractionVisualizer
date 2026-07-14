# 第二阶段真实性能与兼容性验证

本规范用于验证 InteractionVisualizer 的四类优化：旧 Bukkit 展示实体基线、无服务端锚点的纯发包静态展示、`DisplayManager` 通用可见性限流（另含旧掉落物标签桶回归），以及事件驱动的交互方块更新。目标是用同一套场景同时观察发包、TPS/MSPT、插件热路径和客户端 FPS，避免用单次 microbenchmark 代替真实服务器结论。

配套脚本包括 [`tools/perf/phase2-pktmon.ps1`](../tools/perf/phase2-pktmon.ps1)、[`tools/perf/analyze-phase2-pcap.ps1`](../tools/perf/analyze-phase2-pcap.ps1) 和 [`tools/perf/analyze-presentmon.ps1`](../tools/perf/analyze-presentmon.ps1)。它们分别负责安全包装 Windows `pktmon`、只读离线分析 pcapng/字段 CSV，以及分析单一 Minecraft PID/SwapChain 的逐帧 CSV；任何分析脚本都不会替用户启动、停止或改动 Minecraft 服务端。

## 1. 执行前提

- A/B 两个 jar 必须由 GitHub Actions 构建，记录完整 commit SHA 和 artifact SHA-256；不要在测试机本地构建。
- 固定 Paper build、JDK、JVM 参数、世界快照、插件集合、配置、客户端版本、资源包和网络压缩阈值。
- 每轮只运行一个服务端实例。不得同时运行 A、B，因为它们会竞争 CPU、内存、磁盘和网络。
- 每轮从同一个只读模板复制世界和配置，完整重启服务端；不要依赖 `/reload` 清理旧实体、缓存或 JIT 状态。
- 先预生成并加载测试区块。测试窗口内禁止自动备份、世界保存、语言下载、更新检查及其他计划任务。
- `pktmon` 的状态、开始、停止和计数器命令需要管理员 PowerShell。转换已有 ETL 通常不需要管理员权限。
- 当前部署状态不等于待测定义。执行前必须确认：

  - “旧实体”构建和候选构建都包含完全相同的 `/iv perf` 观测代码；观测层本身不得改变渲染行为。
  - “纯发包静态”只替换满足资格条件的逻辑 `Item`：不创建 Bukkit tracker/`ItemDisplay` 锚点。普通 `DisplayEntity` 与 `TextDisplay` 不在该替换范围内，仍沿用原路径；`/iv perf scene static` 这个场景名称本身也不能证明无锚点。
  - 事件驱动候选已覆盖目标事件，并保留低频兜底审计。

## 2. 固定测量窗口

每个稳定态 run 使用以下时序：

1. 启动服务端并等待 `Done`。
2. 预热 JVM、区块和客户端 120 秒。
3. 建立场景并等待 20 秒稳定。
4. 启动 `pktmon`。
5. 执行 `/iv perf start <label>`。
6. 采集 180 秒；采集中不要执行 `/iv perf status`。
7. 执行 `/iv perf stop`，随后保存 `pktmon counters` 并停止抓包。
8. 在测量窗口之外清理场景并正常关闭服务端。

spawn burst 单独使用 10 秒窗口；运动 burst 可使用 5 秒窗口。短窗口只用于累计耗时和包突发，不能用约 100～200 个 tick 样本宣称 p99/p99.9 MSPT。

`/iv perf stop` 会把一条 `IV_PERF {json}` 写入服务端日志。label 采用 `scenario_variant_run`，例如 `static_packet_A1`。以日志中 stop 时间减 JSON 的 `seconds` 得到精确抓包裁剪窗口。

## 3. 四类场景

### 3.1 旧实体与纯发包静态

需要两个逻辑等价、仅渲染传输路径不同的构建：

- A：旧 Bukkit 展示实体路径。
- B：无 Bukkit 锚点的纯发包静态路径。

本场景只改变 `Settings.Performance.VirtualItems.PacketOnlyStatic`。两边都固定
`Settings.Performance.VisibilityRateLimit.Enabled: false`，并使用相同的
`Settings.Performance.BlockUpdates.EventDriven` 值；否则生成阶段的通用显示限流或方块调度差异会污染 packet-only 结论。

spawn burst：

```text
/iv perf clear
/iv perf start static_spawn_A1
/iv perf scene static 4096 400
# 10 秒后
/iv perf stop
/iv perf clear
```

steady：

```text
/iv perf clear
/iv perf scene static 4096 5000
# 等待 20 秒
/iv perf start static_steady_A1
# 等待 180 秒
/iv perf stop
/iv perf clear
```

另外单独比较当前动画锚点开关：

- A：`Settings.Performance.VirtualItems.StaticAnchorDuringAnimation: false`
- B：`Settings.Performance.VirtualItems.StaticAnchorDuringAnimation: true`

```text
/iv perf clear
/iv perf start motion_anchor_A1
/iv perf scene motion 2048 80
# 5 秒后
/iv perf stop
/iv perf clear
```

若要形成 180 秒持续运动负载，应由固定脚本每 5 秒重建一次 80-tick 场景，或扩展测试 harness；不要依赖人工不定时重复命令。

### 3.2 可见性令牌桶

这里有两个互不替代的实现，必须分开测量。`/iv perf scene` 经过 `DisplayManager`，但不经过
`DroppedItemDisplay`；真实 Bukkit dropped item 的标签则经过后者。不要用旧标签桶的结果替代新通用队列的验证。

#### 3.2.1 `DisplayManager` 通用显示限流

两边都固定 `Settings.Performance.VirtualItems.PacketOnlyStatic: true` 和相同的
`Settings.Performance.BlockUpdates.EventDriven`，只改变通用限流开关：

- A：`Settings.Performance.VisibilityRateLimit.Enabled: false`。
- B：`Settings.Performance.VisibilityRateLimit.Enabled: true`，`BucketSize: 128`，`RestorePerTick: 32`。

使用 `/iv perf scene static 2048 5000` 建立相同的 eligible logical `Item`。先等待全部显示，再传送到距离原场景
大于 `(server view-distance + 2) * 16` blocks 的位置，并确认原场景 chunk 已离开该玩家的 sent-chunk set；也可以切换世界并确认 unload。完成隐藏后再开始测量，返回原场景触发重新排队。仅移动 80 格通常不足以在较大 view-distance 下证明 chunk 已卸载。

`PacketOnlyStatic: false` 的 Bukkit tracker 路径当前只用于 initial spawn burst 对照，不能拿“离开后重新进入”宣称通用队列已被验证。spawn burst 应单独运行一次，验证首次生成时不会在单 tick 恢复全部实体。两名以上玩家时，每名玩家必须拥有独立队列。

```text
/iv perf clear
/iv perf scene static 2048 5000
# 等待全部显示，移动超过 (view-distance + 2) * 16 blocks 或切换世界，并确认原 chunk unload
/iv perf start visibility_generic_B1
# 返回原场景，等待全部恢复
/iv perf stop
/iv perf clear
```

#### 3.2.2 `DroppedItemDisplay` 专用标签桶回归

使用固定世界快照预置 2048 个真实 Bukkit dropped item，设置不可拾取且在测试期内不自然消失。只改变：

- 两侧都显式设置 `Entities.Item.Options.VisibilityCulling.Enabled: true`、
  `Entities.Item.Options.VisibilityCulling.ViewDistance: 64` 和
  `Entities.Item.Options.VisibilityRateLimit.Enabled: true`。这两个实验性控制默认关闭，不能依赖打包默认值。
- A：`BucketSize: 4096`、`RestorePerTick: 4096`，作为等效不限流。
- B：同一路径使用 `BucketSize: 128`、`RestorePerTick: 32`。

玩家从所有物品 80 格外瞬移到 64 格可见范围内，等待全部显示，再离开范围。两名以上玩家时，每名玩家拥有独立令牌桶。

2048 个标签在 128/32 下的理论显示收敛时间为：

```text
1 + ceil((2048 - 128) / 32) = 61 ticks = 3.05 seconds
```

两个限流场景都必须另外覆盖快速进出、排队期间实体消失、两名玩家反向移动、玩家退出重连，以及世界切换。
隐藏当前是立即执行的，应单独观察离开范围时是否出现大突发。通用队列记录
`visibilityShowsQueued/visibilityShowsDrained`；旧标签桶没有这两个通用计数器，必须结合 Bukkit show/hide 和抓包判断。
每个 `IV_PERF` 样本还必须核对 `droppedLabelVisibilityCulling`、`droppedLabelViewDistance`、
`droppedLabelVisibilityRateLimit`、`droppedLabelVisibilityBucketSize` 和
`droppedLabelVisibilityRestorePerTick`，以证明实际运行配置与 V2 清单一致。

### 3.3 事件驱动方块更新

使用固定位置的 1024 个 Furnace、Blast Furnace 和 Smoker，分别运行：

- 全部 idle，180 秒。
- 25% active，180 秒。
- 100% active，180 秒。
- 固定频率的库存及燃烧状态变化，180 秒。

本场景固定 `PacketOnlyStatic` 与 `VisibilityRateLimit.Enabled`，只改变
`Settings.Performance.BlockUpdates.EventDriven`；不要把静态发包或显示队列收益计入方块更新收益。

A 为固定周期轮询，B 为事件驱动更新加低频兜底审计。事件兼容性至少包括：

- 普通点击、shift-click、拖拽。
- Hopper source 和 destination 的 `InventoryMoveItemEvent`。
- burn start、start smelt、smelt complete。
- 方块破坏、爆炸或其他移除、区块 unload/reload。
- 其他插件直接修改库存但不触发标准玩家事件；这条必须由兜底审计在约定周期内修正。

`displaySyncs`/`itemSyncs` 可能被 revision cache 掩盖，不能独自代表扫描成本。事件驱动比较至少同时使用 MSPT、插件热路径累计耗时或单独的 block poll/update 计数器；需要定位时再跑 Spark/JFR 诊断轮，不要把 profiler 轮与干净 MSPT 轮混为一组。

### 3.4 全量混合场景

- A：旧实体、通用限流关闭、旧标签桶等效不限流、固定周期轮询。
- B：纯发包静态、通用及旧标签桶均为 128/32、事件驱动更新。

在同一区域组合 4096 个静态展示、2048 个掉落物标签、1024 个交互方块和固定动画 burst。该场景只用于验证集成收益和交互回归；单项取舍仍以 3.1～3.3 的单因素结果为准。

## 4. ABBA 顺序

每个场景执行 12 个独立 run：

| Block | Run 1 | Run 2 | Run 3 | Run 4 |
|---|---:|---:|---:|---:|
| 1 | A | B | B | A |
| 2 | B | A | A | B |
| 3 | A | B | B | A |

每个 run 都完整重启并恢复相同世界快照。不得把同一 JVM 内先后切换配置当作独立重复。保留每一对 A/B 的原始结果，以 paired log-ratio 计算中位数，并对配对结果 bootstrap 95% CI。

建议场景编号：

| ID | A | B | 主要负载 |
|---|---|---|---|
| S1 | 旧实体 | 纯发包静态 | 4096 static，10 秒 spawn |
| S2 | 旧实体 | 纯发包静态 | 4096 static，180 秒 steady |
| M1 | moving anchor | static anchor | 2048 motion burst |
| V1 | 通用限流关闭 | 通用限流 128/32 | packet-only static；2048 items 的 sent-chunk unload/re-entry |
| V2 | 旧标签桶 4096/4096 | 旧标签桶 128/32 | 2048 dropped labels 进出范围 |
| E1 | 周期轮询 | 事件驱动 | 1024 idle blocks |
| E2 | 周期轮询 | 事件驱动 | 1024 active/mutating blocks |
| X1 | 全旧路径 | 全候选路径 | 混合生产场景 |

如需识别三项优化之间的交互，再补 packet-static × limiter × event-driven 的完整 2³ 八配置；不要从 staged pair 推断未测试的交互效应。

### 4.1 ABBA 清单与自动分析

[`tools/perf/analyze-phase2-abba.ps1`](../tools/perf/analyze-phase2-abba.ps1) 把上述顺序变成硬校验，而不是依赖人工整理。正式清单是 CSV，必须包含：

```text
Scenario,Block,Position,Variant,RunId,StackSha256,ArtifactSha256,CaptureMethod,SourcePath
S2,1,1,A,S2_A_01,<stack sha256>,<A artifact sha256>,iv-perf,S2_A_01.log
S2,1,2,B,S2_B_01,<same stack sha256>,<B artifact sha256>,iv-perf,S2_B_01.log
S2,1,3,B,S2_B_02,<same stack sha256>,<B artifact sha256>,iv-perf,S2_B_02.log
S2,1,4,A,S2_A_02,<same stack sha256>,<A artifact sha256>,iv-perf,S2_A_02.log
```

`StackSha256` 是固定栈清单的 SHA-256；该清单至少记录 Paper/Leaf build、JDK、JVM 参数、世界快照、插件集、配置、客户端及资源包，不包含本来就应该不同的 A/B IV artifact。每个 variant 的 `ArtifactSha256` 在全部 run 中必须一致，并对应 GitHub Actions 下载的 jar。`SourcePath` 可以是分析 JSON，也可以是包含匹配 `RunId` label 的 `IV_PERF` 服务端日志；相对路径以 CSV 所在目录为准。

```powershell
& .\tools\perf\analyze-phase2-abba.ps1 .\S2-manifest.csv `
    -Metric msptP95 -Direction LowerIsBetter -MinimumSeconds 170 `
    -OutputJson .\S2-msptP95.abba.json

& .\tools\perf\analyze-phase2-abba.ps1 .\S2-f3l-manifest.csv `
    -Metric p95FrameTimeMs -Direction LowerIsBetter -MinimumSeconds 170 `
    -OutputJson .\S2-client-loop-p95.abba.json
```

默认模式要求恰好 12 个独立 run，顺序为 ABBA、BAAB、ABBA；按相邻位置 1-2、3-4 形成六对，输出 `exp(median(log(B/A)))` 及固定 seed 的 paired-bootstrap 95% CI。脚本会拒绝混合 stack、variant artifact、capture method，拒绝 `droppedTickSamples`/`missingSampleCount`/`jfrDataLossBytes` 非零及窗口不足。`-AllowIncomplete` 只用于探索性中间检查，其结果会明确标为 `formalComplete: false`，不能用于通过门槛。

## 5. pktmon 使用

### 5.1 安全包装脚本

在管理员 PowerShell 中：

```powershell
$tool = ".\tools\perf\phase2-pktmon.ps1"
$out = "D:\iv-ab\2026-07-13"

& $tool selftest
& $tool status -OutputDirectory $out
& $tool start -OutputDirectory $out -RunId "S2_A_01" -Port 25566 -CaptureComponent nics

# 运行 /iv perf 窗口
& $tool counters -OutputDirectory $out -RunId "S2_A_01"
& $tool stop -OutputDirectory $out -RunId "S2_A_01"
& $tool convert -OutputDirectory $out -RunId "S2_A_01"
```

安全约束：

- 脚本没有删除过滤器的子命令，也绝不调用 `pktmon filter remove`。
- 发现现有 PktMon 捕获、未知过滤器、归属状态不一致或无法判断捕获状态时，`start` 直接拒绝。
- 脚本首次确认全局过滤器为空后添加唯一的 TCP/25566 过滤器，并在输出目录保存过滤器状态。后续 run 仅在当前全局状态与已保存状态完全一致时复用。
- 每个 `RunId` 先以 CreateNew 写入不可变 claim；ETL、counters、capture sidecar、pcapng 和 stats 任一同名产物已存在时都拒绝开始，不能用重复 RunId 覆盖证据。
- 每个输出目录另有 CreateNew 捕获期独占 lock；不同 RunId 也不能并发争写 active manifest。只有确认 owned capture 已停止且状态为 Inactive、completed sidecar 已落盘后才释放。崩溃遗留的 stale lock 永不自动抢占，必须先人工核对 PktMon 和 manifest。
- 调用 `pktmon start` 前先在同目录原子发布 schema 2 的 `starting` manifest，记录 capture GUID、主机、调用 PID/时间、模式、component、规范 ETL 路径、参数摘要和全局过滤器 SHA-256 指纹。start 或后续落盘失败时，只在 live ETL 路径与上述归属全部匹配时尝试清理；无法证明就保留可恢复 manifest 并拒绝全局 stop。
- `stop` 同时要求显式 `RunId`、匹配 claim/manifest、同一主机、相同过滤器指纹，以及 live PktMon/logman 中的唯一 ETL 路径。任一证据缺失或冲突都拒绝执行全局 `pktmon stop`。复制或遗留 manifest 本身不构成归属证明。
- 因 Windows 的 `pktmon filter remove` 只能一次删除全部过滤器，脚本停止后故意保留自己的过滤器。整个 ABBA campaign 使用同一个输出目录。campaign 结束后的全局清理由管理员在确认机器无其他 PktMon 用户后人工决定。

### 5.2 同机 loopback 校准

客户端与服务端分机时优先使用 `-CaptureComponent nics`，然后按 `tcp.srcport == 25566` 统计下行，按 `tcp.dstport == 25566` 统计上行。

同一台 Windows 机器通过 `127.0.0.1` 连接时，流量可能绕过物理 NIC。正式 ABBA 前必须做一次 10 秒校准：

1. 用 `nics` 捕获一次登录或固定动作。
2. 检查 counters/pcapng 是否存在 25566 流量。
3. 如果为零，在管理员终端运行 `pktmon list --json`，改用 `-CaptureComponent all` 或明确的 `-CaptureComponentId <id>` 重测。
4. 使用 all 时，同一个网络包会在协议栈多个组件出现。包装器会从完成的 capture sidecar 识别 component=all，并强制转换时提供唯一的 `-ComponentId <id>`；绝不能把所有 Appearance 相加当作包量。缺少或不匹配 sidecar 的 ETL 一律拒绝转换。

示例：

```powershell
& $tool start -OutputDirectory $out -RunId "loopback_cal" -Port 25566 -CaptureComponent all
# 10 秒固定动作后 stop
& $tool stop -OutputDirectory $out -RunId "loopback_cal"
& $tool convert -OutputDirectory $out -RunId "loopback_cal" -ComponentId 12
```

当前包装默认抓取每包前 128 bytes，以保留 IP/TCP 头并降低磁盘扰动。只有短时协议兼容分析才使用 `-PacketSize 0` 抓全包。正式测试前做一次 capture-off/on 控制；如果开启 pktmon 令 p95 MSPT 增加超过 2%，先缩短 ETL 窗口或减小 `PacketSize`，不要直接把有 ETL 的结果和无 ETL 的结果混为一组。

PktMon 没有可供包装脚本绑定的公开 capture session ID。普通 ETL 模式可用唯一的规范化 ETL 路径、过滤器指纹、主机和捕获参数共同证明归属；`CountersOnly` 没有唯一 ETL 路径，无法排除“原捕获已被外部停止，随后另一个同配置捕获接管”的情况。因此安全包装器拒绝启动不可自动安全停止的 `-CountersOnly` 模式。若专用测试机确实只能使用 counters-only，必须由管理员在独占维护窗口直接操作 PktMon，并把这类结果标为不同采集方法，不能与 ETL run 配对。

### 5.3 pcapng/tshark 只读离线分析

[`tools/perf/analyze-phase2-pcap.ps1`](../tools/perf/analyze-phase2-pcap.ps1) 不调用 PktMon、不打开实时接口，也不需要管理员权限。输入为 `.pcapng`/`.pcap`/`.cap` 时需要 Wireshark 的 `tshark`；也可直接读取预先导出的字段 CSV，后者不需要安装 tshark。先运行不依赖 tshark 的内置测试：

```powershell
& .\tools\perf\analyze-phase2-pcap.ps1 -SelfTest

$runId = "S2_A_01"
$stopEpoch = 1783920180.125 # 取 IV_PERF stop 日志时间，必须保存原始取值依据
$seconds = 180.0           # 取同一条 IV_PERF JSON 的 seconds

& .\tools\perf\analyze-phase2-pcap.ps1 "D:\iv-ab\2026-07-13\$runId.pcapng" `
    -ServerPort 25566 `
    -WindowStartEpochSeconds ($stopEpoch - $seconds) `
    -WindowEndEpochSeconds $stopEpoch `
    -OutputJson "D:\iv-ab\2026-07-13\$runId.pcap-analysis.json"
```

正式结果必须显式传入同一条 `IV_PERF` 记录推导的半开窗口 `[startEpochSeconds, endEpochSeconds)`；不传窗口时只适合勘查，`formalEvidenceReady` 为 false。50ms/1s 桶按 Unix epoch 对齐，即 `floor(frame.time_epoch / bucketSeconds)`，不能用“捕获第一包”分别对齐 A/B。下行定义为 `tcp.srcport == ServerPort`，上行定义为 `tcp.dstport == ServerPort`；两端同时等于服务端端口的歧义行会令正式证据失效。

预导出 CSV 至少包含以下精确列名：

```text
frame.number,frame.time_epoch,frame.len,tcp.srcport,tcp.dstport,tcp.len
```

要得到 TCP 分析错误计数，再加入 `tcp.analysis.retransmission`、`tcp.analysis.fast_retransmission`、`tcp.analysis.spurious_retransmission`、`tcp.analysis.lost_segment`、`tcp.analysis.duplicate_ack`、`tcp.analysis.out_of_order`、`tcp.analysis.zero_window` 和 `tcp.flags.reset`。缺少的可选列在 JSON 中为 `null`，不能当成 0；任一约定列缺失时，字节和峰值仍可作为局部证据，但完整抓包门的 `formalEvidenceReady` 必须为 false。脚本输出每方向的总 packet/data-packet、`sum(tcp.len)`、`sum(frame.len)`、每秒速率、50ms/1s 峰值及可用的 TCP analysis 标志；同时记录源文件和 tshark 二进制 SHA-256、字段可用性、窗口、行数对账和证据边界。

这里的 packet 是抓包中该 component 出现的一条 TCP frame，不是 Minecraft 逻辑包或 Bundle 内子包；`tcp.len` 是加密/压缩后的 TCP payload，`frame.len` 是该抓包点报告的 frame 长度，不包含无法由来源恢复的链路信息。重传、duplicate ACK、lost segment 和 out-of-order 是 tshark 启发式判断。捕获 drop/events lost、截断和 component 重复不能从这些字段推导，仍须联读 counters JSON、stats TXT 和 completed capture sidecar。`CaptureComponent all` 的 ETL 必须先按 sidecar 选择唯一 `ComponentId` 再转换，禁止把多个 Appearance 相加。

### 5.4 无管理员权限的服务器出站证据：JFR SocketWrite

JDK Flight Recorder 的 `jdk.SocketWrite` 记录 Java socket write 次数、远端地址/端口和 `bytesWritten`。`jcmd` 必须在服务器 JVM 所在机器、由启动该 JVM 的同一 OS 用户执行；这不需要也不应通过 UAC 提权。仓库提供 [`tools/perf/phase2-socket-write.jfc`](../tools/perf/phase2-socket-write.jfc)，对短时验证关闭 threshold/throttle 并关闭 stack trace，同时启用 `jdk.DataLoss` 守门。

服务器视角的 JFR `RemotePort` 是客户端临时端口，不是服务端监听的 25566。开始前先保存连接映射：

```powershell
$serverPid = 12345
$runId = "S2_A_01"
$jfc = (Resolve-Path .\tools\perf\phase2-socket-write.jfc).Path
$jfr = "D:\iv-ab\2026-07-13\$runId.jfr"

Get-NetTCPConnection -OwningProcess $serverPid -LocalPort 25566 -State Established |
    Select-Object LocalAddress,LocalPort,RemoteAddress,RemotePort,OwningProcess

& jcmd $serverPid JFR.start "name=$runId" "settings=$jfc" disk=true
# 与 /iv perf start/stop 对齐执行固定窗口
& jcmd $serverPid JFR.stop "name=$runId" "filename=$jfr"

& .\tools\perf\analyze-jfr-socket-writes.ps1 $jfr `
    -RemoteAddress 127.0.0.1 -RemotePort 52144 -DurationSeconds 180 `
    -OutputJson "D:\iv-ab\2026-07-13\$runId.socket-writes.json"
```

分析器调用同 JDK 的 `jfr print --json`，按客户端 endpoint 汇总实际 Java socket write 次数、字节数和 50ms/1s 峰值；存在任何 `jdk.DataLoss` 就拒绝结果。同机 loopback 不经过物理 NIC 时，这条证据仍可工作。正式前仍必须校准：固定动作应产生非零增量；如果服务器使用绕过 JDK socket instrumentation 的 native transport，JFR 可能漏记，不能继续把它当作完整字节证据。

关闭 threshold/throttle 会提高事件量，必须先做 capture-off/on 控制，并把 JFR 字节轮与干净 MSPT 轮分开；A/B 两边使用相同 JFC 和窗口。JFR 提供的是 JVM 写调用及 payload bytes，不是 Minecraft 逻辑包数、TCP segment、on-wire bytes、重传、丢包或协议字段，因此不能替代 pktmon/pcapng 的兼容性抓包。

### 5.5 无管理员权限的客户端证据：原版 F3+L profile

原版客户端按一次 `F3+L` 开始 profiling，固定窗口结束再按一次停止，随后在客户端目录 `debug/profiling` 生成 ZIP。ZIP 内的 `client/metrics/ticking.csv` 包含逐客户端主循环的 `ticktime`（纳秒）。它比抄录 F3 单点 FPS 或截图帧时图更适合 ABBA：逐样本、可哈希、可自动计算。

```powershell
& .\tools\perf\analyze-minecraft-debug-profile.ps1 `
    "D:\iv-ab\2026-07-13\S2_A_01.minecraft-profile.zip" `
    -TrimStartSeconds 10 -TrimEndSeconds 10 `
    -OutputJson "D:\iv-ab\2026-07-13\S2_A_01.minecraft-profile.json"
```

分析器输出 client main-loop FPS、p50/p95/p99 loop time 和 1%/0.1% low，并检查 `@tick` 缺口。F3+L profiling 本身也有开销，A/B 必须同样开启、使用同一客户端和相机脚本。F3 帧时图或截图仍适合发现卡顿形状和视觉错误，但没有可审计的逐帧导出，不作为正式数值来源。

这个 `ticktime` 覆盖客户端主循环、渲染和 FPS limiter，但看不到 OS compositor 的实际 `DisplayedTime`，也不能识别未显示帧。因此它可以作为无需管理员权限的强 client-loop 回归门，不能替代 PresentMon 的 displayed-frame/dropped-frame 结论。

## 6. 指标

### 6.1 `/iv perf`

- `tickSamples / seconds`：实际完成 tick 的平均速率；TPS 在健康状态会封顶 20，因此优化判断以 MSPT 为主。
- `msptP50/P95/P99/P999/Max/Mean`、`ticksOver50ms`。
- `virtualSpawnBundles`、`virtualMotionBundles`、`virtualTeleportBundles`、remove、pickup。
- `bukkitEntitySpawns/Removes/Teleports`、show/hide。
- `displaySyncs`、`itemSyncs`、`virtualViewerChecks`。
- 通用限流的 `visibilityShowsQueued`、`visibilityShowsDrained`；旧 `DroppedItemDisplay` 标签桶不使用这两个计数器。
- `blockUpdateChecks`、`blockUpdateMs` 只统计各 display type 子 updater 的实际检查/更新调用；legacy parent collection iteration 和旧 task scheduling 不在该计时器内，必须从 MSPT/整体热路径判断。`Settings.Performance.BlockUpdates.MaxDirtyPerTick` 是每个 display type 的独立 dirty budget，不是所有熔炉类型共享的全局上限。
- `itemAnimationMs`、`droppedItemMs`，需要除以 seconds、item 数和 viewer 数后比较。

`knownVirtualPackets` 是插件已知的顶层 Sparrow 调用/Bundle 数，不等于 Bundle 内逻辑包数、TCP segment 数或编码后字节。

### 6.2 pktmon/pcapng

- 下行和上行 TCP data segments/s。
- `sum(tcp.len)`：TCP payload bytes/s。
- `sum(frame.len)`：on-wire bytes/s。
- 50ms 和 1s 分桶峰值。
- retransmission、drop、events lost。

`pktmon counters --json` 用于快速确认包计数和捕获异常；精确 bytes 与时间分桶由 `analyze-phase2-pcap.ps1` 从 pcapng 或预导出的字段 CSV 计算。正式比较使用 `downstream.tcpPayloadBytes`、`downstream.onWireBytes`、`downstream.peak50ms.tcpPayloadBytes` 等同名 JSON 路径，并检查 `window`、`rowAccounting`、`analysisFieldAvailability` 和 `formalEvidenceReady`。没有 tshark/字段 CSV 时只能保留 ETL、pcapng、counters JSON 和 stats TXT 作为待分析原始证据，不能从 counters 猜测字节数或 50ms 峰值。

### 6.3 JFR SocketWrite

- `socketWriteEvents/s`：JVM 对指定客户端 endpoint 的实际 write 调用率，不等于逻辑包或 TCP segment。
- `socketWriteBytes/s`：JVM 交给 socket 的 payload bytes；用来补强 `knownVirtualPackets`，但不包含 TCP/IP header、重传和链路开销。
- 50ms/1s bucket 峰值：检查 spawn/show burst 是否削峰。
- `jfrDataLossBytes` 必须为 0；同时保留原始 `.jfr`、endpoint 映射和分析 JSON。

### 6.4 客户端 FPS

机器人客户端不能验证 FPS。使用真实渲染客户端和 PresentMon/CapFrameX，固定：

- 客户端版本、JVM、模组、资源包和 GPU 驱动。
- 分辨率、窗口模式、渲染距离、VSync 和 FPS cap。
- 相同相机路径及停留时间；服务端和客户端最好分机。

使用官方 PresentMon 2.5.1 Console Application 作为标准采集器。以下示例采集 200 秒，并在分析时从首尾各裁掉
10 秒，得到与服务端相同的 180 秒稳定窗口；`$minecraftProcessId` 必须是 Minecraft 渲染客户端 PID，而不是服务端 PID：

```powershell
$presentMon = "D:\tools\PresentMon-2.5.1-x64.exe"
$minecraftProcessId = 12345
$csv = "D:\iv-ab\2026-07-13\S2_A_01.presentmon.csv"
$json = "D:\iv-ab\2026-07-13\S2_A_01.presentmon.json"

& $presentMon --process_id $minecraftProcessId --output_file $csv --v2_metrics `
    --timed 200 --terminate_after_timed --no_console_stats

# 第一次可省略 -SwapChain；若 CSV 有多个流，分析器会拒绝并列出可选地址。
& .\tools\perf\analyze-presentmon.ps1 $csv -Process $minecraftProcessId `
    -SwapChain '0x00000123456789AB' -TrimStartSeconds 10 -TrimEndSeconds 10 `
    -OutputJson $json
```

命令中绝不能加入 `--exclude_dropped` 或 `--no_track_display`。每个结果必须只含一个 PID 和一个
`SwapChainAddress`；不能把启动器、加载画面、游戏主窗口或多个 swap chain 的行相加。保留原始逐帧 CSV 和分析 JSON，比较平均 FPS、1%/0.1% low、p95/p99 frame time 和 dropped frames。

统一口径如下；不得拿采用其他 percentile/low 定义的 CapFrameX 汇总值直接混入同一组：

- mean FPS = displayed frame count / `sum(DisplayedTime)` 秒。
- p95/p99 frame time = 对已显示帧时长升序排列后的 nearest-rank 百分位。
- 1%/0.1% low = `1000 / mean(worst ceil(N * fraction) displayed frame times in ms)`。
- dropped ratio = `DisplayedTime == NA` 的行数 / 裁剪后总行数；只有保留 dropped rows 时有效。

如使用 CapFrameX，只把它作为视觉路径/采集稳定性的交叉检查；正式 A/B 数值仍由同一版本 PresentMon CSV 经过上述脚本计算。

## 7. 通过阈值

所有优化先通过行为硬门，再看性能：

- 无服务端异常、客户端断连、ghost、duplicate 或清理后残留包。
- `droppedTickSamples == 0`，tick 样本与窗口长度相符。
- 优化目标指标的配对中位数至少改善 5%，且 bootstrap 95% CI 不跨 ratio 1.0。
- 守门指标：p95 MSPT ratio 的 CI 上界不超过 1.02；p99 不超过 1.05；无理由的总 wire bytes 增量不超过 3%；重传和错误不得增加。

专项阈值：

- 纯发包静态：`bukkitEntitySpawns == 0`；steady 的 virtual/Bukkit teleport 为 0；4096 个展示不能令服务端实体数随 count 增长；steady 下行不超过 idle + 1%。
- 静止动画锚点：`bukkitEntityTeleports` 至少下降 95%；`virtualTeleportBundles` 与 A 相差不超过 1%；客户端轨迹和拾取终点一致。
- 通用 128/32 可见性：每玩家首个 drain tick show 不超过 128，后续每 tick 不超过 32；2048 个 `DisplayManager` 实体不超过 63 ticks 完成；`visibilityShowsQueued/Drained` 与去重、取消结果一致；50ms 峰值下行 payload 至少下降 70%，完整收敛窗口总 payload 与 A 相差不超过 5%。
- 旧 dropped-label 128/32 回归：同样满足 128/32 与 63-tick 收敛上限，但使用真实标签的 Bukkit show/hide、ghost 检查和抓包计数，不得引用通用队列计数器作为证据。
- 事件驱动 idle：1024 idle block 的相关 CPU/累计耗时至少下降 70%；100% active 的 p95/p99 不超过通用守门；标准事件在 2 ticks 内反映，非标准直接写入在约定 fallback audit 周期内修正。
- FPS：p95 frame time 不恶化超过 2%，1% low 不下降超过 3%，且没有视觉正确性回归。

若只达到“不回退”而未达到改善阈值，不称为优化；保留数据后继续寻找瓶颈。

## 8. CraftEngine 借鉴范围与实现边界

第二阶段只借鉴 CraftEngine 在固定版本中的三类结构：常量 Item/Text 展示预生成包、玩家区块 send/forget 生命周期，以及由方块状态变化驱动的增量更新。参考代码固定到 commit `22fe37c023ba348bac6602302dbcc08bee6d4860`，避免把上游后续变化混入本轮 A/B：

- [ItemDisplayBlockEntityElement](https://github.com/Xiao-MoMi/craft-engine/blob/22fe37c023ba348bac6602302dbcc08bee6d4860/bukkit/src/main/java/net/momirealms/craftengine/bukkit/block/entity/renderer/constant/ItemDisplayBlockEntityElement.java) 与 [TextDisplayBlockEntityElement](https://github.com/Xiao-MoMi/craft-engine/blob/22fe37c023ba348bac6602302dbcc08bee6d4860/bukkit/src/main/java/net/momirealms/craftengine/bukkit/block/entity/renderer/constant/TextDisplayBlockEntityElement.java)：静态展示缓存与复用包的设计参考。
- [CEChunk](https://github.com/Xiao-MoMi/craft-engine/blob/22fe37c023ba348bac6602302dbcc08bee6d4860/core/src/main/java/net/momirealms/craftengine/core/world/chunk/CEChunk.java#L114-L145)：玩家区块发送/遗忘生命周期参考。
- [WorldStorageInjector](https://github.com/Xiao-MoMi/craft-engine/blob/22fe37c023ba348bac6602302dbcc08bee6d4860/bukkit/src/main/java/net/momirealms/craftengine/bukkit/plugin/injector/WorldStorageInjector.java#L88-L157) 与 [CEWorld](https://github.com/Xiao-MoMi/craft-engine/blob/22fe37c023ba348bac6602302dbcc08bee6d4860/core/src/main/java/net/momirealms/craftengine/core/world/CEWorld.java#L62-L73)：状态变化汇聚和增量 tick 参考。

本项目没有照搬 CraftEngine 的自定义方块存储、NMS 注入或 ray tracing。纯发包范围严格限于无重力、零速度、无可见自定义名且不发光的逻辑 `Item`；`DisplayEntity`/`TextDisplay` 仍走现有 Paper 实体路径。所有开关默认关闭，只有独立抓包、MSPT/FPS A/B 和兼容性清单通过后才讨论默认启用。

## 9. 兼容性清单

三项候选没有统一的 Phase 2 总开关，必须独立启用、独立回退：

- `Settings.Performance.VirtualItems.PacketOnlyStatic` 和
  `Settings.Performance.VisibilityRateLimit.Enabled` 默认均为 `false`，修改后可用 `/iv reload`
  热切换。关闭通用 limiter 会立即 drain 已排队的 show；hide 始终即时。
- `Settings.Performance.BlockUpdates.EventDriven` 默认 `false`，监听器只在启动时注册；启停后必须完整重启。
  关闭并重启后回到 legacy 周期扫描。
- `Entities.Item.Options.VisibilityCulling.Enabled` 默认 `false`，其 `ViewDistance` 默认值为 `64`；
  `Entities.Item.Options.VisibilityRateLimit.Enabled` 默认 `false`。两者可独立通过 `/iv reload` 热切换，
  任一项启用都会进入逐玩家可见性控制；关闭两者会清空 pending 并立即恢复 legacy show/hide 生命周期。
- `PacketOnlyStatic: false` 仍是“Bukkit `ItemDisplay` 追踪锚 + Sparrow 假 `ITEM` 包”，不是完全退出
  NMS/发包；Leaf/NMS 兼容失败不能靠关闭此开关规避。
- `Entities.Item.Options.VisibilityRateLimit` 是 dropped-item TextDisplay 标签的独立令牌桶，和上面的
  `DisplayManager` 通用 limiter 不是同一个开关或同一份证据。

兼容性按独立 stack 执行，不能用 Paper 的结果替代 Leaf 或协议翻译链：

1. Paper 26.1.2 + Java 25 + 同协议原版客户端，不安装协议拦截插件，作为基线。
2. 生产若支持 26.2，在对应 Paper build 和原生客户端复测。
3. 在目标 Leaf build 上完整启服、抓包并复测；这里依赖 Sparrow/NMS，不能只引用 Paper API 兼容声明。
4. 分别加入生产实际使用的 ProtocolLib 或 PacketEvents，再检查 outgoing entity bundle 是否被修改或取消。
5. ViaVersion/ViaBackwards 必须覆盖同协议和每个生产允许的跨版本客户端；若有代理或 Geyser，再分别保存
   后端与前端抓包并使用真人 Java/Bedrock 客户端。

`plugin.yml` 的 `api-version` 为 26.1.2；Paper/Leaf 1.21.11 会在 Phase 2 开关读取前拒绝加载，不属于本轮兼容目标。

每个上述 stack 至少执行以下清单：

- 1、2、10 名 viewer；晚加入和不同方向观看。
- 首次进入、快速跨越 64m、反复进出、传送、世界切换。
- 区块 unload/reload、死亡/重生、退出重连、模块 toggle 和插件 reload。
- 排队显示期间物品被移除或 viewer 离线时，pending 必须取消且无 ghost。
- 熔炉、鼓风炉、烟熏炉四侧和俯视都保持交互方块正面；TextDisplay 高度正确。
- 蜂箱和蜂巢方向正确。
- 方块上方阻挡时动画不被挤出。
- 附魔描述文字可见，完成后 8-tick 回收与背包拾取动画正确。
- 静态物品无漂移；运动物品重力、空气阻力、客户端校正和拾取终点一致。
- 关闭/清理后无客户端残留实体；两名 viewer 不互相污染可见性状态。

Pktmon 只能证明传输行为，不能证明朝向、高度、遮挡、动画终点或 ghost。以上视觉项目必须由真实客户端录像或逐项人工勾选。

## 10. 每个 run 必须保存

- baseline/candidate commit 和 jar SHA-256。
- Paper、Java、JVM、配置、世界快照标识。
- 场景、seed、item/block/viewer 数、ABBA 顺序和 run 编号。
- `IV_PERF` JSONL。
- pktmon RunId claim、capture manifest（异常时保留 starting/recovery 状态，正常时为 completed）、ETL、counters JSON、stats TXT、pcapng、component ID、`*.pcap-analysis.json`，以及使用预导出路径时的字段 CSV。
- 服务端日志、错误数、PresentMon 版本与二进制 SHA-256、客户端逐帧 CSV、分析 JSON、兼容性勾选结果。

缺少上述原始证据的结果只能作为线索，不能作为性能结论或默认启用优化的依据。尚未完成行为硬门的
路径必须在合并前保持默认关闭或从 PR 拆出；默认关闭的实现不代表该优化已经通过生产启用门槛。

## 11. GitHub 隔离运行门

仓库提供两个**互相独立**的运行门；默认分支可手动触发，普通 PR 只自动运行短烟测，不会自动消耗
正式长跑所需的 runner 时间：

- `.github/workflows/phase2-runtime-ab.yml`：不启动抓包、JFR 或 profiler，使用真实 Paper JVM 与真实
  26.1.2 TCP play-state 客户端，按 `ABBA / BAAB / ABBA` 重启 12 次，只用于 `observedTps`、
  `msptMean`、`msptP95` 和 `msptP99`。`observedTps` 是该窗口内完成 tick 的墙钟吞吐；健康服已被
  20 TPS 上限截顶时只用于过载/非劣判断，不能把边界抖动解释为优化，效应量仍以 MSPT 为主。
- `.github/workflows/phase2-packet-capture.yml`：同样逐轮重启，但启动 `tcpdump` 并在服务端停止采样后才运行
  `tshark`。`static-spawn` 比较纯发包静态展示，`visibility-return` 比较 50 ms/1 s 可见性恢复峰值；
  每轮动作与背景流量共用严格等长窗口，该轮的 MSPT 不得和 clean 轮配对。GitHub runner 只抓 `lo`，
  因此正式配对指标限于 TCP payload 总量与 payload 时间桶；loopback 伪链路头和 offload 下的
  `frame.len` 不能称作物理网卡 on-wire 字节或包数。

协议客户端只用于让 Paper/Sparrow 经过真实 tracker、chunk 与 TCP 路径，不渲染画面。准备步骤固定
`node-minecraft-protocol`、wrapper 和协议数据的三个 40 位提交，在无 secret 的只读 job 中禁用 npm
lifecycle scripts，并保存完整 lockfile、由 lockfile 派生的生产依赖清单和逐文件 SHA-256。该依赖仍来自尚未合并的上游 PR，
因此自动结果是性能/协议回归证据，不替代原版客户端、Leaf、Via/代理/Geyser 的真人兼容性勾选。

正式结论必须使用默认 12 runs、120 秒预热、20 秒稳定和 180 秒 clean 采样。4/8 runs 仅用于验证
harness；GitHub shared runner 的结果若接近噪声阈值，应在专用 self-hosted runner 上用同一套脚本复测。

新 workflow 尚未进入默认分支时，GitHub 不允许直接 `workflow_dispatch`。因此 PR 的
opened/synchronize/reopened 事件只跑 4 runs、1024 items 的短烟测；添加 `phase2-runtime-formal`、
`phase2-packet-static-formal` 或 `phase2-packet-visibility-formal` 标签才会分别启动对应的 12-run 正式门。
其他标签不会误触发长任务。
