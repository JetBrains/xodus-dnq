package kotlinx.dnq


class XdWrapperNotFoundException(val entityType: String) : UnsupportedOperationException("XdEntityType for type $entityType is " +
        "not registered or defined. Consider to invoke XdModel.scanClasspath()")