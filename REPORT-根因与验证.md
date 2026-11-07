# MTR4 → MTR3 转换器：根因修复与验证报告

版本：`0.2.0`（`C:\Users\AD\Desktop\MTR4toMTR3.jar`）
产物：`…\versions\1.20.1-Fabric 0.19.3\saves\新的世界-MTR3-v2\`（新建）

---

## 0. 安全快照对比（先看这个）

对 4 个受保护目录在**转换前**与**转换后**各做一次「完整文件清单 + 逐文件 SHA-256」：

| 目录 | 文件数 |
|---|---|
| `saves/测试摄影专用/` | 575 |
| `saves/绫洛市/` | 6556 |
| `saves/新的世界-MTR3/`（旧的失败产物） | 212 |
| `Tianyicraft/saves/新的世界/`（源存档） | 171 |
| **合计** | **7514** |

```
$ bash dev/snapshot.sh dev/snap-before.txt <四个目录>
$ bash dev/snapshot.sh dev/snap-after.txt  <四个目录>
$ diff dev/snap-before.txt dev/snap-after.txt
（无输出）
```

**结论：7514 / 7514 个文件哈希完全一致，0 变化。** 四份存档（含旧失败产物）在整个调查与转换过程中未被写入、改名、删除或移动。
写入只发生在本次新建的 `saves/新的世界-MTR3-v2/`（以及项目目录 `D:\MTR4-to-MTR3-Tool`）。
未启动游戏。所有参照数据均以只读方式读取。

代码层同时加了硬保护：
* 输出目录已存在 → 直接中止（不覆盖、不清空）；
* 所有写操作经过 `Converter.guard(Path)`：`toAbsolutePath().normalize().startsWith(OUTPUT_ROOT)` 校验，越界抛异常中止；
* 实现里没有任何 `Files.delete` / `Files.move` / rename 作用于源或既有存档。

---

## 1. 根因

**MTR3 只有在 `data/mtr_train_data.dat` 存在时才会去扫描 `mtr/` 文件树。MTR4 从不写这个文件，所以上一版产物虽然 `mtr/` 目录完全正确，MTR3 却从未读过它。**

### 证据链（从"看起来没问题"到"为什么是空的"）

1. **目录树与命名完全一致**，不是路径问题。
   参照存档与产物都是 `mtr/<namespace>/<dimension>/<kind>/<id % 100>/<id>`（文件名无后缀、十进制有符号 long、桶目录 = `id % 100`）。MTR3 的 `RailwayDataFileSaveModule.writeMessagePackToFile` 里就是 `resolve(String.valueOf(id % 100)).resolve(String.valueOf(id))`，逐字吻合。

2. **单文件 msgpack 结构一致**，不是字段问题。
   逐键解码 stations / platforms / routes / depots / sidings / rails 后，与参照存档键集合相同（见 §4）。也就是说：即使 MTR3 读了，也能解析。

3. **游戏日志给出了决定性反证**：打开失败产物时，MTR3 一句话都没说。
   `logs/2026-11-06-4.log.gz`（18:07 启动，18:09:32 载入 `新的世界-MTR3`，18:10:00 退出）里 `grep -c 'Transit Railway'` = **0**：
   * 没有 `Minecraft Transit Railway data successfully loaded for minecraft:overworld`
   * 也没有退出时的 `Minecraft Transit Railway save complete … / - Changed: N`

   同一实例载入 `测试摄影专用`（18:14、18:23）与 `绫洛市`（12:45）时，这三行都在。
   → 说明故障不是"文件解析失败被吞掉"（那种情况会留下 `printStackTrace`），而是**载入代码根本没执行**。

4. **字节码给出机制**（MTR-fabric-1.20.1-3.6.4.2.jar）：
   * `RailwayData.getInstance(world)` → `mtr.mappings.PersistentStateMapper.getInstance(world, supplier, "mtr_train_data")`；
   * `PersistentStateMapper` 的 reader lambda 里是 `load(tag)` → `RailwayData.load(CompoundTag)`；
   * `RailwayData.load(CompoundTag)` 末尾才调用 `railwayDataFileSaveModule.load()`，而 `RailwayDataFileSaveModule.load()` 是**整个 mod 里唯一**会 `Files.list(mtr/<kind>)` 的地方（唯一调用点：`RailwayData.load` 偏移 1186）。
   * Minecraft 1.20.1 的 `PersistentStateManager.get(reader, supplier, name)`：**文件存在** → `reader.apply(tag.getCompound("data"))`；**文件不存在** → 直接 `supplier.get()` 新建空对象，**reader 永不调用**。

5. **源存档没有这个文件**：`Tianyicraft/saves/新的世界/data/` 里只有 MTR4 的 `mtr.dat`（键为 `route_ids_with_disabled_announcements`、`DataVersion`），没有 `mtr_train_data.dat`。转换工具整份复制世界，自然也没有。

6. **旁证（时间戳）**：失败产物里 `data/mtr_train_data.dat` 的 mtime 是 **18:10**（退出存档那一刻），而不是转换时（18:00）。即：载入时它不存在，是 MTR3 退出时自己新建的空 state（73 字节，`data` 里只有 `use_time_and_wind_sync`）——正好对应 `supplier.get()` 分支。同理 `DIM-1/`、`DIM1/` 两个也在这时被创建。

7. **反向证据**：两个正常存档都有 `data/mtr_train_data.dat`，而且 overworld / DIM-1 / DIM1 各一份，全部 73 字节、NBT 内容完全相同。

> 附带发现：正因为 MTR3 在 18:10 已经把那个空 state 写了进去，**旧的失败产物如果现在再打开一次，反而会读到 `mtr/` 文件树**（预测，未实测）。但仍建议用 v2：旧产物的 depots 多了 `last_deployed`、sidings 的浮点是 f64。

---

## 2. 修改点

| 文件 | 改动 |
|---|---|
| **新增** `src/mtrconv/Nbt.java` | 零依赖 NBT（读 `level.dat` 的 `DataVersion`；写 `mtr_train_data.dat`；检测 `data.raw_message_pack`） |
| `Converter.java` | 新增 `writeTrainDataStates(...)`：按维度写 `data/mtr_train_data.dat`；新增 `worldDirFor(...)` 维度→目录映射；新增 `guard(...)` 路径越界保护；`verifyTree` 改为逐键 schema 比对；`railEntry` 的 `rolling_offset`/`vertical_curve_radius` 改 float32；depots 去掉 `last_deployed`；sidings 的 `rail_length`/`acceleration_constant` 改 float32；报告开头新增「根因」段 |
| **新增** `src/mtrconv/Schema.java` | 从 MTR3 原生文件 + 字节码抄下来的 schema：`REQUIRED`（必备键）、`KEYS`（允许键，含 addon 键）、`TYPE`（int/float/bool/str/array/map） |
| **新增** `src/mtrconv/Verify.java` | `--verify <存档>` 只读校验：逐对象比对 schema + 检查每个维度的 `mtr_train_data.dat` |
| `MsgPack.java` | 新增 `packFloat`（0xCA），`Float` 按 f32 写出 |
| `Report.java` | 报告开头新增「零、根因与关键修复」段 |
| `Main.java` | 新增 `--verify` 子命令 |

**为什么不写 `raw_message_pack`**：`RailwayData.load(tag)` 的第一个分支就是"若 tag 含 `raw_message_pack`（byte[]）→ 从内联 msgpack 快照恢复，**跳过整个 mtr/ 文件树**"。所以修复必须只提供 `{DataVersion, data:{use_time_and_wind_sync}}`，一旦多写这个键，就等于换一种方式忽略文件树。

**生成的 state 与 MTR3 自己写的逐字节相同**（gunzip 后 56 字节 NBT 完全一致，五份样本：测试摄影专用的 overworld/DIM-1/DIM1、绫洛市、我们的 v2）：

```
0a0000 03000b4461746156657273696f6e00000d89 0a000464617461 0100167573655f74696d655f616e645f77696e645f73796e63 00 00 00
```

---

## 3. 新产物

```
…\versions\1.20.1-Fabric 0.19.3\saves\新的世界-MTR3-v2\
├── mtr\minecraft\overworld\{stations×4, platforms×4, routes×1, depots×1, sidings×1, rails×14}
├── data\mtr_train_data.dat          ← 新增（本次修复的核心）
├── .mtr4-backup\mtr\…               ← 源 MTR4 数据树备份 + SHA-256 清单
├── MTR4-to-MTR3-report.txt          ← 本次转换报告（含根因段、逐项校验）
└── （世界本体：region/DIM1/DIM-1/level.dat 等，原样复制）
```

`--verify` 结果：**25 个对象文件全部 missing=[] extra=[] typeDiff=[]，state 存在且不含 raw_message_pack → 通过。**

> 本次**没有启动游戏**（遵守「不动既有存档」的约束，且启动游戏无法在只读前提下验证）。
> 以下全部是离线验证：字节码、原生存档逐字节对比、以及自建的 schema 校验器（并先用 MTR3 自己写的两个存档证明该校验器可靠）。
> 进游戏后请先看日志里有没有 `Minecraft Transit Railway data successfully loaded for minecraft:overworld`（见 §6）。

---

## 4. 逐项对比证据

### 4.1 独立脚本（wire 层）对比：产物 vs 两个正常存档

`dev/schema.js` 直接读 msgpack 的**类型操作码**（不是只比键名），参照 = `测试摄影专用` + `绫洛市` 两个 MTR3 自己写的存档：

```
## stations  [MATCH]  files=4  requiredKeys=10
  missing=[]  extra=[]  typeDiff=[]
