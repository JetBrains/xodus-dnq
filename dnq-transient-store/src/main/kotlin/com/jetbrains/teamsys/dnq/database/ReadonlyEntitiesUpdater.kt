/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.database.TransientEntitiesUpdater
import jetbrains.exodus.database.TransientEntity
import java.io.File
import java.io.InputStream

class ReadonlyTransientEntitiesUpdater : TransientEntitiesUpdater {

    fun invalidState() {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun addChild(
        parent: TransientEntity,
        parentToChildLinkName: String,
        childToParentLinkName: String,
        child: TransientEntity
    ) {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun setBlob(transientEntity: TransientEntity, blobName: String, stream: InputStream) {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun setBlob(transientEntity: TransientEntity, blobName: String, file: File) {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun setBlobString(transientEntity: TransientEntity, blobName: String, newValue: String): Boolean {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun deleteBlob(transientEntity: TransientEntity, blobName: String): Boolean {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun setLink(source: TransientEntity, linkName: String, target: TransientEntity): Boolean {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun addLink(source: TransientEntity, linkName: String, target: TransientEntity): Boolean {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun deleteLinks(source: TransientEntity, linkName: String) {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun deleteEntity(transientEntity: TransientEntity): Boolean {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun deleteLink(source: TransientEntity, linkName: String, target: TransientEntity): Boolean {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun setProperty(
        transientEntity: TransientEntity,
        propertyName: String,
        propertyNewValue: Comparable<*>
    ): Boolean {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun setToOne(source: TransientEntity, linkName: String, target: TransientEntity?) {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun setManyToOne(
        many: TransientEntity,
        manyToOneLinkName: String,
        oneToManyLinkName: String,
        one: TransientEntity?
    ) {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun clearOneToMany(one: TransientEntity, manyToOneLinkName: String, oneToManyLinkName: String) {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun createManyToMany(
        e1: TransientEntity,
        e1Toe2LinkName: String,
        e2Toe1LinkName: String,
        e2: TransientEntity
    ) {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun clearManyToMany(e1: TransientEntity, e1Toe2LinkName: String, e2Toe1LinkName: String) {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun setOneToOne(
        e1: TransientEntity,
        e1Toe2LinkName: String,
        e2Toe1LinkName: String,
        e2: TransientEntity?
    ) {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun removeOneToMany(
        one: TransientEntity,
        manyToOneLinkName: String,
        oneToManyLinkName: String,
        many: TransientEntity
    ) {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun removeFromParent(
        child: TransientEntity,
        parentToChildLinkName: String,
        childToParentLinkName: String
    ) {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun removeChild(parent: TransientEntity, parentToChildLinkName: String, childToParentLinkName: String) {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun setChild(
        parent: TransientEntity,
        parentToChildLinkName: String,
        childToParentLinkName: String,
        child: TransientEntity
    ) {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun clearChildren(parent: TransientEntity, parentToChildLinkName: String) {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }

    override fun deleteProperty(transientEntity: TransientEntity, propertyName: String): Boolean {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }


    override fun hasChanges() = false

    override fun clear() {

    }

    override fun apply() {

    }

    override fun addChange(change: () -> Boolean): () -> Boolean {
        throw IllegalStateException("Readonly transaction cannot perform write operations")
    }
}
