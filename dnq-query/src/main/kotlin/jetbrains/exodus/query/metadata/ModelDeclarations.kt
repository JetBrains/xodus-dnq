package jetbrains.exodus.query.metadata

import java.util.Collections

/** Declared property requirement. These values do not enforce presence or uniqueness. */
enum class DeclarationRequirement { OPTIONAL, REQUIRED, UNIQUE }
/** Describes a length constraint, numeric range or custom constraint without running it. */
enum class ConstraintKind { LENGTH, NUMERIC_RANGE, CUSTOM }
/** Distinguishes a built-in constraint, a subclass of one and a custom implementation. */
enum class ConstraintImplementation { BUILT_IN, SUBCLASS, CUSTOM }
/** NATURAL denotes an `XdNaturalEntityType` origin. These categories grant no registration authority. */
enum class RegistrationCategory { NATURAL, NON_NATURAL }
/**
 * Describes failure, link clearing or cascading deletion, without performing any action.
 * FAIL_PER_TYPE and FAIL_PER_ENTITY distinguish custom failure message scopes, not callbacks.
 */
enum class DeclarationDeletePolicy { FAIL, CLEAR, CASCADE, FAIL_PER_TYPE, FAIL_PER_ENTITY }

/**
 * Identifies the original Kotlin declaration by [owner] and [kotlinName], not its database name.
 * An inherited property can keep its original owner when described on another type.
 * Like the other data classes here, equality and copy use all fields.
 */
data class DeclarationPropertyId(val owner: String, val kotlinName: String)
/** Descriptive custom type and optional binding implementation identifiers. This value loads or binds nothing. */
data class CustomTypeDeclaration(val typeId: String, val bindingImplementationId: String?)
/**
 * Describes identity storage, such as `localEntityId` with primitive type `LONG`.
 * It creates no property, sequence or identity value.
 */
data class IdentityDeclaration(val databaseName: String, val primitiveTypeName: String)
/**
 * Retains raw nullable bounds and a descriptive [implementationId], not executable validation.
 * Length bounds use Int and numeric bounds use Long. Null means no supplied bound.
 * Construction preserves signs and extremes without checking or normalizing the bounds.
 */
data class ConstraintDeclaration(
    val kind: ConstraintKind,
    val implementationId: String,
    val implementation: ConstraintImplementation,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minValue: Long? = null,
    val maxValue: Long? = null
)
/** Records the source plugin and Kotlin property of an extension, without installing it. */
data class ExtensionDeclaration(val pluginId: String, val property: DeclarationPropertyId)
/** One database-named index field. [isProperty] is true for a scalar property and false for a link. */
data class IndexFieldDeclaration(val databaseName: String, val isProperty: Boolean)
/**
 * Describes one link end. [property] identifies its Kotlin declaration and [databaseName] its database name.
 * [oppositeType] and [oppositeProperty] describe the target side, which can be the same type.
 * The nullable name overrides retain explicit naming facts for each side.
 *
 * [cardinality] describes target counts. [endType] distinguishes directed, undirected, parent and child ends.
 * [onDelete] describes the effect on targets on source deletion, and [onTargetDelete] the reverse.
 * [registrationCategory] and [installedExtension] record provenance. This value registers no association.
 */
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

/** Copies elements in order and blocks mutation through the returned list. */
private fun <T> Collection<T>.detachedList(): List<T> = Collections.unmodifiableList(ArrayList(this))
/** Copies elements in iteration order and blocks mutation through the returned set. */
private fun <T> Collection<T>.detachedSet(): Set<T> = Collections.unmodifiableSet(LinkedHashSet(this))

/**
 * Describes a scalar separately from mutable [SimplePropertyMetaDataImpl].
 * [property] retains Kotlin identity. [databaseName], [kind] and [primitiveTypeName] describe storage metadata.
 * [parameters] holds ordered type parameter names, such as `String` for `Set`. Null and empty remain distinct.
 * [autoIndexed] records the automatic indexing choice, not a guarantee that an index exists.
 *
 * [requirement], [constraints] and [customType] describe declarations without enforcing or binding them.
 * Construction and [copy] defensively copy collection inputs and expose unmodifiable lists.
 * Equality and hashing use every declared field, including list order.
 */
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

/**
 * Describes an index on [owner], the owning entity type name. It creates no index.
 * [fields] retains composite index field order. Construction and [copy] defensively copy the list.
 * The exposed list blocks mutation. Equality and hashing include the owner and ordered fields.
 */
class IndexDeclaration(val owner: String, fields: List<IndexFieldDeclaration>) {
    val fields: List<IndexFieldDeclaration> = fields.detachedList()
    fun copy(owner: String = this.owner, fields: List<IndexFieldDeclaration> = this.fields) =
        IndexDeclaration(owner, fields)
    override fun equals(other: Any?): Boolean = other is IndexDeclaration && owner == other.owner && fields == other.fields
    override fun hashCode(): Int = listOf(owner, fields).hashCode()
}

/**
 * Describes a named entity type, its abstract flag and its supplied ancestor order.
 * [conditionalRequiredCandidates] contains database names, not evaluated conditions or unconditional requirements.
 * Scalars, indexes, endpoints, extensions, registration provenance and optional identity remain descriptive facts.
 * Construction and [copy] defensively copy each collection and expose unmodifiable collections.
 * Nested declaration values are immutable. Equality and hashing use all fields, with list order preserved.
 */
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

/**
 * Groups type descriptions by entity type name without registering or applying them.
 * Construction and [copy] defensively copy the map and expose an unmodifiable map of immutable values.
 * Equality and hashing use map contents. A copy with unchanged fields has equal value.
 */
class ModelDeclarations(types: Map<String, TypeDeclaration>) {
    val types: Map<String, TypeDeclaration> = Collections.unmodifiableMap(LinkedHashMap(types))
    fun copy(types: Map<String, TypeDeclaration> = this.types) = ModelDeclarations(types)
    override fun equals(other: Any?): Boolean = other is ModelDeclarations && types == other.types
    override fun hashCode(): Int = types.hashCode()
}

/**
 * Creates fresh mutable scalar metadata with the stored name, kind, primitive type and automatic indexing flag.
 * Each call copies non-null parameters to a fresh mutable list. Null and empty remain distinct.
 * It does not convert requirements, constraints or custom bindings, or register anything.
 * For an existing declaration `scalar`, output mutations do not affect it or another output:
 * ```
 * val first = scalar.toSimplePropertyMetaData()
 * val second = scalar.toSimplePropertyMetaData()
 * first.name = "changed"
 * first.typeParameterNames?.add("extra")
 * // scalar and second retain their original names and parameters, including null parameters.
 * ```
 */
internal fun ScalarDeclaration.toSimplePropertyMetaData(): SimplePropertyMetaDataImpl =
    SimplePropertyMetaDataImpl(databaseName, primitiveTypeName, parameters?.let { ArrayList(it) }).also {
        it.type = kind
        it.isAutoIndexed = autoIndexed
    }
