/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
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
package jetbrains.exodus.query.metadata;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 */
public class SimplePropertyMetaDataImpl extends PropertyMetaDataImpl {

    private String primitiveTypeName;

    private List<String> typeParameterNames;

    private boolean autoIndexed = true;

    public SimplePropertyMetaDataImpl() {
    }

    public SimplePropertyMetaDataImpl(final String name, final String primitiveTypeName) {
        this(name, primitiveTypeName, Collections.emptyList());
    }

    public SimplePropertyMetaDataImpl(final String name, final String primitiveTypeName, final List<String> typeParameterNames) {
        super(name, PropertyType.PRIMITIVE);
        this.primitiveTypeName = primitiveTypeName;
        this.typeParameterNames = typeParameterNames;
    }

    @Nullable
    public String getPrimitiveTypeName() {
        return primitiveTypeName;
    }

    /**
     * If you have a property of type Set[String], String is the type parameter.
     * So, getPrimitiveTypeName() returns "Set" and getTypeParameterNames() returns ["String"].
     * */
    @Nullable
    public List<String> getTypeParameterNames() { return typeParameterNames; }

    public void setPrimitiveTypeName(String primitiveTypeName) {
        this.primitiveTypeName = primitiveTypeName;
    }

    /**
     * Whether a database that indexes every simple property (see the {@code indexForEverySimpleProperty} mode of
     * the YouTrackDB schema initializer) should also index this one. {@code true} by default.
     * <p>
     * Opting out matters for properties whose values are unbounded: a B-tree index key may not exceed
     * {@code BTREE_MAX_KEY_SIZE} (30% of the page size, i.e. ~2457 bytes with the default 8 KB page), and a
     * larger value makes the write fail with {@code TooBigIndexKeyException}. An unindexed property is still a
     * regular property - it is readable, writable and usable as a query predicate; the query just scans instead
     * of probing an index.
     */
    public boolean isAutoIndexed() {
        return autoIndexed;
    }

    public void setAutoIndexed(boolean autoIndexed) {
        this.autoIndexed = autoIndexed;
    }
}
