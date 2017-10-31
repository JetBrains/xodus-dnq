package kotlinx.dnq

import kotlin.reflect.KProperty1


interface XdModelPlugin {

    val typeExtensions: List<KProperty1<out XdEntity, *>>

}


interface XdModelPlugins {

    val plugins: List<XdModelPlugin>

}

open class SimpleModelPlugin(val typeExtensions: List<KProperty1<out XdEntity, *>>) : XdModelPlugins {
    override val plugins: List<XdModelPlugin> = listOf(
            object : XdModelPlugin {
                override val typeExtensions: List<KProperty1<out XdEntity, *>> = this@SimpleModelPlugin.typeExtensions.toList()
            }
    )

}