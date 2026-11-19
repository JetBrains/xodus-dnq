package jetbrains.exodus.query.metadata

import java.util.Collections

// These descriptions carry no registration authority or executable behavior.
enum class DeclarationRequirement { OPTIONAL, REQUIRED, UNIQUE }
enum class ConstraintKind { LENGTH, NUMERIC_RANGE, CUSTOM }
enum class ConstraintImplementation { BUILT_IN, SUBCLASS, CUSTOM }
enum class RegistrationCategory { NATURAL, NON_NATURAL }
enum class DeclarationDeletePolicy { FAIL, CLEAR, CASCADE, FAIL_PER_TYPE, FAIL_PER_ENTITY }

data class DeclarationPropertyId(val owner: String, val kotlinName: String)
data class CustomTypeDeclaration(val typeId: String, val bindingImplementationId: String?)
data class IdentityDeclaration(val databaseName: String, val primitiveTypeName: String)
data class ConstraintDeclaration(
    val kind: ConstraintKind,
    val implementationId: String,
    val implementation: ConstraintImplementation,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minValue: Long? = null,
    val maxValue: Long? = null
)
data class ExtensionDeclaration(val pluginId: String, val property: DeclarationPropertyId)
data class IndexFieldDeclaration(val databaseName: String, val isProperty: Boolean)
data class EndpointDeclaration(
    val property: DeclarationPropertyId,
    val databaseName: String,
    val databaseNameOverride: String?,
    val oppositeType: String,
    val oppositeProperty: DeclarationPropertyId?,
    val oppositeDatabaseNameOverride: String?,
    val cardinality: AssociationEndCardinality,
    val endType: AssociationEndType,
    val onDelete: DeclarationDeletePolicy,
    val onTargetDelete: DeclarationDeletePolicy,
    val registrationCategory: RegistrationCategory,
    val installedExtension: ExtensionDeclaration? = null
)

private fun <T> Collection<T>.detachedList(): List<T> = Collections.unmodifiableList(ArrayList(this))
private fun <T> Collection<T>.detachedSet(): Set<T> = Collections.unmodifiableSet(LinkedHashSet(this))

class ScalarDeclaration(
    val property: DeclarationPropertyId,
    val databaseName: String,
    val kind: PropertyType,
    val primitiveTypeName: String?,
    parameters: List<String>?,
    val autoIndexed: Boolean,
    val requirement: DeclarationRequirement,
    constraints: List<ConstraintDeclaration> = emptyList(),
    val customType: CustomTypeDeclaration? = null
) {
    val parameters: List<String>? = parameters?.detachedList()
    val constraints: List<ConstraintDeclaration> = constraints.detachedList()

    fun copy(
        property: DeclarationPropertyId = this.property, databaseName: String = this.databaseName,
        kind: PropertyType = this.kind, primitiveTypeName: String? = this.primitiveTypeName,
        parameters: List<String>? = this.parameters, autoIndexed: Boolean = this.autoIndexed,
        requirement: DeclarationRequirement = this.requirement,
        constraints: List<ConstraintDeclaration> = this.constraints, customType: CustomTypeDeclaration? = this.customType
    ) = ScalarDeclaration(property, databaseName, kind, primitiveTypeName, parameters, autoIndexed,
        requirement, constraints, customType)

    override fun equals(other: Any?): Boolean = other is ScalarDeclaration &&
        property == other.property && databaseName == other.databaseName && kind == other.kind &&
        primitiveTypeName == other.primitiveTypeName && parameters == other.parameters &&
        autoIndexed == other.autoIndexed && requirement == other.requirement &&
        constraints == other.constraints && customType == other.customType

    override fun hashCode(): Int = listOf(property, databaseName, kind, primitiveTypeName, parameters,
        autoIndexed, requirement, constraints, customType).hashCode()
}

class IndexDeclaration(val owner: String, fields: List<IndexFieldDeclaration>) {
    val fields: List<IndexFieldDeclaration> = fields.detachedList()
    fun copy(owner: String = this.owner, fields: List<IndexFieldDeclaration> = this.fields) =
        IndexDeclaration(owner, fields)
    override fun equals(other: Any?): Boolean = other is IndexDeclaration && owner == other.owner && fields == other.fields
    override fun hashCode(): Int = listOf(owner, fields).hashCode()
}

class TypeDeclaration(
    val name: String,
    val isAbstract: Boolean,
    ancestors: List<String>,
    val registrationCategory: RegistrationCategory,
    scalars: List<ScalarDeclaration> = emptyList(),
    conditionalRequiredCandidates: Set<String> = emptySet(),
    indexes: List<IndexDeclaration> = emptyList(),
    endpoints: List<EndpointDeclaration> = emptyList(),
    installedExtensions: List<ExtensionDeclaration> = emptyList(),
    val identity: IdentityDeclaration? = null
) {
    val ancestors: List<String> = ancestors.detachedList()
    val scalars: List<ScalarDeclaration> = scalars.detachedList()
    val conditionalRequiredCandidates: Set<String> = conditionalRequiredCandidates.detachedSet()
    val indexes: List<IndexDeclaration> = indexes.detachedList()
    val endpoints: List<EndpointDeclaration> = endpoints.detachedList()
    val installedExtensions: List<ExtensionDeclaration> = installedExtensions.detachedList()

    fun copy(
        name: String = this.name, isAbstract: Boolean = this.isAbstract, ancestors: List<String> = this.ancestors,
        registrationCategory: RegistrationCategory = this.registrationCategory,
        scalars: List<ScalarDeclaration> = this.scalars,
        conditionalRequiredCandidates: Set<String> = this.conditionalRequiredCandidates,
        indexes: List<IndexDeclaration> = this.indexes, endpoints: List<EndpointDeclaration> = this.endpoints,
        installedExtensions: List<ExtensionDeclaration> = this.installedExtensions,
        identity: IdentityDeclaration? = this.identity
    ) = TypeDeclaration(name, isAbstract, ancestors, registrationCategory, scalars, conditionalRequiredCandidates,
        indexes, endpoints, installedExtensions, identity)

    override fun equals(other: Any?): Boolean = other is TypeDeclaration &&
        name == other.name && isAbstract == other.isAbstract && ancestors == other.ancestors &&
        registrationCategory == other.registrationCategory && scalars == other.scalars &&
        conditionalRequiredCandidates == other.conditionalRequiredCandidates && indexes == other.indexes &&
        endpoints == other.endpoints && installedExtensions == other.installedExtensions && identity == other.identity

    override fun hashCode(): Int = listOf(name, isAbstract, ancestors, registrationCategory, scalars,
        conditionalRequiredCandidates, indexes, endpoints, installedExtensions, identity).hashCode()
}

class ModelDeclarations(types: Map<String, TypeDeclaration>) {
    val types: Map<String, TypeDeclaration> = Collections.unmodifiableMap(LinkedHashMap(types))
    fun copy(types: Map<String, TypeDeclaration> = this.types) = ModelDeclarations(types)
    override fun equals(other: Any?): Boolean = other is ModelDeclarations && types == other.types
    override fun hashCode(): Int = types.hashCode()
}

// Each call owns its mutable metadata and parameter list. Null and empty remain distinct.
internal fun ScalarDeclaration.toSimplePropertyMetaData(): SimplePropertyMetaDataImpl =
    SimplePropertyMetaDataImpl(databaseName, primitiveTypeName, parameters?.let { ArrayList(it) }).also {
        it.type = kind
        it.isAutoIndexed = autoIndexed
    }
