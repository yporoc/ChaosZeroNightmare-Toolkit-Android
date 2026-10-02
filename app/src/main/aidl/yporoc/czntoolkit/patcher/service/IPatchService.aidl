package yporoc.czntoolkit.patcher.service;

import yporoc.czntoolkit.patcher.service.IProgressCallback;

/**
 * 运行在 Shizuku（shell UID）进程中的补丁服务。
 * 所有文件 IO 都在该进程内完成，跨进程只传小体积结果。
 */
interface IPatchService {

    /** 检测 gameres 目录与 3 个目标文件状态，返回 DetectKeys 约定的 Bundle。 */
    Bundle detect(String gameresPath);

    /** 构建补丁（结果保留在服务进程内存，等待 apply）。onLog 可为 null。 */
    Bundle build(String gameresPath, IProgressCallback onLog);

    /** 应用补丁：备份原文件 → 写入 manifest/分卷/etag → 写 prev。 */
    Bundle apply(String gameresPath);

    /** 从备份还原官方繁中。 */
    Bundle restore(String gameresPath);

    /** 列出目录下的子目录（用于路径手选浏览器）。 */
    String[] listDirs(String parentPath);

    /** 丢弃服务内存中暂存的构建结果。 */
    void discardPending();

    /** 结束服务进程。 */
    void exit();
}
