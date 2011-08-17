package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.Pair;
import com.jetbrains.teamsys.core.dataStructures.decorators.HashSetDecorator;
import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.database.exceptions.*;
import com.jetbrains.teamsys.dnq.association.AggregationAssociationSemantics;
import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import com.jetbrains.teamsys.dnq.association.DirectedAssociationSemantics;
import com.jetbrains.teamsys.dnq.association.UndirectedAssociationSemantics;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings({"ThrowableInstanceNeverThrown"})
class ConstraintsUtil {

    private static final Log log = LogFactory.getLog(ConstraintsUtil.class);


    //TODO: performance tip: use getPersistentIterable.next instead of getLinksSize

    static boolean checkCardinality(TransientEntity e, AssociationEndMetaData md) {
        return checkCardinality(e, md.getCardinality(), md.getName());
    }

    static boolean checkCardinality(TransientEntity e, AssociationEndCardinality cardinality, String associationName) {
        long size;

        switch (cardinality) {
            case _0_1:
                size = e.getLinksSize(associationName);
                return size <= 1;

            case _0_n:
                return true;

            case _1:
                size = e.getLinksSize(associationName);
                return size == 1;

            case _1_n:
                size = e.getLinksSize(associationName);
                return size >= 1;
        }

        throw new IllegalArgumentException("Unknown cardinality [" + cardinality + "]");
    }

    @NotNull
    static Set<DataIntegrityViolationException> checkIncomingLinks(@NotNull TransientChangesTracker changesTracker, @NotNull ModelMetaData modelMetaData) {
        final Set<DataIntegrityViolationException> exceptions = new HashSetDecorator<DataIntegrityViolationException>();

        for (TransientEntity e : changesTracker.getChangedEntities()) {
            if (e.isRemoved()) {
                List<Pair<String, EntityIterable>> incomingLinks = e.getIncomingLinks();

                if (incomingLinks.size() > 0) {
                    List<Pair<String, EntityIterable>> _incomingLinks = new ArrayList<Pair<String, EntityIterable>>();
                    for (Pair<String, EntityIterable> pair : incomingLinks) {
                        final StoreSession storeSession = e.getTransientStoreSession();

                        if (storeSession == null) {
                            throw new IllegalStateException("No current transient session!");
                        }

                        EntityIterable links = pair.getSecond();

                        EntityIterator linksIterator = links.iterator();
                        while (linksIterator.hasNext()){

                            TransientEntity entity = ((TransientEntity) linksIterator.next());

                            if (entity == null || entity.isRemoved() || entity.getRemovedLinks(pair.getFirst()).contains(e)) {
                                continue;
                            }

                            _incomingLinks.add(pair);
                        }
                    }
                    if (_incomingLinks.size() > 0) {
                        EntityMetaData metaData = modelMetaData.getEntityMetaData(e.getType());
                        exceptions.add(metaData.getInstance(e).createIncomingLinksException(_incomingLinks, modelMetaData, e));
                    }
                }
            }
        }

        return exceptions;
    }

    @NotNull
    static Set<DataIntegrityViolationException> checkAssociationsCardinality(@NotNull TransientChangesTracker changesTracker, @NotNull ModelMetaData modelMetaData) {
        Set<DataIntegrityViolationException> exceptions = new HashSetDecorator<DataIntegrityViolationException>();

        for (TransientEntity e : changesTracker.getChangedEntities()) {
            if (!e.isRemoved()) {
                // if entity is new - check cardinality of all links
                // if entity saved - check cardinality of changed links only
                EntityMetaData md = modelMetaData.getEntityMetaData(e.getType());

                // meta-data may be null for persistent enums
                if (e.isNewOrTemporary()) {
                    // check all links of new entity
                    for (AssociationEndMetaData aemd : md.getAssociationEndsMetaData()) {
                        if (log.isTraceEnabled()) {
                            log.trace("Check cardinality [" + e.getType() + "." + aemd.getName() + "]. Required is [" + aemd.getCardinality().getName() + "]");
                        }

                        if (!checkCardinality(e, aemd)) {
                            exceptions.add(new CardinalityViolationException(e, aemd));
                        }
                    }
                } else if (e.isSaved()) {
                    // check only changed links of saved entity
                    Map<String, LinkChange> changedLinks = changesTracker.getChangedLinksDetailed(e);
                    if (changedLinks != null) {
                        for (String changedLink : changedLinks.keySet()) {
                            AssociationEndMetaData aemd = md.getAssociationEndMetaData(changedLink);

                            if (aemd == null) {
                                log.debug("aemd is null. Type: [" + e.getType() + "]. Changed link: " + changedLink);
                            } else {
                                if (log.isTraceEnabled()) {
                                    log.trace("Check cardinality [" + e.getType() + "." + aemd.getName() + "]. Required is [" + aemd.getCardinality().getName() + "]");
                                }

                                if (!checkCardinality(e, aemd)) {
                                    exceptions.add(new CardinalityViolationException(e, aemd));
                                }

                            }

                        }
                    }
                }
            }
        }

        return exceptions;
    }

