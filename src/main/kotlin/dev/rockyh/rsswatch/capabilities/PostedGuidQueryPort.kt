package dev.rockyh.rsswatch.capabilities

/**
 * Discord ダイジェストへ配信済みの guid を照会する Port(feature 間境界)。
 *
 * - 依存する側: report/application(BuildReportUseCase / BuildCncfReportUseCase が配信済み表示に使う)
 * - 実装する側: notify/application(PostedGuidQueryPortImpl)
 */
interface PostedGuidQueryPort {

    /** これまでに配信済みとして記録された全 guid の集合を返す。 */
    fun postedGuids(): Set<String>
}
