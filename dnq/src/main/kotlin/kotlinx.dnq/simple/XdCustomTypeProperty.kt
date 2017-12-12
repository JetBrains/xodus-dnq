package kotlinx.dnq.simple

import kotlinx.dnq.simple.custom.type.XdComparableBinding

interface XdCustomTypeProperty<V : Comparable<V>> {
    val binding: XdComparableBinding<V>?
}