    static void processOnDeleteConstraints(@NotNull TransientStoreSession session, @NotNull TransientEntity e, @NotNull EntityMetaData emd, @NotNull ModelMetaData md, boolean callDestructorsPhase, Set<Entity> processed) {
        // outgoing associations
        for (AssociationEndMetaData amd : emd.getAssociationEndsMetaData()) {
            if (amd.getCascadeDelete() || amd.getClearOnDelete()) {

                if (log.isDebugEnabled()) {
                    if (amd.getCascadeDelete()) {
                        log.debug("Cascade delete targets for link [" + e + "]." + amd.getName());
                    }

                    if (amd.getClearOnDelete()) {
                        log.debug("Clear associations with targets for link [" + e + "]." + amd.getName());
                    }
                }
                processOnSourceDeleteConstrains(e, amd, callDestructorsPhase, processed);
            }
        }

        // incoming associations
        Map<String, Set<String>> incomingAssociations = emd.getIncomingAssociations(md);
        for (String key : incomingAssociations.keySet()) {
            for (String linkName : incomingAssociations.get(key)) {
                processOnTargetDeleteConstraints(e, md, key, linkName, session, callDestructorsPhase, processed);
            }
        }
    }

    private static void processOnDeleteConstrainsSwitch(Entity e, AssociationEndMetaData amd, boolean callDestructorsPhase, Set<Entity> processed) {
        // cascade delete depend association end type and cardinality
        switch (amd.getAssociationEndType()) {
            case ParentEnd:
                switch (amd.getCardinality()) {

                    case _0_1:
                    case _1:
                        processOnDeleteConstraintForParentEndToSingleChild(e, amd, callDestructorsPhase, processed);
                        break;

                    case _0_n:
                    case _1_n:
                        processOnDeleteConstraintForParentEndToMultipleChild(e, amd, callDestructorsPhase, processed);
                        break;
                }
                break;

            //Unreproducible case due to OnDelete(cascade) constraint preset for parent end
            case ChildEnd:
                switch (amd.getAssociationMetaData().getOppositeEnd(amd).getCardinality()) {
                    case _0_1:
                    case _1:
                        processOnDeleteConstraintsForSingleChildEnd(e, amd, callDestructorsPhase, processed);
                        break;

                    case _0_n:
                    case _1_n:
                        processOnDeleteConstraintsForMultipleChildEnd(e, amd, callDestructorsPhase, processed);
                        break;
                }
                break;

            case UndirectedAssociationEnd:
                switch (amd.getCardinality()) {
                    case _0_1:
                    case _1:
                        processOnDeleteConstraintForUndirectedAssociationEndToSingle(e, amd, callDestructorsPhase, processed);
                        break;

                    case _0_n:
                    case _1_n:
                        processOnDeleteConstraintForUndirectedAssociationEndToMultiple(e, amd, callDestructorsPhase, processed);
                        break;
                }
                break;

            case DirectedAssociationEnd:
                switch (amd.getCardinality()) {
                    case _0_1:
                    case _1:
                        processOnDeleteConstraintForDirectedAssociationEndToSingle(e, amd, callDestructorsPhase, processed);
                        break;

                    case _0_n:
                    case _1_n:
                        processOnDeleteConstraintForDirectedAssociationEndToMultiple(e, amd, callDestructorsPhase, processed);
                        break;
                }
                break;

            default:
                throw new IllegalArgumentException("Cascade delete is not supported for association end type [" + amd.getAssociationEndType() + "]");
        }
    }

