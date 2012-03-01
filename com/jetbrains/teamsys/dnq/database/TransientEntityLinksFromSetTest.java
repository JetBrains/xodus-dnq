package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.mps.dnq.common.tests.AbstractEntityStoreAwareTestCase;
import com.jetbrains.mps.dnq.common.tests.TestOnlyServiceLocator;
import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import com.jetbrains.teamsys.dnq.association.DirectedAssociationSemantics;
import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics;
import jetbrains.exodus.core.dataStructures.NanoSet;
import jetbrains.exodus.core.dataStructures.hash.cuckoo.HashSet;
import jetbrains.exodus.database.Entity;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.database.async.EntityStoreSharedAsyncProcessor;
import jetbrains.exodus.database.impl.iterate.EntityIteratorWithPropId;

public class TransientEntityLinksFromSetTest extends AbstractEntityStoreAwareTestCase {

    public void testAll() {
        TransientEntity i1, i2, i3, i4;
        TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
        TransientStoreSession transientStoreSession = store.beginSession(1);
        try {
            i1 = (TransientEntity) transientStoreSession.newEntity("Issue");
            i2 = (TransientEntity) transientStoreSession.newEntity("Issue");
            i3 = (TransientEntity) transientStoreSession.newEntity("Issue");
            i4 = (TransientEntity) transientStoreSession.newEntity("Issue");
        } catch (Throwable e) {
            TransientStoreUtil.abort(e, transientStoreSession);
            throw new RuntimeException("Should never be thrown.");
        } finally {
            TransientStoreUtil.commit(transientStoreSession);
        }

        final HashSet<String> names = new HashSet<String>();
        names.add("dup");
        names.add("hup");

        transientStoreSession = store.beginSession(1);
        try {
            DirectedAssociationSemantics.createToMany(i1, "dup", i2);
            DirectedAssociationSemantics.createToMany(i1, "hup", i3);
            DirectedAssociationSemantics.createToMany(i1, "hup", i4);
            DirectedAssociationSemantics.createToMany(i2, "dup", i3);

            check_i1((EntityIteratorWithPropId) AssociationSemantics.getAddedLinks(i1, names).iterator(), i2, i3, i4);
            check_i2((EntityIteratorWithPropId) AssociationSemantics.getAddedLinks(i2, names).iterator(), i3);
        } catch (Throwable e) {
            TransientStoreUtil.abort(e, transientStoreSession);
            throw new RuntimeException("Should never be thrown.");
        } finally {
            TransientStoreUtil.commit(transientStoreSession);
        }

        transientStoreSession = store.beginSession(1);
        try {
            check_i1((EntityIteratorWithPropId) AssociationSemantics.getToMany(i1, names).iterator(), i2, i3, i4);
            check_i2((EntityIteratorWithPropId) AssociationSemantics.getToMany(i2, names).iterator(), i3);
        } catch (Throwable e) {
            TransientStoreUtil.abort(e, transientStoreSession);
            throw new RuntimeException("Should never be thrown.");
        } finally {
            TransientStoreUtil.commit(transientStoreSession);
        }

        transientStoreSession = store.beginSession(1);
        try {
            DirectedAssociationSemantics.removeToMany(i1, "dup", i2);
            DirectedAssociationSemantics.removeToMany(i1, "hup", i3);
            DirectedAssociationSemantics.removeToMany(i1, "hup", i4);
            DirectedAssociationSemantics.removeToMany(i2, "dup", i3);

            check_i1((EntityIteratorWithPropId) AssociationSemantics.getRemovedLinks(i1, names).iterator(), i2, i3, i4);
            check_i2((EntityIteratorWithPropId) AssociationSemantics.getRemovedLinks(i2, names).iterator(), i3);
        } catch (Throwable e) {
            TransientStoreUtil.abort(e, transientStoreSession);
            throw new RuntimeException("Should never be thrown.");
        } finally {
            TransientStoreUtil.commit(transientStoreSession);
        }
    }

    private void check_i2(EntityIteratorWithPropId it, TransientEntity i3) {
        assertTrue(it.hasNext());
        assertEquals(i3, it.next());
        assertEquals("dup", it.currentLinkName());
        assertFalse(it.hasNext());
    }

    private void check_i1(EntityIteratorWithPropId it, TransientEntity i2, TransientEntity i3, TransientEntity i4) {
        assertTrue(it.hasNext());
        assertEquals(i2, it.next());
        assertEquals("dup", it.currentLinkName());
        assertTrue(it.hasNext());
        final Entity candidate = it.next();
        assertEquals("hup", it.currentLinkName());
        assertTrue(it.hasNext());
        if (i3.equals(candidate)) { // handle set nondeterminism
            assertEquals(i4, it.next());
        } else {
            assertEquals(i4, candidate);
            assertEquals(i3, it.next());
        }
        assertEquals("hup", it.currentLinkName());
        assertFalse(it.hasNext());
    }
}
