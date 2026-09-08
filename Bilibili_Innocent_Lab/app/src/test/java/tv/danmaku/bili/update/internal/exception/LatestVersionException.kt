package tv.danmaku.bili.update.internal.exception

/**
 * 宿主 `LatestVersionException(String)` 的 JVM 测试替身。
 *
 * 只需要“存在一个接受单个 String 的构造器且是 Throwable 子类”，让
 * `BlockUpdateFeatureInstaller` 的安装期结构校验能走到注册那一步。
 */
class LatestVersionException(message: String) : RuntimeException(message)
