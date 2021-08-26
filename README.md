# ![Xodus-DNQ](docs/images/XodusDNQ.png)
 
[![official JetBrains project](https://jb.gg/badges/official.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.jetbrains.xodus/dnq/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.jetbrains.xodus/dnq)
[![Build Status](https://travis-ci.org/JetBrains/xodus-dnq.svg?branch=master)](https://travis-ci.org/JetBrains/xodus-dnq)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)
![Pure Java + Kotlin](https://img.shields.io/badge/100%25-java%2bkotlin-orange.svg)
[![Stack Overflow](https://img.shields.io/badge/stack%20overflow-xodus--dnq-brightgreen.svg)](https://stackoverflow.com/questions/tagged/xodus-dnq)

Xodus-DNQ is a Kotlin library that contains the data definition language and queries for 
[Xodus](https://github.com/JetBrains/xodus), a transactional schema-less embedded database. 
Xodus-DNQ provides the same support for Xodus that ORM frameworks provide for SQL databases. 
With Xodus-DNQ, you can define your persistent meta-model using Kotlin classes.

JetBrains team tools [YouTrack](https://jetbrains.com/youtrack) and [Hub](https://jetbrains.com/hub) use Xodus-DNQ for 
persistent layer definition.

More documentation https://jetbrains.github.io/xodus-dnq.

## Quick Start Guide

### Install to your project
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.jetbrains.xodus/dnq/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.jetbrains.xodus/dnq)

List of released versions is available at https://github.com/JetBrains/xodus-dnq/releases.

#### Gradle
```groovy
repositories {
    mavenCentral()
}
compile 'org.jetbrains.xodus:dnq:${version}'
```
#### Maven
```xml
<dependency>
    <groupId>org.jetbrains.xodus</groupId>
    <artifactId>dnq</artifactId>
    <version>$version</version>
</dependency>
```

### Use Xodus-DNQ
[See code in repository](https://github.com/JetBrains/xodus-dnq/blob/master/dnq/src/test/kotlin/kotlinx/dnq/sample/SampleShortApp.kt).
```kotlin
// Define persistent class. It should extend XdEntity
class XdPost(entity: Entity) : XdEntity(entity) {
    //  and have component object of type XdEntityType
    companion object : XdNaturalEntityType<XdPost>()

    // Define persistent property of type org.joda.time.DateTime?
    var publishedAt by xdDateTimeProp()

    // Define required persistent property of type String
    var text by xdRequiredStringProp()
}

class XdBlog(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<XdBlog>()

    // Define multi-value link to XdPost
    val posts by xdLink0_N(XdPost)
}

fun main(args: Array<String>) {
    // Register persistent classes
    XdModel.registerNodes(XdPost, XdBlog)

    // Initialize Xodus persistent storage
    val xodusStore = StaticStoreContainer.init(
            dbFolder = File(System.getProperty("user.home"), ".xodus-dnq-blog-db"),
            environmentName = "db"
    )

    // Initialize Xodus-DNQ metadata
    initMetaData(XdModel.hierarchy, xodusStore)

    // Do in transaction
    val blog = xodusStore.transactional {
        // Find an existing blog in database
        XdBlog.all().firstOrNull()
                // or create a new one if there are no blogs yet
                ?: XdBlog.new()
    }

    xodusStore.transactional {
        // Create new post
        val post = XdPost.new {
            this.publishedAt = DateTime.now()
            this.text = args.firstOrNull() ?: "Empty post"
        }

        // Add new post to blog
        blog.posts.add(post)
    }

    // Do in read-only transaction
    xodusStore.transactional(readonly = true) {
        // Print all blog posts
        for (post in blog.posts) {
            println("${post.publishedAt}: ${post.text}")
        }
    }
}
```

## Find out more
- [Read documentation](https://jetbrains.github.io/xodus-dnq)
- [Report an issue](https://github.com/JetBrains/xodus-dnq/issues/new)
- [Ask question at Stack Overflow](https://stackoverflow.com/questions/tagged/xodus-dnq)
- [Learn more about Xodus](https://github.com/JetBrains/xodus)
