# CZN 繁转简（Android）

Chaos Zero Nightmare 安卓端汉化补丁工具：把游戏 `gameres/chunks/lang_zht_b03_0.ssrc`
里的官方繁中文本库转换为简体，并同步 `manifest.ssra` / `manifest.ssra.etag`，
使游戏 patching 阶段不重新下载资源。移植自 PC 端 GPL-3.0 项目
ChaosZero-Toolkit-Extra 的 `ssra_zhcn.py`，本项目同以 **GPL-3.0** 发布。

## 功能

- 自动定位 `Android/data/<游戏包名>/files/gameres`，显示 3 个目标文件状态
  （manifest.ssra / 繁中分卷 / manifest.ssra.etag），支持手输路径 + Shizuku 目录浏览手选。
  繁中分卷名一律由清单推导，不写死。
- **繁转简状态**：已汉化 / 未汉化 / 汉化失效 / 现场异常 / 资源未就绪 / 未判定。
  判据是清单身份 **和** 分卷身份双双对得上；「手动检测繁转简状态」才会真解压文本库，
  按本应用词典复算残留繁体条数（十秒级，只在点按钮时跑）。
- 🈯 繁转简：OpenCC t2s 逐条转换 → 重建 PLPcK 文本库（**含官方尾部页脚，原样搬运**）→
  zstd 压缩并用 skippable frame 垫满原帧尺寸（分卷与官方逐字节同尺寸）→ 更新 manifest 卷
  XXH64 → 同步 etag（分隔符自适应：实测手机版是 LF，PC 版是 CRLF）→ 自动备份。
- **备份与补丁绑定版本身份**：备份按 `b<build>-<context>` 分目录，每次从「已核实为官方态」的
  现场重取，并写 `identity.txt`；apply 前核对现场清单/分卷哈希，对不上就拒绝；restore 只认
  身份能对上现场的备份，还原后反向断言哈希回到官方值。不带身份的旧备份一律拒绝还原。
- 全程 Shizuku（shell UID）执行文件 IO，无需 root。不写也不再删游戏自维护的 `manifest.ssra.prev`。

## 支持的游戏资源版本

| 资源 build | 实测 | 说明 |
|---|---|---|
| 126（`pack-126-3554674e9022`） | ✅ 真机官方态 | `text.db` 明文尾部带 38 字节 `PLPcK` 页脚 |
| 88（`pack-88-e8a4e49e3a1e`） | ✅ 分身安装 | 页脚为 0 字节，同一条代码路径覆盖 |

汉化会在**官方切换资源版本的那一轮**被冲掉（更新器按逐卷内容哈希重取），日常启动不会。
状态显示为「汉化失效」时重新点一次繁转简即可恢复，不需要重装或改代码。

## 已知缺口（实话）

- 大厅活动横幅、战斗「心绪崩溃」状态名、灾厄章节标题、游戏 logo 与 12+/15+ 分级标等
  **39 张把文字烘成图片的繁体资产**（`banner/lobby/zht` 14、`local/**/zht` 16、
  `disaster/zht` 6、`event/zht` 3）不会被转换——它们是像素不是文本。
- 本应用用的 opencc4j 词典在 **117 条文本上与 PC 端结果不同**，全部集中在「乾」：
  PC 转成「干」（干燥、干净、干涸…），opencc4j 保留「乾」，其中 3 条它一个字都没转。
  原因是它的单字表刻意不含「乾」，词组路径需要它自己声明为 optional 的分词器依赖。
  这不影响补丁结构（长度不变、可回读、可还原），只影响这几句的显示效果。

## 环境

| 组件 | 版本 |
|---|---|
| AGP / Kotlin / Compose BOM | 9.4.1（内置 Kotlin）/ 2.4.20 / 2026.09.00 |
| Shizuku api/provider | 13.1.5 |
| zstd-jni | 1.5.7-20（abiFilters: arm64-v8a, x86_64）；真机走 native cznfast（zstd 1.5.6） |
| opencc4j | 1.14.0 |
| minSdk / targetSdk / compileSdk | 24 / 36 / 37.1 |

## 构建与测试

```bash
./gradlew :app:assembleDebug        # 输出 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # 需本地 keystore.properties（gitignore）
./gradlew :app:testDebugUnitTest    # 黄金测试 + 停机毒台（需要本地测试数据，见下）
```

测试数据均已 gitignore，**缺了会直接失败而不是静默跳过**（旧版用固定相对层级 + `assumeTrue`，
克隆放深一层就"全绿但没测"）：

- `testdata/phone_gameres/` — 手机官方繁中三件套（build 88，adb pull）；
- `testdata/phone_gameres_b126/` — 手机官方繁中三件套（build 126）；
- `testdata/oracle_b126/` — PC 端工具在 build 126 上的产物读数，用作**外部判据**；
- PC 端工具 `zhcn_patch/` 备份目录（用于验证"已简体化的树必须被拒绝构建"），
  可用环境变量 `CZN_ZHCN_ROOT` 指定，未设时向上逐级查找。

测试覆盖：126/88 双样本等长与页脚搬运、产物读回零残留、构建幂等、身份闸门（跨版本、
混合态、残缺身份、旧版无身份备份）、分卷挪卷/换封装/加后随条目等前提停机、etag 的 LF 与
CRLF 两种分隔、六态状态读数。

## 使用步骤（真机）

1. 安装 APK，打开 Shizuku 应用，用「无线调试」启动服务并给本应用授权。
2. 打开本应用 → 点「检测」确认 3 个文件全部 ✅，并看「繁转简状态」（应为「未汉化」）。
3. 关闭游戏 → 点「🈯 繁转简（构建补丁）」→ 确认统计 → 「应用补丁」。
4. 启动游戏验证：patching 不重下载、文本为简体；横幅类图片仍是繁体（见"已知缺口"）。
5. 想确认有没有生效：回应用点「手动检测繁转简状态」，看残留条数是否为 0。
6. 想还原：回应用点「还原官方繁中」——它会核对身份，只有现场确实是本补丁产物时才写回。
