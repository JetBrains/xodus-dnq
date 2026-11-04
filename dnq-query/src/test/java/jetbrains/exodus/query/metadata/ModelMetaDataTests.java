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

import junit.framework.TestCase;
import org.junit.Assert;

import static jetbrains.exodus.query.metadata.MetaBuilder.clazz;
import static jetbrains.exodus.query.metadata.MetaBuilder.model;

public class ModelMetaDataTests extends TestCase {

    final private static String TARGET_CLASS_NAME = "TstTarget";
    final private static String SOURCE_SUPER_CLASS_NAME = "TstSuperSource";
    final private static String SOURCE_SUB_CLASS_NAME = "TstSubSource";

    public void testEmptyInterfacesAreImmutableAndNonemptySetterFails() {
        EntityMetaDataImpl entity = entity("Example", null);
        entity.setInterfaces(java.util.Collections.emptyList());
        assertTrue(entity.getInterfaceTypes().isEmpty());
        try {
            entity.getInterfaceTypes().add("Other");
            fail("Mutable interfaces");
        } catch (UnsupportedOperationException expected) {
            assertTrue(entity.getInterfaceTypes().isEmpty());
        }
        try {
            entity.setInterfaces(java.util.List.of("Other"));
            fail("Accepted interfaces");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Example"));
        }
    }

