# CZN 繁转简 Android

通过 Shizuku 在手机上把 Chaos Zero Nightmare 的官方繁中文本库转换为简体，
并同步资源更新器的身份记录，避免游戏启动时重新下载资源。无需 root。

## 原理

游戏资源由三个文件描述：`manifest.ssra`（资源索引）、`chunks/*.ssrc`（zstd 分卷）、
`manifest.ssra.etag`（更新器身份记录）。补丁流程：

1. 解析 manifest，跨卷提取 `text/zht/text.db`（zstd 解压，256 字节 XOR 相位解密）
2. 解析 PLPcK 哈希桶结构，OpenCC t2s 逐条转换
3. 重建文本库，压缩后用 zstd skippable frame 垫满原帧尺寸，保证分卷与官方逐字节等长
4. 更新 manifest 中该分卷的 XXH64，同步 etag，原文件自动备份到 `czn_backup_original/`

## 构建

需要 JDK 17+ 与 Android SDK（`local.properties` 指定 `sdk.dir`）。

```
./gradlew :app:assembleDebug
```

原生库 libcznfast.so 已预编译在 `app/src/main/jniLibs/`，重编：

```
./native/build_native.sh <NDK路径>
```

单元测试需要自备测试数据，不入库。

## 使用

安装 [Shizuku](https://github.com/RikkaApps/Shizuku)，通过无线调试启动并授权本应用，
之后：检测 → 繁转简（构建补丁）→ 应用补丁。「还原官方繁中」可随时回退。

## 许可

代码 GPLv3，见 LICENSE；图标资产 CC BY-NC-SA 4.0，见 LICENSE-ASSETS；
第三方组件见 NOTICE。
