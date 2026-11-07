# MTR4 → MTR3 存档转换器（MVP）

把 **MTR4（Minecraft Transit Railway 4.x，`org.mtr.*`）** 存档里的铁路数据，转换成
**MTR3 / YMTR（`mtr.*`）** 能读的存档。

* 便携 Java jar，Swing 界面，**零外部依赖**（MessagePack 编解码、轨道几何全部自己实现，没有打包任何第三方库）
* 目标 Java 17（`--release 17`），在 Java 17/21 上都能跑
* **源存档只读**：先整份复制，再只在副本上转换

```
build/MTR4toMTR3.jar          ← 双击运行（或 java -jar）
```

**下载**：从 [Releases](https://github.com/longqiuting/mtr4-to-mtr3-save-converter/releases)
拿 `MTR4toMTR3.jar`（约 68 KB，Java 17，零依赖，不需要编译）。当前版本 **v0.2.0**。

---

## 0. 为什么上一版产物「文件都在、游戏里却什么都没有」（0.2.0 修复）

**MTR3 只有在 `<世界>/data/mtr_train_data.dat` 存在时，才会去扫描 `mtr/` 文件树。**

* `RailwayData.getInstance(world)` 用 `PersistentStateMapper.getInstance(world, supplier, "mtr_train_data")`
  取数据；这个 state 的 reader（`PersistentStateMapper` 里的 `load(tag)` lambda）是**唯一**会走到
  `RailwayData.load(CompoundTag)` → `RailwayDataFileSaveModule.load()` 的入口，
  而后者才是**唯一**会 `Files.list(mtr/<ns>/<dim>/<kind>/…)` 的代码。
* Minecraft 1.20.1 的 `PersistentStateManager.get(reader, supplier, name)`：文件存在 → 调 reader；
  **文件不存在 → 直接 `supplier.get()` 新建空对象，reader 永不调用**。
* MTR4 从不写这个文件（它写的是 `data/mtr.dat`），所以整份复制过去的 MTR4 世界没有它
  → MTR3 以空存档启动 → `mtr/` 下所有文件一个都不读 → 轨道/车站/车厂全消失。
  日志里的表现是：连 `Minecraft Transit Railway data successfully loaded` 都不打印。

修复：转换时按维度写 `<世界>/data/mtr_train_data.dat`、`DIM-1/data/…`、`DIM1/data/…`、
`dimensions/<ns>/<dim>/data/…`，内容为 `{DataVersion:<int>, data:{use_time_and_wind_sync:0}}`
（与 MTR3 自己写的逐字节相同）。

**故意不写 `raw_message_pack`**：该键一旦存在，`RailwayData.load(tag)` 会走进"内联 msgpack 快照"分支，
同样跳过整个 `mtr/` 文件树 —— 换一种方式读不到。

顺带对齐的线上类型：`rail_length`、`acceleration_constant`、`rolling_offset`、`vertical_curve_radius`
改为 float32（MTR3 原生写 f32），`depots` 去掉 MTR3 从不写的 `last_deployed`。

详见 `REPORT-根因与验证.md`（含安全快照、字节码证据、逐项对比）。

---

## 1. 用法

### 图形界面

双击 `MTR4toMTR3.jar`。

1. **源存档目录**：MTR4 实例 `versions/<实例>/saves/<世界>`（必须含 `mtr/`）
2. **目标实例目录**：装了 MTR3 的实例根目录（`versions/<实例>/`，含 `mods/` 与 `saves/`）
3. **输出存档名**：默认 `<原名>-MTR3`
4. 点「预检」看对象数量、目标版本、输出位置是否可用；点「开始转换」
5. 结果写在下面的报告区，可「导出报告…」存成 txt

### 命令行

```bash
java -jar MTR4toMTR3.jar --cli <源存档目录> <目标实例目录> [输出存档名]

# 例：
java -jar MTR4toMTR3.jar --cli \
  "…/versions/Tianyicraft/saves/新的世界" \
  "…/versions/1.20.1-Fabric 0.19.3"

# 用真实 MTR3 轨道数据校验内置几何算法（不需要开游戏）
java -jar MTR4toMTR3.jar --selftest "…/saves/测试摄影专用"

# 只读校验任意存档：逐键比对 MTR3 原生 schema + 检查 data/mtr_train_data.dat
java -jar MTR4toMTR3.jar --verify "…/saves/新的世界-MTR3-v2"
java -jar MTR4toMTR3.jar --verify "…/saves/测试摄影专用"   # 校验器自身可信度
```

退出码：`0` 成功，`1` 有失败项，`2` 参数/前置条件错误。

---

## 2. 备份与安全策略

**为什么不能直接拿 MTR3 打开 MTR4 存档？**
MTR3 的 `RailwayDataFileSaveModule` 有 `checkFilesToDelete`：它读不懂的字段一律取默认值，
随后自动存档会把 `mtr/` 下「它没写过」的文件删掉、把它认识的文件覆盖成空对象。**MTR4 存档会被静默毁掉。**

所以本工具的执行顺序是固定的：

| 步骤 | 行为 |
|---|---|
| 1 | 只读扫描源存档的 `mtr/`（不写、不改名、不加锁） |
| 2 | 检查目标实例确实装了 MTR3（`mods/` 里有 `id=mtr` 且主版本 = 3），装的是 MTR4 就直接拒绝 |
| 3 | 输出目录 `<目标实例>/saves/<名字>` **已存在就中止**，绝不覆盖 |
| 4 | 把整个世界（**不含 `mtr/`**、不含 `session.lock`）复制到输出目录 |
| 5 | 把源 `mtr/` 原样备份到 `<输出存档>/.mtr4-backup/mtr/`，并写 `.mtr4-backup/MANIFEST.sha256`（逐文件 SHA-256） |
| 6 | 在 `<输出存档>/mtr/` 里重新生成 MTR3 数据树（旧文件一个都不带过去） |
| 7 | 回读校验：每个输出文件重新解码，检查 MTR3 必需的键齐不齐 |

**回滚方式**：删掉输出存档的 `mtr/`，把 `.mtr4-backup/mtr/` 复制回去 —— 就回到了转换前的 MTR4 数据（想换个参数重转时用）。

**转换完成后，先用 MTR3 打开「新的这个副本」确认没问题，再去考虑源存档要不要保留。**
在确认之前不要让 MTR3 碰源存档。

---

## 3. 转换了什么

| 类型 | MTR4 字段 | MTR3 字段 | 变换 |
|---|---|---|---|
| 车站 `stations` | `position1/2`、`zone1`、`exits[]` | `x_min/z_min/x_max/z_max`、`zone`、`exits{}` | 2D 投影；exits 数组→按 name 建 map |
| 站台 `platforms` | `position1/2`、`dwellTime` | `pos_1/pos_2`、`dwell_time` | BlockPos 打包；**dwellTime ÷ 500** |
| 线路 `routes` | `routePlatformData[]`、`hidden`、`routeNumber` | `platform_ids[]`+`custom_destinations[]`、`is_route_hidden`、`light_rail_route_number` | 拆平铺数组 |
| 车厂 `depots` | `routeIds`、`frequencies`、`realTimeDepartures` | `route_ids`、`frequencies[24]`、`departures` | 补齐 24 档频率 |
| 侧线 `sidings` | `railLength`、`maxVehicles`、`acceleration` | `rail_length`、`max_trains/unlimited_trains`、`acceleration_constant` | **acceleration × 2500** |
| 轨道 `rails` | 每段「端点+角度+shape」 | 每节点「邻接表 + h/k/r/t 曲线参数」 | 由端点和角度**反算曲线参数**，两个方向各写一条邻接记录 |
| 其他 kind | — | — | 原样复制（`settings` 除外） |

### 轨道是怎么转的（关键部分）

MTR4 存的是「一段轨道的两端点 + 两端朝向角 + 形状枚举」，MTR3 存的是「节点邻接表 + 曲线参数
`h1,k1,r1,tStart1,tEnd1,h2,k2,r2,tStart2,tEnd2`」。两边其实是同一套几何：
MTR4 的 `org.mtr.core.data.RailMath` 构造函数与 MTR3 的 `mtr.data.Rail(BlockPos, RailAngle, …)`
构造函数**逐行等价**。工具把那段公式完整移植成 `RailGeom.java`，因此是**精确反算，不是拟合**。

验证方式（三层，全部离线）：

1. **差分测试**：用 URLClassLoader 把 MTR4 jar 里的真 `RailMath` 直接跑起来，和移植版逐字段比对
   —— 3 万个随机轨道 + 本机 3 个存档共 161 段真实轨道，**数值与布尔字段 0 差异**。
2. **反向记录约定**：MTR3 自己的存档里，A→B 与 B→A 两条记录的关系是「端点互换、角度不变」
   （`--selftest` 在 1,706 对真实双向记录上验证：**0 处不一致**；用「角度取反」的写法几乎全错）。
   工具按这个约定写反向记录。
3. **几何自检**：每段轨道算出参数后，再用参数把两端点反算回来，与原始坐标比对（容差 0.75 格）；
   对不上就跳过该段并写进报告的「跳过」明细，**不会**写出坏数据。

`--selftest` 就是第 1、2 层的可复跑版本。

---

## 4. 已知丢失项（MTR3 数据模型里没有的东西）

| 丢失内容 | 处理 |
|---|---|
| 车站的 Y 范围（MTR4 是 3D 盒，MTR3 只有 2D 矩形） | 投影到 x/z；同一投影上下两层站会合并 |
| 车站 `zone2/zone3` | 只保留 `zone1` |
| 每段轨道的 `styles[]`（模型/装饰） | 丢弃（MTR3 没有 per-segment 模型概念） |
| 每段轨道的 `speedLimit1/2` | 按速度档位映射到最接近的 `rail_type`（WOODEN 20 / STONE 40 / EMERALD 60 / IRON 80 / OBSIDIAN 120 / BLAZE 160 / QUARTZ 200 / DIAMOND 300） |
| `canConnectRemotely`、`canHaveSignal`、`signalColors[]` | 丢弃；MTR3 的信号是独立的 `signal-blocks` 对象，MVP 不重建 |
| 侧线的 `vehicleCars[]`（显式车厢几何） | 无法反查车型，统一写 `train_type = train_24_2`（SP1900），需游戏内改 |
| 侧线的 `path*` | 置空，由 MTR3 在车厂发车时重新计算 |
| `routeNumber` 带来的 `is_light_rail_route` | 非空则置 true（原布尔值不可恢复） |
| `data/mtr.dat` 里的 `route_ids_with_disabled_announcements` | MVP 未解析 gzip+NBT，`disable_next_station_announcements` 一律 false |
| 车厂 `lastGenerated*` 生成状态、`settings{lastSimulationMillis}` | 丢弃（运行时状态） |
| 电梯 `lifts`、信号 `signal-blocks` | **原样复制**（MVP 未实现字段映射）。MTR3 读到时可能取默认值，**建议游戏内复查或重建** |
| 列车实体、方块层（11 个 MTR4 新方块） | 未处理。列车由车厂重新生成；`*_slab`/`mosaic_tile`/`driver_key_dispenser` 等方块仍是 MTR4 方块，MTR3 里会变成空气/未知方块 |

---

## 5. MTR4 版本差异怎么处理

映射表是按**字段名**写的，而 4.0.x 的 `mtr/` 字段名没有变过，所以核心逻辑对 4.x 是稳定的。
真正会变的是：新增字段（忽略即可）、字段类型变化（读取端做了容错，例如 `routeNumber` 同时接受
整数和字符串）。

工具在转换前从**实例的 `mods/*.jar` 元数据**（`fabric.mod.json` 的 `id`，或 Forge `mods.toml`
里 `[[mods]]` 块的 `modId`）读版本号，而不是猜文件名——注意只认 `[[mods]]` 块，
依赖块里的 `modId="mtr"` 不会被误当成主模组（BTE 之类的附属就踩过这个坑）。

策略是**版本白名单 + 未知版本警告**：

```java
KNOWN_SOURCE = {"4.0.5"}      // 已验证
KNOWN_TARGET = {"3.6.4.2"}    // 已验证
```

* 命中白名单：静默转换
* 没命中：**照转，但在报告头部写「版本 X 不在已验证白名单内，字段映射按 4.0.x / 3.6.4.2 处理，请核对报告」**
* 目标实例装的是 MTR4（主版本 ≥ 4）：直接拒绝
* 目标实例没有 `id=mtr` 的模组：直接拒绝

升级兼容的推荐流程：新版 MTR4 出来以后，先拿它生成一个小存档跑 `--cli`，把报告里的
「跳过 / 失败」和 `.mtr4-backup/MANIFEST.sha256` 一起看一遍，再决定是否把版本号加进白名单。
已知必须重新核对的点：`dwellTime` 的倍率（500）、`acceleration` 的倍率（2500）、
`RailMath` 的公式（如果 MTR 改了曲线算法，差分测试会立刻发现）。

---

## 6. 构建

需要 JDK 17+。

```bash
bash build.sh        # Linux/macOS/git-bash
build.bat            # Windows cmd
```

产物 `build/MTR4toMTR3.jar`（约 68 KB，零外部依赖）。源码结构：

```
src/mtrconv/Main.java        Swing 界面 + CLI
src/mtrconv/Converter.java   转换引擎（复制/备份/映射/轨道/校验/报告）
src/mtrconv/MsgPack.java     手写 MessagePack 读写（含 append-log 容错）
src/mtrconv/RailGeom.java    MTR RailMath 公式移植
src/mtrconv/Nbt.java         手写 NBT（读 level.dat 的 DataVersion / 写 mtr_train_data.dat）
src/mtrconv/Schema.java      MTR3 3.6.4.2 的键集合与类型（从原生存档 + 字节码抄下来）
src/mtrconv/Verify.java      --verify 只读校验
src/mtrconv/Versions.java    mods/*.jar 版本探测 + 白名单
src/mtrconv/Report.java      报告生成
src/mtrconv/SelfTest.java    --selftest 几何校验
dev/                         开发期差分测试（不打包进 jar）
```

---

## 7. 还没做的（MVP 之外）

* 电梯 / 信号机的字段映射（现在只是原样复制）
* 方块层替换（11 个 MTR4 专有方块）
* 世界级 `data/mtr.dat`（关闭报站的线路清单）
* 车站多层拆分
* 大世界性能（现在整档读进内存；样本 164 KB 无压力，几万段轨道需要改成流式 + 进度条）
