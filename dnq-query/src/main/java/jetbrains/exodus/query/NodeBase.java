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
package jetbrains.exodus.query;

import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery;
import jetbrains.exodus.query.metadata.ModelMetaData;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public abstract class NodeBase {
    private static final String TREE_LEVEL_INDENT = "  ";

    private NodeBase parent;

    protected NodeBase() {
    }

    public NodeBase getParent() {
        return parent;
    }

    public void setParent(NodeBase parent) {
        this.parent = parent;
    }

    @Nonnull
    public abstract GremlinQuery getQuery();

    public abstract Iterable<Entity> instantiate(String entityType,
                                                 QueryEngine queryEngine,
                                                 ModelMetaData metaData);

    public abstract NodeBase getClone();

    public Collection<NodeBase> getChildren() {
        return List.<NodeBase>of();
    }

    @SuppressWarnings({"EqualsWhichDoesntCheckParameterClass"})
    @Override
    public boolean equals(Object obj) {
        NodeBase node = (NodeBase) obj;
        Iterator<NodeBase> iterator = node.getChildren().iterator();
        for (NodeBase child1 : getChildren()) {
            NodeBase child2 = iterator.next();
            if (!(child1.equals(child2))) {
                return false;
            }
        }
        return true;
    }

    public String toString() {
        return toString("");
    }

    boolean toString(StringBuilder result, NodeBase subtree, String presentation) {
        return toString(result, "", subtree, presentation);
    }

    protected String toString(String prefix) {
        final StringBuilder result = new StringBuilder(prefix).append(getClass().getSimpleName());
        for (NodeBase child : getChildren()) {
            result.append('\n').append(child.toString(TREE_LEVEL_INDENT + prefix));
        }
        return result.toString();
    }

    private boolean toString(StringBuilder result, String prefix, NodeBase subtree, String presentation) {
        if (equals(subtree)) {
            result.append((prefix + presentation).replace("\n", '\n' + prefix));
            return true;
        }
        result.append(prefix);
        result.append(getClass().getSimpleName());
        boolean used = false;
        for (NodeBase child : getChildren()) {
            result.append('\n');
            StringBuilder childResult = new StringBuilder();
            boolean presentationUsed = !used && child.toString(childResult, TREE_LEVEL_INDENT + prefix, subtree, presentation);
            if (presentationUsed) {
                subtree = null;
                result.append(childResult);
            } else {
                result.append(child.toString(TREE_LEVEL_INDENT + prefix));
            }
            used |= presentationUsed;
        }
        return used;
    }

    public abstract String getSimpleName();

    public int size() {
        int r = 1;
        for (NodeBase child : getChildren()) {
            r += child.size();
        }
        return r;
    }
}