## platforms  [MATCH]  files=4  requiredKeys=7
  missing=[]  extra=[]  typeDiff=[]
  optionalKeysOnlySomeRefsWrite=["adc_time","stop_without_opening_doors","psd_display_mode"]
## routes  [MATCH]  files=1  requiredKeys=17
  missing=[]  extra=[]  typeDiff=[]
## depots  [MATCH]  files=1  requiredKeys=15
  missing=[]  extra=[]  typeDiff=[]
## sidings  [MATCH]  files=1  requiredKeys=23
  missing=[]  extra=[]  typeDiff=[]
## rails  [MATCH]  files=14  requiredKeys=2
  missing=[]  extra=[]  typeDiff=[]
  nested rail_connections[]: ours 27 keys / 28 conns | MTR3-core keys 20: missing=[]
    | ref-union 33 keys, addon-only in refs=["path_mode","pos_start","facing_start","pos_end","facing_end","bezier_curve"]
## lifts  [N/A]        源存档没有这类对象
## signal-blocks  [N/A] 源存档没有这类对象

SUMMARY kinds differing: 0 / 6 compared
```

说明：
* `optionalKeysOnlySomeRefsWrite` 是**参照存档里只有较新版本才写的键**（`测试摄影专用` 的站台没有这三个），我们写了，属于"允许"。
* msgpack 整数宽度（`int+`/`int8`/`uint32`…）按值取最小宽度，MTR3 自己也一样（同一字段 `k_1` 既有 `0`(int+) 也有 `-1`(int8)），所以对比按"整数/浮点/bool/str/array/map"类别判定。

### 4.2 校验器本身是可信的：跑在 MTR3 原生存档上全通过

```
$ java -jar MTR4toMTR3.jar --verify "…/saves/测试摄影专用"
stations 9 / platforms 22 / routes 9 / depots 11 / sidings 12 / rails 143   全部 ✓ missing=[] extra=[] typeDiff=[]
mtr_train_data data/…、DIM-1/…、DIM1/…  ✓
结论: 通过

