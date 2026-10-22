# CZN 繁转简（Android）

把《卡厄思梦境》(Chaos Zero Nightmare) 安卓版游戏资源里的官方繁中文本库转成简体，并同步清单和
身份记录，让游戏在 patching 阶段不重新下载资源。需要 Shizuku，不需要 root。

移植自 PC 端工具 ChaosZero-Toolkit-Extra 的 ssra_zhcn.py，同样以 GPL-3.0 发布。

## 用法

1. 在 Shizuku 里用无线调试启动服务，并给本应用授权。
2. 关闭游戏，打开本应用点「检测」，确认三个目标文件都在、写探针通过。
3. 点「繁转简（构建补丁）」，看过统计再点「应用补丁」。原文件备份到 gameres 同级的
   `czn_backup_original/<build>-<context>/` 目录。
4. 启动游戏，文本应为简体。要回去就点「还原官方繁中」。
5. 想知道汉化还在不在生效，点「手动检测繁转简状态」，它会真读一遍文本库并数出残留条数。

## 构建

```
./gradlew :app:assembleDebug      # 调试包
./gradlew :app:assembleRelease    # 需要本地 keystore.properties（已 gitignore）
./gradlew :app:testDebugUnitTest  # 需要本地测试资源
```

单元测试用的手机资源（`testdata/`）和 PC 端参照产物都不随仓库分发，缺了测试会直接报错而不是跳过。

## 支持范围与已知限制

- 已实测 build 88 与 build 126；繁中分卷名、卷内偏移等位置信息由清单推导，不写死。
- 官方更新资源版本时，汉化会被换回繁体（更新器按卷内容哈希重取）。状态显示「汉化失效」时
  重新点一次即可，不用重装。日常启动不会冲掉。
- 大厅横幅、心绪崩溃状态名、章节标题、logo 和年龄分级标等 39 处文字是直接画进图片的，
  本工具不处理图片。
- opencc4j 词典有 117 条文本的转换结果与 PC 端不同，都卡在「乾」这个字上（PC 转「干」）。
  只影响这几句的显示，不影响补丁结构。