    private static void processOnTargetDeleteConstrainsSwitch(Entity source, Entity target, String linkName, AssociationEndMetaData amd, boolean callDestructorsPhase) {
        // cascade delete depend association end type and cardinality
        switch (amd.getAssociationEndType()) {
            //Unreproducible case due to OnDelete(clear) constraint preset for child end
            case ParentEnd:
                switch (amd.getCardinality()) {

                    case _0_1:
                    case _1:
                        //Unreproducible case due to OnDelete(clear) constraint preset for child end
                        processOnTargetDeleteConstraintForParentEndToSingleChild(source, linkName, amd, callDestructorsPhase);
                        break;

                    case _0_n:
                    case _1_n:
                        //Unreproducible case due to OnDelete(clear) constraint preset for child end
                        processOnTargetDeleteConstraintForParentEndToMultipleChild(source, target, linkName, amd, callDestructorsPhase);
                        break;
                }
                break;

            case ChildEnd:
                switch (amd.getAssociationMetaData().getOppositeEnd(amd).getCardinality()) {
                    case _0_1:
                    case _1:
                        //Unreproducible case due to OnDelete(cascade) constraint preset for parent end
                        processOnTargetDeleteConstraintsForSingleChildEnd(target, linkName, amd, callDestructorsPhase);
                        break;

                    case _0_n:
                    case _1_n:
                        //Unreproducible case due to OnDelete(cascade) constraint preset for parent end
                        processOnTargetDeleteConstraintsForMultipleChildEnd(target, source, linkName, amd, callDestructorsPhase);
                        break;
                }
                break;

            case UndirectedAssociationEnd:
                switch (amd.getCardinality()) {
                    case _0_1:
                    case _1:
                        processOnTargetDeleteConstraintForUndirectedAssociationEndToSingle(source, target, linkName, amd, callDestructorsPhase);
                        break;

                    case _0_n:
                    case _1_n:
                        processOnTargetDeleteConstraintForUndirectedAssociationEndToMultiple(source, target, linkName, amd, callDestructorsPhase);
                        break;
                }
                break;

            case DirectedAssociationEnd:
                switch (amd.getCardinality()) {
                    case _0_1:
                    case _1:
                        processOnTargetDeleteConstraintForDirectedAssociationEndToSingle(source, linkName, callDestructorsPhase);
                        break;

                    case _0_n:
                    case _1_n:
                        processOnTargetDeleteConstraintForDirectedAssociationEndToMultiple(source, target, linkName, callDestructorsPhase);
                        break;
                }
                break;

            default:
                throw new IllegalArgumentException("Cascade delete is not supported for association end type [" + amd.getAssociationEndType() + "]");
        }
    }

    private static void processOnTargetDeleteConstraints(TransientEntity target, ModelMetaData md, String oppositeType, String linkName, TransientStoreSession session, boolean callDestructorsPhase, Set<Entity> processed) {
        EntityMetaData oppositeEmd = md.getEntityMetaData(oppositeType);
        if (oppositeEmd == null) {
            throw new RuntimeException("can't find metadata for entity type " + oppositeType + " as opposite to " + target.getType());
        }
        AssociationEndMetaData amd = oppositeEmd.getAssociationEndMetaData(linkName);
        final EntityIterator it = session.findLinks(oppositeType, target, linkName).iterator();
        TransientChangesTracker changesTracker = session.getTransientChangesTracker();
        while (it.hasNext()) {
            TransientEntity source = (TransientEntity) it.next();
            if (source.isRemoved()) continue;

            Map<String, LinkChange> changedLinks = changesTracker.getChangedLinksDetailed(source);
            boolean linkRemoved = false;
            if (changedLinks != null) { // changed links can be null
                LinkChange change = changedLinks.get(linkName);
                if (change != null) { // change can be null if current link is not changed, but some was
                    Set<TransientEntity> removed = change.getRemovedEntities();
                    linkRemoved = (removed == null) ? false : removed.contains(target);
                }
            }

            if (!linkRemoved) {
                if (amd.getTargetCascadeDelete()) {
                    if (log.isDebugEnabled()) {
                        log.debug("cascade delete targets for link [" + source + "]." + linkName);
                    }
                    EntityOperations.remove(source, callDestructorsPhase, processed);
                } else if (amd.getTargetClearOnDelete() && !callDestructorsPhase) {
                    if (log.isDebugEnabled()) {
                        log.debug("clear associations with targets for link [" + source + "]." + linkName);
                    }
                    //processOnTargetDeleteConstrainsSwitch(source, target, linkName, amd, callDestructorsPhase);
                    removeLink(source, target, amd);
                }
            }
        }
    }