$ java -jar MTR4toMTR3.jar --verify "…/saves/绫洛市"
stations 65 / platforms 189 / routes 21 / depots 21 / sidings 29 / rails 1573 / lifts 102 / signal-blocks 34  全部 ✓
结论: 通过
```

（同一校验器对旧的失败产物会报 `depots: extra key 'last_deployed'` —— 这是旧产物的真实差异，说明它不是"永远通过"的橡皮图章。）

### 4.3 `mtr_train_data.dat` 逐字节一致

| 来源 | 原始大小 | gunzip 后 | 与 v2 的 NBT |
|---|---|---|---|
| 测试摄影专用 `data/` | 73 | 56 | **完全一致** |
| 测试摄影专用 `DIM-1/data/` | 73 | 56 | **完全一致** |
| 测试摄影专用 `DIM1/data/` | 73 | 56 | **完全一致** |
| 绫洛市 `data/` | 73 | 56 | **完全一致** |
| **新的世界-MTR3-v2 `data/`** | 73 | 56 | — |

---

## 5. 仍存的差异 / 已知限制（诚实清单）

1. **`rail_connections` 里的 6 个键不写**：`path_mode`、`pos_start`、`facing_start`、`pos_end`、`facing_end`、`bezier_curve`。
   这些不是 MTR3 核心的字段——`mtr.data.Rail(Map)` 的字符串常量只有 19 个（`h_1 h_2 k_1 k_2 r_1 r_2 t_start_1 t_end_1 t_start_2 t_end_2 y_start y_end reverse_t_1 is_straight_1 reverse_t_2 is_straight_2 rail_type transport_mode rail_data`），加上 `RailEntry` 的 `node_pos` 共 20 个，我们**全部写了**。
   那 6 个键由 ANTE/NTE 一类"模型轨"扩展写入（在参照存档里与 `model_key` 非空强相关，脚本统计：带 `path_mode` 的连接几乎都带 `model_key`）。我们的轨道 `model_key=""`，属普通轨，缺这几个键 MTR3 侧取默认值。
2. **lifts / signal-blocks** 依旧是"原样复制、未做字段映射"。本次源存档这两类为空，所以不影响本产物；有数据时仍建议进游戏复查。
3. **轨道几何是反算的**（由端点+角度反推 h/k/r/t），可能有厘米级偏差；影响"线形好不好看"，不影响"读不读得到"。
4. 站台 `dwellTime ÷ 500`、车厂频率补 24 档、侧线 `acceleration × 2500` 等既有映射本次未改动。

---

## 6. 如果进游戏仍然看不到（下一步实验）

先看日志，一行定生死：

* 出现 `Minecraft Transit Railway data successfully loaded for minecraft:overworld` → load 跑了，问题在对象层（把日志和 `--verify` 输出给我；此时可以拿 MTR3 自己重写后的文件做 diff）。
* 仍没有这一行 → `saves/<世界>/data/mtr_train_data.dat` 没被读到：检查文件是否被清理脚本/同步工具删掉、`level.dat` 是否被回滚、或该世界是否被别的 MTR 版本接管。

要做"金标准模板"时的最小实验（不要动既有存档，复制一份到 `%LOCALAPPDATA%\Temp\mtr3-golden\` 再开）：

1. 用 v2 的副本进游戏，建 1 个车站 + 1 个站台 + 1 段轨道 + 1 个车厂，退出；
2. `java -jar MTR4toMTR3.jar --verify <那份副本>` —— MTR3 重写后的对象与本 schema 的差异会直接列出来；
3. 把差异键反馈进 `Schema.java`，转换器即可对齐到该版本。
