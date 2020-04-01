package kotlinx.dnq.management

interface DnqStatisticsMBean {

    val delegatesCacheSize: Int
    val delegatesCacheHitRate: String

}