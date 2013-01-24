package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.dnq.association.AggregationAssociationSemantics;
import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import com.jetbrains.teamsys.dnq.association.DirectedAssociationSemantics;
import com.jetbrains.teamsys.dnq.association.UndirectedAssociationSemantics;
import jetbrains.exodus.core.dataStructures.Pair;
import jetbrains.exodus.core.dataStructures.decorators.HashSetDecorator;
import jetbrains.exodus.database.*;
import jetbrains.exodus.database.exceptions.CardinalityViolationException;
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException;
import jetbrains.exodus.database.exceptions.NullPropertyException;
import jetbrains.exodus.database.exceptions.SimplePropertyValidationException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

@SuppressWarnings({"ThrowableInstanceNeverThrown"})
class ConstraintsUtil {

    private static final Log log = LogFactory.getLog(ConstraintsUtil.class);

    static boolean checkCardinality(TransientEntity e, AssociationEndMetaData md) {
        final AssociationEndCardinality cardinality = md.getCardinality();
        if (cardinality == AssociationEndCardinality._0_n) return true;

        final TransientStoreSession session = ((TransientEntityStore)e.getStore()).getThreadSession();
        final EntityIterable links = session.findLinks(md.getOppositeEntityMetaData().getType(), e, md.getName());
        final EntityIterator iter = links.iterator();

        int size = 0;
        while (size < 2 && iter.hasNext()) size++;

        switch (cardinality) {
        case _0_1:
            return size <= 1;

        case _1:
            return size == 1;

        case _1_n:
            return size >= 1;
        }

        throw new IllegalArgumentException("Unknown cardinality [" + cardinality + "]");
    }