    private static void processOnDeleteConstraintsForSingleChildEnd(Entity child, AssociationEndMetaData amd, boolean callDestructorsPhase, Set<Entity> processed) {
        if (!callDestructorsPhase) {
            Entity parent = AssociationSemantics.getToOne(child, amd.getName());

            if (parent != null && !EntityOperations.isRemoved(parent)) {
                if (amd.getCascadeDelete() ||
                        (amd.getClearOnDelete() &&
                                amd.getAssociationMetaData().getOppositeEnd(amd).getTargetCascadeDelete())) {
                    EntityOperations.remove(parent, callDestructorsPhase, processed);
                } else {
                    AggregationAssociationSemantics.setOneToOne(parent, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), amd.getName(), null);
                }
            }
        }
    }

    private static void processOnDeleteConstraintsForMultipleChildEnd(Entity child, AssociationEndMetaData amd, boolean callDestructorsPhase, Set<Entity> processed) {
        if (!callDestructorsPhase) {
            Entity parent = AssociationSemantics.getToOne(child, amd.getName());

            if (parent != null && !EntityOperations.isRemoved(parent)) {
                if (amd.getCascadeDelete() ||
                        (amd.getClearOnDelete() &&
                                amd.getAssociationMetaData().getOppositeEnd(amd).getTargetCascadeDelete())) {
                    EntityOperations.remove(parent, callDestructorsPhase, processed);
                } else {
                    AggregationAssociationSemantics.removeOneToMany(parent, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), amd.getName(), child);
                }
            }
        }
    }

    static void processOnDeleteConstraintForDirectedAssociationEndToMultiple(Entity e, AssociationEndMetaData amd, boolean callDestructorsPhase, Set<Entity> processed) {
        for (Entity target : AssociationSemantics.getToManyList(e, amd.getName())) {
            if (EntityOperations.isRemoved(target)) continue;

            if (amd.getCascadeDelete()) {
                EntityOperations.remove(target, callDestructorsPhase, processed);
            } else if (!callDestructorsPhase) {
                DirectedAssociationSemantics.removeToMany(e, amd.getName(), target);
            }
        }
    }

    static void processOnDeleteConstraintForDirectedAssociationEndToSingle(Entity e, AssociationEndMetaData amd, boolean callDestructorsPhase, Set<Entity> processed) {
        Entity target;
        target = AssociationSemantics.getToOne(e, amd.getName());
        if (target != null && !EntityOperations.isRemoved(target)) {
            if (amd.getCascadeDelete()) {
                EntityOperations.remove(target, callDestructorsPhase, processed);
            } else if (!callDestructorsPhase) {
                DirectedAssociationSemantics.setToOne(e, amd.getName(), null);
            }
        }
    }

    static void processOnDeleteConstraintForUndirectedAssociationEndToSingle(Entity e, AssociationEndMetaData amd, boolean callDestructorsPhase, Set<Entity> processed) {
        Entity target = AssociationSemantics.getToOne(e, amd.getName());
        if (target != null && !EntityOperations.isRemoved(target)) {

            if (amd.getCascadeDelete() ||
                    (amd.getClearOnDelete() &&
                            amd.getAssociationMetaData().getOppositeEnd(amd).getTargetCascadeDelete())) {
                EntityOperations.remove(target, callDestructorsPhase, processed);
            } else if (!callDestructorsPhase) {
                switch (amd.getAssociationMetaData().getOppositeEnd(amd).getCardinality()) {
                    case _0_1:
                    case _1:
                        // one to one
                        UndirectedAssociationSemantics.setOneToOne(e, amd.getName(), amd.getAssociationMetaData().getOppositeEnd(amd).getName(), null);
                        break;

                    case _0_n:
                    case _1_n:
                        // many to one
                        UndirectedAssociationSemantics.removeOneToMany(target, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), amd.getName(), e);
                        break;
                }
            }

        }
    }

    static void processOnDeleteConstraintForUndirectedAssociationEndToMultiple(Entity e, AssociationEndMetaData amd, boolean callDestructorsPhase, Set<Entity> processed) {
        for (Entity target : AssociationSemantics.getToManyList(e, amd.getName())) {
            if (EntityOperations.isRemoved(target)) continue;

            if (amd.getCascadeDelete() ||
                    (amd.getClearOnDelete() &&
                            amd.getAssociationMetaData().getOppositeEnd(amd).getTargetCascadeDelete())) {
                EntityOperations.remove(target, callDestructorsPhase, processed);
            } else if (!callDestructorsPhase) {
                switch (amd.getAssociationMetaData().getOppositeEnd(amd).getCardinality()) {
                    case _0_1:
                    case _1:
                        // one to many
                        UndirectedAssociationSemantics.removeOneToMany(e, amd.getName(), amd.getAssociationMetaData().getOppositeEnd(amd).getName(), target);
                        break;

                    case _0_n:
                    case _1_n:
                        // many to many
                        UndirectedAssociationSemantics.removeManyToMany(e, amd.getName(), amd.getAssociationMetaData().getOppositeEnd(amd).getName(), target);
                        break;
                }
            }
        }
    }

    static void processOnDeleteConstraintForParentEndToMultipleChild(Entity e, AssociationEndMetaData amd, boolean callDestructorsPhase, Set<Entity> processed) {
        for (Entity child : AssociationSemantics.getToManyList(e, amd.getName())) {
            if (EntityOperations.isRemoved(child)) continue;

            if (amd.getCascadeDelete() ||
                    (amd.getClearOnDelete() &&
                            amd.getAssociationMetaData().getOppositeEnd(amd).getTargetCascadeDelete())) {
                EntityOperations.remove(child, callDestructorsPhase, processed);
            } else if (!callDestructorsPhase) {
                AggregationAssociationSemantics.removeOneToMany(
                        e, amd.getName(),
                        amd.getAssociationMetaData().getOppositeEnd(amd).getName(), child);
            }
        }
    }

    static void processOnDeleteConstraintForParentEndToSingleChild(Entity e, AssociationEndMetaData amd, boolean callDestructorsPhase, Set<Entity> processed) {
        Entity child = AssociationSemantics.getToOne(e, amd.getName());
        if (child != null && !EntityOperations.isRemoved(child)) {

            if (amd.getCascadeDelete() ||
                    (amd.getClearOnDelete() &&
                            amd.getAssociationMetaData().getOppositeEnd(amd).getTargetCascadeDelete())) {
                EntityOperations.remove(child, callDestructorsPhase, processed);
            } else if (!callDestructorsPhase) {
                AggregationAssociationSemantics.setOneToOne(e, amd.getName(), amd.getAssociationMetaData().getOppositeEnd(amd).getName(), null);
            }
        }
    }

    /**********************************************************************************/

    private static void processOnSourceDeleteConstrains(Entity e, AssociationEndMetaData amd, boolean callDestructorsPhase, Set<Entity> processed) {
        switch (amd.getCardinality()) {

            case _0_1:
            case _1:
                processOnSourceDeleteConstraintForSingleLink(e, amd, callDestructorsPhase, processed);
                break;

            case _0_n:
            case _1_n:
                processOnSourceDeleteConstraintForMultipleLink(e, amd, callDestructorsPhase, processed);
                break;
        }
    }

    private static boolean getOppositeEndOnTargetDeleteCascade(AssociationEndMetaData endMetaData) {
        if (endMetaData.getAssociationEndType().equals(AssociationEndType.DirectedAssociationEnd)) {
            // there is no opposite end in directed association
            return false;
        }
        return endMetaData.getAssociationMetaData().getOppositeEnd(endMetaData).getTargetCascadeDelete();
    }

    private static AssociationEndMetaData getOppositeEndSafely(AssociationEndMetaData endMetaData) {
        try {
            return endMetaData.getAssociationMetaData().getOppositeEnd(endMetaData);
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private static void processOnSourceDeleteConstraintForSingleLink(Entity source, AssociationEndMetaData amd, boolean callDestructorsPhase, Set<Entity> processed) {
        Entity target = AssociationSemantics.getToOne(source, amd.getName());
        if (target != null && !EntityOperations.isRemoved(target)) {

            if (amd.getCascadeDelete() || getOppositeEndOnTargetDeleteCascade(amd)) {
                EntityOperations.remove(target, callDestructorsPhase, processed);
            } else if (!callDestructorsPhase) {
                removeSingleLink(source, amd, getOppositeEndSafely(amd), target);
            }
        }
    }

    private static void processOnSourceDeleteConstraintForMultipleLink(Entity source, AssociationEndMetaData amd, boolean callDestructorsPhase, Set<Entity> processed) {
        for (Entity target : AssociationSemantics.getToManyList(source, amd.getName())) {
            if (EntityOperations.isRemoved(target)) continue;

            if (amd.getCascadeDelete() || getOppositeEndOnTargetDeleteCascade(amd)) {
                EntityOperations.remove(target, callDestructorsPhase, processed);
            } else if (!callDestructorsPhase) {
                removeOneLinkFromMultipleLink(source, amd, getOppositeEndSafely(amd), target);
            }
        }
    }

    private static void removeSingleLink(Entity source, AssociationEndMetaData sourceEnd, AssociationEndMetaData targetEnd, Entity target) {
        switch (sourceEnd.getAssociationEndType()) {
            case ParentEnd:
                AggregationAssociationSemantics.setOneToOne(source, sourceEnd.getName(), targetEnd.getName(), null);
                break;

            case ChildEnd:
                // Here is cardinality check because we can remove parent-child link only from the parent side
                switch (targetEnd.getCardinality()) {

                    case _0_1:
                    case _1:
                         AggregationAssociationSemantics.setOneToOne(target, targetEnd.getName(), sourceEnd.getName(), null);
                        break;

                    case _0_n:
                    case _1_n:
                        AggregationAssociationSemantics.removeOneToMany(target, targetEnd.getName(), sourceEnd.getName(), source);
                        break;
                }
                break;

            case UndirectedAssociationEnd:
                switch (targetEnd.getCardinality()) {

                    case _0_1:
                    case _1:
                        // one to one
                        UndirectedAssociationSemantics.setOneToOne(source, sourceEnd.getName(), targetEnd.getName(), null);
                        break;

                    case _0_n:
                    case _1_n:
                        // many to one
                        UndirectedAssociationSemantics.removeOneToMany(target, targetEnd.getName(), sourceEnd.getName(), source);
                        break;
                }
                break;

            case DirectedAssociationEnd:
                DirectedAssociationSemantics.setToOne(source, sourceEnd.getName(), null);
                break;

            default:
                throw new IllegalArgumentException("Cascade delete is not supported for association end type [" + sourceEnd.getAssociationEndType() + "] and [..1] cardinality");
        }
    }

    private static void removeOneLinkFromMultipleLink(Entity source, AssociationEndMetaData sourceEnd, AssociationEndMetaData targetEnd, Entity target) {
        switch (sourceEnd.getAssociationEndType()) {
            case ParentEnd:
                AggregationAssociationSemantics.removeOneToMany(source, sourceEnd.getName(), targetEnd.getName(), target);
                break;

            case UndirectedAssociationEnd:
                switch (targetEnd.getCardinality()) {

                    case _0_1:
                    case _1:
                        // one to many
                        UndirectedAssociationSemantics.removeOneToMany(source, sourceEnd.getName(), targetEnd.getName(), target);
                        break;

                    case _0_n:
                    case _1_n:
                        // many to many
                        UndirectedAssociationSemantics.removeManyToMany(source, sourceEnd.getName(), targetEnd.getName(), target);
                        break;
                }
                break;

            case DirectedAssociationEnd:
                DirectedAssociationSemantics.removeToMany(source, sourceEnd.getName(), target);
                break;

            default:
                throw new IllegalArgumentException("Cascade delete is not supported for association end type [" + sourceEnd.getAssociationEndType() + "] and [..n] cardinality");
        }
    }

     private static void removeLink(Entity source, Entity target, AssociationEndMetaData sourceEnd) {
        switch (sourceEnd.getCardinality()) {

            case _0_1:
            case _1:
                removeSingleLink(source, sourceEnd, getOppositeEndSafely(sourceEnd), target);
                break;

            case _0_n:
            case _1_n:
                removeOneLinkFromMultipleLink(source, sourceEnd, getOppositeEndSafely(sourceEnd), target);
                break;
        }
     }

    /**********************************************************************************/

    //Unreproducible case due to OnDelete(cascade) constraint preset for parent end
    private static void processOnTargetDeleteConstraintsForSingleChildEnd(@NotNull Entity parent, @NotNull String linkName, AssociationEndMetaData amd, boolean callDestructorsPhase) {
        if (!callDestructorsPhase) {
            AggregationAssociationSemantics.setOneToOne(parent, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), linkName, null);
        }
    }

    //Unreproducible case due to OnDelete(cascade) constraint preset for parent end
    private static void processOnTargetDeleteConstraintsForMultipleChildEnd(@NotNull Entity parent, @NotNull Entity child, @NotNull String linkName, AssociationEndMetaData amd, boolean callDestructorsPhase) {
        if (!callDestructorsPhase) {
            AggregationAssociationSemantics.removeOneToMany(parent, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), linkName, child);
        }
    }

    private static void processOnTargetDeleteConstraintForDirectedAssociationEndToMultiple(@NotNull Entity source, @NotNull Entity target, @NotNull String linkName, boolean callDestructorsPhase) {
        if (!callDestructorsPhase) {
            DirectedAssociationSemantics.removeToMany(source, linkName, target);
        }
    }

    private static void processOnTargetDeleteConstraintForDirectedAssociationEndToSingle(@NotNull Entity source, @NotNull String linkName, boolean callDestructorsPhase) {
        if (!callDestructorsPhase) {
            DirectedAssociationSemantics.setToOne(source, linkName, null);
        }
    }

    private static void processOnTargetDeleteConstraintForUndirectedAssociationEndToSingle(@NotNull Entity source, @NotNull Entity target, @NotNull String linkName, AssociationEndMetaData amd, boolean callDestructorsPhase) {
        if (!callDestructorsPhase) {
            switch (amd.getAssociationMetaData().getOppositeEnd(amd).getCardinality()) {
                case _0_1:
                case _1:
                    // one to one
                    UndirectedAssociationSemantics.setOneToOne(source, linkName, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), null);
                    break;

                case _0_n:
                case _1_n:
                    // many to one
                    UndirectedAssociationSemantics.removeOneToMany(target, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), linkName, source);
                    break;
            }
        }
    }

    private static void processOnTargetDeleteConstraintForUndirectedAssociationEndToMultiple(@NotNull Entity source, @NotNull Entity target, @NotNull String linkName, AssociationEndMetaData amd, boolean callDestructorsPhase) {
        if (!callDestructorsPhase) {
            switch (amd.getAssociationMetaData().getOppositeEnd(amd).getCardinality()) {
                case _0_1:
                case _1:
                    // one to many
                    UndirectedAssociationSemantics.removeOneToMany(source, amd.getName(), amd.getAssociationMetaData().getOppositeEnd(amd).getName(), target);
                    break;

                case _0_n:
                case _1_n:
                    // many to many
                    UndirectedAssociationSemantics.removeManyToMany(source, linkName, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), target);
                    break;
            }
        }
    }

    //Unreproducible case due to OnDelete(clear) constraint preset for child end
    private static void processOnTargetDeleteConstraintForParentEndToMultipleChild(@NotNull Entity parent, @NotNull Entity child, @NotNull String linkName, AssociationEndMetaData amd, boolean callDestructorsPhase) {
        if (!callDestructorsPhase) {
            AggregationAssociationSemantics.removeOneToMany(parent, linkName, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), child);
        }
    }

    //Unreproducible case due to OnDelete(clear) constraint preset for child end
    private static void processOnTargetDeleteConstraintForParentEndToSingleChild(@NotNull Entity source, @NotNull String linkName, AssociationEndMetaData amd, boolean callDestructorsPhase) {
        if (!callDestructorsPhase) {
            AggregationAssociationSemantics.setOneToOne(source, linkName, amd.getAssociationMetaData().getOppositeEnd(amd).getName(), null);
        }
    }

    @NotNull
    static Set<DataIntegrityViolationException> checkRequiredProperties(@NotNull TransientChangesTracker tracker, @NotNull ModelMetaData md) {
        Set<DataIntegrityViolationException> errors = new HashSetDecorator<DataIntegrityViolationException>();

        for (TransientEntity e : tracker.getChangedEntities()) {
            if (!e.isRemoved()) {

                EntityMetaData emd = md.getEntityMetaData(e.getType());

                Set<String> requiredProperties = emd.getRequiredProperties();
                Set<String> requiredIfProperties = emd.getRequiredIfProperties(e);
                Map<String, PropertyChange> changedProperties = tracker.getChangedPropertiesDetailed(e);

                if ((requiredProperties.size() + requiredIfProperties.size() > 0 && (e.isNewOrTemporary() || (changedProperties != null && changedProperties.size() > 0)))) {
                    for (String requiredPropertyName : requiredProperties) {
                        checkProperty(errors, e, changedProperties, emd, requiredPropertyName);
                    }
                    for (String requiredIfPropertyName : requiredIfProperties) {
                        checkProperty(errors, e, changedProperties, emd, requiredIfPropertyName);
                    }
                }
            }
        }

        return errors;
    }

    /**
     * Properties and associations, that are part of indexes, can't be empty
     *
     * @param tracker changes tracker
     * @param md      model metadata
     * @return index fields errors set
     */
    @NotNull
    static Set<DataIntegrityViolationException> checkIndexFields(@NotNull TransientChangesTracker tracker, @NotNull ModelMetaData md) {
        Set<DataIntegrityViolationException> errors = new HashSetDecorator<DataIntegrityViolationException>();

        for (TransientEntity e : tracker.getChangedEntities()) {
            if (!e.isRemoved()) {

                EntityMetaData emd = md.getEntityMetaData(e.getType());

                Map<String, PropertyChange> changedProperties = tracker.getChangedPropertiesDetailed(e);
                Set<Index> indexes = emd.getIndexes();

                for (Index index : indexes) {
                    for (IndexField f : index.getFields()) {
                        if (f.isProperty()) {
                            if (e.isNewOrTemporary() || (changedProperties != null && changedProperties.size() > 0)) {
                                checkProperty(errors, e, changedProperties, emd, f.getName());
                            }
                        } else {
                            // link
                            if (!checkCardinality(e, AssociationEndCardinality._1, f.getName())) {
                                errors.add(new CardinalityViolationException("Association [" + f.getName() + "] can't be empty, because it's part of unique constraint.", e, f.getName()));
                            }
                        }
                    }
                }
            }
        }

        return errors;
    }

    private static void checkProperty(Set<DataIntegrityViolationException> errors, TransientEntity e, Map<String, PropertyChange> changedProperties, EntityMetaData emd, String name) {
        final PropertyMetaData pmd = emd.getPropertyMetaData(name);
        final PropertyType type;
        if (pmd == null) {
            log.warn("Can't determine property type. Try to get property value as if it of primitive type.");
            type = PropertyType.PRIMITIVE;
        } else {
            type = pmd.getType();
        }

        if (e.isNewOrTemporary() || changedProperties.containsKey(name)) {
            checkProperty(errors, e, name, type);
        }
    }

    private static void checkProperty(Set<DataIntegrityViolationException> errors, TransientEntity e, String name, PropertyType type) {

        switch (type) {
            case PRIMITIVE:
                if (isEmptyPrimitiveProperty(e.getProperty(name))) {
                    errors.add(new NullPropertyException(e, name));
                }
                break;

            case BLOB:
                if (e.getBlob(name) == null) {
                    errors.add(new NullPropertyException(e, name));
                }
                break;

            case TEXT:
                if (isEmptyPrimitiveProperty(e.getBlobString(name))) {
                    errors.add(new NullPropertyException(e, name));
                }
                break;

            default:
                throw new IllegalArgumentException("Unknown property type: " + name);
        }

    }

    private static boolean isEmptyPrimitiveProperty(Comparable propertyValue) {
        return propertyValue == null || "".equals(propertyValue);
    }

}
