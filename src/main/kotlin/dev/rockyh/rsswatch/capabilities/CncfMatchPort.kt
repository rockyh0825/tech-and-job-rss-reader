package dev.rockyh.rsswatch.capabilities

import dev.rockyh.rsswatch.shared.contract.CncfMention

/**
 * CNCF プロジェクト言及照合の Port(feature 間境界)。
 *
 * - 依存する側: notify/application(BuildCncfDigestUseCase がダイジェストの成熟度バッジに使う)・
 *   report/application(BuildCncfReportUseCase が Web レポートの成熟度表示に使う)
 * - 実装する側: keywords/application(CncfMatchPortImpl)
 */
interface CncfMatchPort {

    /** [text] 中で言及された CNCF プロジェクトを成熟度の低い順で返す(言及なしは空リスト)。 */
    fun match(text: String): List<CncfMention>
}