    @NotNull
    static Set<DataIntegrityViolationException> checkIncomingLinks(@NotNull TransientChangesTracker changesTracker) {
        final Set<DataIntegrityViolationException> exceptions = new HashSetDecorator<DataIntegrityViolationException>();

        for (TransientEntity e : changesTracker.getChangedEntities()) {
            if (e.isRemoved()) {
                List<Pair<String, EntityIterable>> incomingLinks = e.getIncomingLinks();

                if (incomingLinks.size() > 0) {
                    List<IncomingLinkViolation> badIncomingLinks = new ArrayList<IncomingLinkViolation>();
                    for (Pair<String, EntityIterable> pair : incomingLinks) {
                        IncomingLinkViolation violation = null;
                        EntityIterator linksIterator = pair.getSecond().iterator();
                        while (linksIterator.hasNext()){
                            TransientEntity entity = ((TransientEntity) linksIterator.next());
                            if (entity == null || entity.isRemoved() || entity.getRemovedLinks(pair.getFirst()).contains(e)) {
                                continue;
                            }
                            if (violation == null) {
                                BasePersistentClassImpl impl = TransientStoreUtil.getPersistentClassInstance(entity, entity.getType());
                                violation = impl.createIncomingLinkViolation(pair.getFirst());
                            }
                            if (!violation.tryAddCause(entity)) break;
                        }

                        if (violation != null) {
                            badIncomingLinks.add(violation);
                        }
                    }
                    if (badIncomingLinks.size() > 0) {
                        exceptions.add(TransientStoreUtil.getPersistentClassInstance(e, e.getType()).createIncomingLinksException(badIncomingLinks, e));
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
                if (e.isNew()) {
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
                    removeLink(source, target, amd);
                }
            }
        }
    }

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

    private static void processOnSourceDeleteConstraintForSingleLink(Entity source, AssociationEndMetaData amd, boolean callDestructorsPhase, Set<Entity> processed) {
        Entity target = AssociationSemantics.getToOne(source, amd.getName());
        if (target != null && !EntityOperations.isRemoved(target)) {

            if (amd.getCascadeDelete() || getOnTargetDeleteCascadeAtOppositeEnd(amd)) {
                EntityOperations.remove(target, callDestructorsPhase, processed);
            } else if (!callDestructorsPhase) {
                removeSingleLink(source, amd, getOppositeEndSafely(amd), target);
            }
        }
    }

    private static void processOnSourceDeleteConstraintForMultipleLink(Entity source, AssociationEndMetaData amd, boolean callDestructorsPhase, Set<Entity> processed) {
        for (Entity target : AssociationSemantics.getToManyList(source, amd.getName())) {
            if (EntityOperations.isRemoved(target)) continue;

            if (amd.getCascadeDelete() || getOnTargetDeleteCascadeAtOppositeEnd(amd)) {
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

    private static boolean getOnTargetDeleteCascadeAtOppositeEnd(AssociationEndMetaData endMetaData) {
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

    @NotNull
    static Set<DataIntegrityViolationException> checkRequiredProperties(@NotNull TransientChangesTracker tracker, @NotNull ModelMetaData md) {
        Set<DataIntegrityViolationException> errors = new HashSetDecorator<DataIntegrityViolationException>();

        for (TransientEntity e : tracker.getChangedEntities()) {
            if (!e.isRemoved()) {

                EntityMetaData emd = md.getEntityMetaData(e.getType());

                Set<String> requiredProperties = emd.getRequiredProperties();
                Set<String> requiredIfProperties = EntityMetaDataUtils.getRequiredIfProperties(emd, e);
                Map<String, PropertyChange> changedProperties = tracker.getChangedPropertiesDetailed(e);

                if ((requiredProperties.size() + requiredIfProperties.size() > 0 && (e.isNew() || (changedProperties != null && changedProperties.size() > 0)))) {
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

    @NotNull
    static Set<DataIntegrityViolationException> checkOtherPropertyConstraints(@NotNull TransientChangesTracker tracker, @NotNull ModelMetaData md) {
        Set<DataIntegrityViolationException> errors = new HashSetDecorator<DataIntegrityViolationException>();

        for (TransientEntity e : tracker.getChangedEntities()) {
            if (!e.isRemoved()) {

                EntityMetaData emd = md.getEntityMetaData(e.getType());

                Map<String, Iterable<PropertyConstraint>> propertyConstraints = EntityMetaDataUtils.getPropertyConstraints(emd, e);
                Iterable<String> suspectedProperties = getChangedPropertiesWithConstraints(tracker, e, propertyConstraints.keySet());
                for (String propertyName: suspectedProperties) {
                    PropertyMetaData propertyMetaData = emd.getPropertyMetaData(propertyName);
                    final PropertyType type = getPropertyType(propertyMetaData);
                    Object propertyValue = getPropertyValue(e, propertyName, type);
                    for (PropertyConstraint constraint : propertyConstraints.get(propertyName)) {
                        SimplePropertyValidationException exception = constraint.check(e, propertyMetaData, propertyValue);
                        if (exception != null) {
                            errors.add(exception);
                        }
                    }
                }
            }
        }

        return errors;
    }

    @NotNull
    private static Iterable<String> getChangedPropertiesWithConstraints(TransientChangesTracker tracker, TransientEntity e, Set<String> constraintedProperties) {
        Iterable<String> propertyNames = Collections.emptySet();
        if (constraintedProperties != null && !constraintedProperties.isEmpty()) {
            // Any property has constraints
            Map<String, PropertyChange> changedProperties = tracker.getChangedPropertiesDetailed(e);
            if (e.isNew()) {
                // All properties with constriants
                propertyNames = constraintedProperties;
            } else if (changedProperties != null && !changedProperties.isEmpty()) {
                // Changed properties with constraints
                Set<String> intersection = new HashSet<String>(changedProperties.size());
                for (String changedProperty: changedProperties.keySet()) {
                    if (constraintedProperties.contains(changedProperty)) {
                        intersection.add(changedProperty);
                    }
                }
                propertyNames = intersection;
            }
        }
        return propertyNames;
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
                            if (e.isNew() || (changedProperties != null && changedProperties.size() > 0)) {
                                checkProperty(errors, e, changedProperties, emd, f.getName());
                            }
                        } else {
                            // link
                            if (!checkCardinality(e, emd.getAssociationEndMetaData(f.getName()))) {
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
        final PropertyType type = getPropertyType(emd.getPropertyMetaData(name));

        if (e.isNew() || changedProperties.containsKey(name)) {
            checkProperty(errors, e, name, type);
        }
    }

    @NotNull
    private static PropertyType getPropertyType(PropertyMetaData propertyMetaData) {
        final PropertyMetaData pmd = propertyMetaData;
        final PropertyType type;
        if (pmd == null) {
            log.warn("Can't determine property type. Try to get property value as if it of primitive type.");
            type = PropertyType.PRIMITIVE;
        } else {
            type = pmd.getType();
        }
        return type;
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

    private static Object getPropertyValue(TransientEntity e, String name, PropertyType type) {
        switch (type) {
            case PRIMITIVE:
                return e.getProperty(name);
            case BLOB:
                return e.getBlob(name);
            case TEXT:
                return e.getBlobString(name);
            default:
                throw new IllegalArgumentException("Unknown property type: " + name);
        }

    }

    private static boolean isEmptyPrimitiveProperty(Comparable propertyValue) {
        return propertyValue == null || "".equals(propertyValue);
    }

}