    public void testUnsupportedRegisteredSubclassCanBeRepairedBeforePreparation() {
        for (String invalid : java.util.Arrays.asList("interfaces", null, "Unrelated", "Base", "Leaf")) {
            class CustomEntity extends EntityMetaDataImpl {
                boolean memberships;
                @Override
                public java.util.Collection<String> getInterfaceTypes() {
                    return memberships ? java.util.List.of("Unrelated") : super.getInterfaceTypes();
                }
            }
            CountingModel model = new CountingModel();
            CustomEntity middle = new CustomEntity();
            middle.setType("Middle");
            middle.setSuperType("Base");
            IndexImpl index = index("Middle", "indexed");
            middle.setOwnIndexes(java.util.Set.of(index));
            model.addEntityMetaData(entity("Base", null));
            model.addEntityMetaData(middle);
            model.addEntityMetaData(entity("Leaf", "Middle"));
            model.addEntityMetaData(entity("Unrelated", null));
            if ("interfaces".equals(invalid)) {
                middle.memberships = true;
            } else {
                index.setOwnerEntityType(invalid);
            }
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    model.prepare();
                    fail("Accepted invalid input: " + invalid);
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage().contains("Middle"));
                }
                assertEquals(0, model.callbacks);
            }
            // No reset or re-registration can conceal a published prepared cache.
            middle.memberships = false;
            index.setOwnerEntityType("Middle");
            assertSame(middle, model.prepare().get("Middle"));
            assertEquals(1, model.callbacks);
            model.prepare();
            assertEquals(1, model.callbacks);
        }
    }

    public void testLiteralClassHierarchyAndEffectiveIndexes() {
        CountingModel model = new CountingModel();
        EntityMetaDataImpl base = entity("Base", null);
        EntityMetaDataImpl middle = entity("Middle", "Base");
        EntityMetaDataImpl leaf = entity("Leaf", "Middle");
        EntityMetaDataImpl sibling = entity("Sibling", "Base");
        IndexImpl inherited = index("Base", "inherited");
        IndexImpl childOnly = index("Leaf", "childOnly");
        base.setOwnIndexes(java.util.Set.of(inherited));
        leaf.setOwnIndexes(java.util.Set.of(childOnly));
        for (EntityMetaDataImpl e : java.util.List.of(base, middle, leaf, sibling)) {
            model.addEntityMetaData(e);
        }
        model.prepare();
        assertEquals(java.util.List.of("Base"), base.getThisAndSuperTypes());
        assertEquals(java.util.List.of("Middle", "Base"), middle.getThisAndSuperTypes());
        assertEquals(java.util.List.of("Leaf", "Middle", "Base"), leaf.getThisAndSuperTypes());
        assertEquals(java.util.List.of("Sibling", "Base"), sibling.getThisAndSuperTypes());
        assertEquals(java.util.Set.of("Middle", "Sibling"), new java.util.HashSet<>(base.getSubTypes()));
        assertEquals(java.util.Set.of("Middle", "Leaf", "Sibling"), new java.util.HashSet<>(base.getAllSubTypes()));
        assertEquals(java.util.Set.of("Leaf"), new java.util.HashSet<>(middle.getSubTypes()));
        assertTrue(leaf.getAllSubTypes().isEmpty());
        for (EntityMetaDataImpl e : java.util.List.of(base, middle, sibling)) {
            assertEquals(java.util.Set.of(inherited), e.getIndexes());
            assertEquals(java.util.Set.of(inherited), e.getIndexes("inherited"));
            assertTrue(e.getIndexes("childOnly").isEmpty());
        }
        assertEquals(java.util.Set.of(inherited, childOnly), leaf.getIndexes());
        assertEquals(java.util.Set.of(inherited), leaf.getIndexes("inherited"));
        assertEquals(java.util.Set.of(childOnly), leaf.getIndexes("childOnly"));
        assertTrue(leaf.getIndexes("absent").isEmpty());
        for (String type : java.util.List.of("Leaf", "Middle", "Base")) {
            assertTrue(jetbrains.exodus.query.Utils.isTypeOf("Leaf", type, model));
        }
        assertFalse(jetbrains.exodus.query.Utils.isTypeOf("Leaf", "Sibling", model));
        assertFalse(jetbrains.exodus.query.Utils.isTypeOf("Base", "Leaf", model));
        assertTrue(jetbrains.exodus.query.Utils.isTypeOf("Unknown", "Unknown", model));
        assertFalse(jetbrains.exodus.query.Utils.isTypeOf("Unknown", "Base", model));
        assertFalse(jetbrains.exodus.query.Utils.isTypeOf("Leaf", "Unknown", model));
        assertFalse(jetbrains.exodus.query.Utils.isTypeOf(null, "Base", model));
    }

    private static EntityMetaDataImpl entity(String type, String parent) {
        EntityMetaDataImpl result = new EntityMetaDataImpl();
        result.setType(type);
        result.setSuperType(parent);
        return result;
    }

    private static IndexImpl index(String owner, String fieldName) {
        IndexFieldImpl field = new IndexFieldImpl();
        field.setName(fieldName);
        field.setProperty(true);
        IndexImpl result = new IndexImpl();
        result.setOwnerEntityType(owner);
        result.setFields(java.util.List.of(field));
        return result;
    }

    private static class CountingModel extends ModelMetaDataImpl {
        int callbacks;
        @Override
        protected void onPrepared(java.util.Collection<EntityMetaData> entities) {
            callbacks++;
        }
    }

    public void testAddAssociationToSuperClass() {

        ModelMetaData modelMetaData = model(
                clazz(TARGET_CLASS_NAME)
                        .prop("name", "string"),
                clazz(SOURCE_SUPER_CLASS_NAME)
                        .prop("name", "string"),
                clazz(SOURCE_SUB_CLASS_NAME, SOURCE_SUPER_CLASS_NAME)
                        .prop("number", "int")
        );

        EntityMetaData superEmd = modelMetaData.getEntityMetaData(SOURCE_SUPER_CLASS_NAME);
        EntityMetaData subEmd = modelMetaData.getEntityMetaData(SOURCE_SUB_CLASS_NAME);

        final String directLinkName = "directLink";
        modelMetaData.addAssociation(
                SOURCE_SUPER_CLASS_NAME, TARGET_CLASS_NAME, AssociationType.Directed,
                directLinkName, AssociationEndCardinality._0_1,
                false, false, false, true,
                null, null,
                false, false, false, false
                );
        Assert.assertNotNull(subEmd.getAssociationEndMetaData(directLinkName));
        Assert.assertEquals(subEmd.getAssociationEndMetaData(directLinkName), superEmd.getAssociationEndMetaData(directLinkName));

        final String forwardLinkName = "targetToSource";
        final String backwardLinkName = "sourceToTarget";
        modelMetaData.addAssociation(
                TARGET_CLASS_NAME, SOURCE_SUPER_CLASS_NAME, AssociationType.Undirected,
                forwardLinkName, AssociationEndCardinality._0_1,
                false, false, false, true,
                backwardLinkName, AssociationEndCardinality._0_1,
                false, false, false, true
        );
        Assert.assertNotNull(subEmd.getAssociationEndMetaData(backwardLinkName));
        Assert.assertEquals(subEmd.getAssociationEndMetaData(backwardLinkName), superEmd.getAssociationEndMetaData(backwardLinkName));

    }
}
