---
layout: page
title: Build Meta-Model 
---
All persistent classes should be registered in `XdModel`, there are several ways to do it.
```kotlin
// 1. Register persistent class explicitly
XdModel.registerNode(User)

// 2. Scan Java-Classpath
XdModel.scanJavaClasspath()

// 3. Scan specific URLs
if (classLoader is URLClassLoader) {
    XdModel.scanURLs("URLClassLoader.urls", classLoader.urLs)
}
```

## Declaration capture

`captureTypeDeclaration` is an internal helper. Registration does not call it.
Call it only after registration completes and keep the hierarchy stable
while it runs. Supply current plain values from trusted input, not from
model getters that capture cannot safely call.

For example, `IndexChild` overrides `compositeIndices` to index the scalar
property `inherited` from `IndexParent`. Let `child` be the registered node
for `IndexChild`. Capture does not call the override. Supply an `INDEXES`
fact on the child with the known, current Kotlin property keys in index
order:

```kotlin
val indexes = INDEXES.at(child, ExplicitIndexFacts(arrayOf("inherited")))
```

Omit `INDEXES` when capture already knows the default, such as the empty
list from the base getter.

In this example, capture cannot safely call the database-name getter on
the parent scalar's storage delegate. Supply the current name from trusted
input as a `DATABASE_NAME` fact. Use the exact current slot in the parent's
map. Neither the child node nor a copy of the slot matches.

```kotlin
val parent = checkNotNull(child.parentNode)
val slot = parent.simpleProperties.getValue("inherited")
val databaseName = DATABASE_NAME.at(parent, "inherited_db", slot)
child.captureTypeDeclaration(indexes, databaseName)
```

`inherited` is the Kotlin key used to resolve the index field.
`inherited_db` is its database name, not an index key.

If capture can safely call the getter and it returns `null`, capture uses
the Kotlin property name. If capture reports a missing Kotlin name, supply
a current `KOTLIN_NAME` fact from trusted input at the parent slot instead
of `DATABASE_NAME`. Still pass `indexes`.

If capture already knows the database name, omit `DATABASE_NAME`. With only
the child index fact needed, use `child.captureTypeDeclaration(indexes)`.
