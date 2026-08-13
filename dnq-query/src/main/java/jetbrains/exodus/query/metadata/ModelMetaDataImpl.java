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

import jetbrains.exodus.core.dataStructures.hash.HashMap;
import jetbrains.exodus.core.dataStructures.hash.HashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ModelMetaDataImpl implements ModelMetaData {

    private static final Logger logger = LoggerFactory.getLogger(ModelMetaDataImpl.class);
    private static final boolean LOG_RESET = Boolean.getBoolean("jetbrains.exodus.query.metadata.logReset");

    private final Set<EntityMetaData> entityMetaDatas = new HashSet<>();
    private final Map<String, AssociationMetaData> associationMetaDatas = new ConcurrentHashMap<>();
    private volatile Map<String, EntityMetaData> typeToEntityMetaDatas = null;
    /**
     * Suppresses the {@link #onPrepared} / {@link #onAddAssociation} schema-application callbacks
     * while a model is being assembled - see {@link #buildModel(Runnable)}. Guarded by the
     * {@code entityMetaDatas} monitor, which is the same monitor {@link #prepare()} and
     * {@link #addEntityMetaData} hold, so no other thread can observe a half-built model.
     * Volatile because {@link #addAssociation} reads it outside the monitor.
     */
    private volatile boolean buildingModel = false;

    /**
     * The association-add deltas collected by an open {@link #batchAssociations(Runnable)} scope on
     * THIS thread, or {@code null} when no scope is open. Thread-confined on purpose: unlike
     * {@link #buildModel(Runnable)}'s process-wide {@code buildingModel} flag, a batch must never
     * swallow the callbacks of another thread's {@code addAssociation} - that thread's caller expects
     * the schema to exist when its call returns, and this thread decides when the batch is applied.
     */
    private final ThreadLocal<List<AddedAssociation>> associationBatch = new ThreadLocal<>();

    /**
     * One association end whose schema application has been deferred by
     * {@link #batchAssociations(Runnable)} - the two arguments {@link #onAddAssociation} would have
     * been called with.
     */
    public record AddedAssociation(
        @NotNull EntityMetaData entityMetaData,
        @NotNull AssociationEndMetaData association
    ) {
    }

    public void init() {
        reset();
        prepare();
    }

    /**
     * Assembles the model in one go: runs {@code build} with the schema-application callbacks
     * ({@link #onPrepared}, {@link #onAddAssociation}) suppressed, then applies the assembled
     * model in a single {@link #prepare()} pass.
     *
     * <p>Bootstrapping a model without this (add all entity types, then add every association)
     * makes the FIRST {@code addAssociation} trigger {@code prepare()} - hence a full
     * {@code onPrepared} schema application of an association-less model - and every association
     * after it a separate {@code onAddAssociation}. For a persistent-store implementation that
     * maps the callbacks onto database schema operations (see the YouTrackDB implementation) that
     * is one schema transaction, one schema-copy and one index pass PER LINK, which dominates
     * startup on a model with hundreds of types. Assembling inside this method collapses all of
     * it into the single {@code onPrepared} pass, which sees the complete model - association
     * ends included - and applies it at once.
     *
     * <p>Re-entrant-safe (the suppression is a plain flag under the monitor, so a nested call
     * simply keeps it set - only the outermost one applies) and exception-safe: a failed build
     * applies nothing, and the memoized model view is dropped either way, so a half-assembled
     * model can never be handed out as prepared-and-applied.
     */
    public void buildModel(@NotNull Runnable build) {
        synchronized (entityMetaDatas) {
            boolean outermost = !buildingModel;
            buildingModel = true;
            try {
                build.run();
            } catch (RuntimeException | Error e) {
                if (outermost) {
                    buildingModel = false;
                    // a prepare() from inside the suppressed build may have memoized a model view
                    // that was never applied - drop it, so the next prepare() applies whatever the
                    // caller ends up with instead of silently returning an unapplied model
                    reset();
                }
                throw e;
            }
            if (outermost) {
                buildingModel = false;
                // the model changed while the callbacks were suppressed: drop the memoized view so
                // that this prepare() rebuilds it and fires onPrepared over the full model
                reset();
                prepare();
            }
        }
    }

    /**
     * Collects the {@link #onAddAssociation} callbacks of every {@link #addAssociation} this thread
     * makes inside {@code body} and delivers them as a single {@link #onAddAssociations} call when
     * {@code body} returns - so a persistent-store implementation that maps them onto database DDL
     * can apply the whole delta in ONE schema transaction instead of one per association.
     *
     * <p>This is the runtime counterpart of {@link #buildModel(Runnable)} and the two are not
     * interchangeable:
     * <ul>
     * <li>{@code buildModel} exists to assemble a model from scratch. It suppresses the callbacks
     *     and re-applies the WHOLE model in its exit {@code prepare()} pass, which is right when the
     *     model is new and wrong when it is not: on an already-prepared model that exit pass re-walks
     *     every entity type, property and index, so it pays for itself only from roughly eight or
     *     nine associations upwards.</li>
     * <li>{@code batchAssociations} touches neither the memoized model view nor the rest of the
     *     model: the associations are registered exactly as they are without a batch, only their
     *     schema application is postponed to the end of the scope and merged. There is no full-model
     *     pass, hence no break-even - a batch of two already wins.</li>
     * </ul>
     *
     * <p>Inside {@code buildModel} this method does nothing extra: the callbacks stay suppressed and
     * the model is applied by {@code buildModel}'s exit pass.
     *
     * <p><b>No lock is held</b> for the duration of {@code body} (contrast {@code buildModel}, which
     * holds the {@code entityMetaDatas} monitor), so a batch cannot block another thread's model
     * access, and {@code body} may read the database freely.
     *
     * <p>Nested calls are safe: the outermost scope owns the flush, so an inner scope's associations
     * join the outer batch.
     *
     * <p><b>Failure semantics.</b> The deltas are applied even when {@code body} throws, because the
     * associations are already in the model by then and skipping their schema would leave the model
     * and the database out of step - exactly the divergence an unbatched caller cannot produce. The
     * body's exception is the one propagated; a failure of the deferred application is attached to it
     * as a suppressed exception. Note the flip side of merging: the delta is applied as one unit, so
     * a single failing association fails the whole batch, where unbatched calls would have applied
     * the ones before it.
     */
    public void batchAssociations(@NotNull Runnable body) {
        if (associationBatch.get() != null) {
            // nested: the outermost scope collects and flushes
            body.run();
            return;
        }

        final List<AddedAssociation> batch = new ArrayList<>();
        associationBatch.set(batch);
        Throwable bodyFailure = null;
        try {
            body.run();
        } catch (RuntimeException | Error e) {
            bodyFailure = e;
        } finally {
            associationBatch.remove();
        }

        try {
            if (!batch.isEmpty()) {
                onAddAssociations(batch);
            }
        } catch (RuntimeException | Error e) {
            if (bodyFailure == null) {
                throw e;
            }
            bodyFailure.addSuppressed(e);
        }

        if (bodyFailure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (bodyFailure instanceof Error error) {
            throw error;
        }
    }

    public void setEntityMetaDatas(@NotNull Set<EntityMetaData> entityMetaDatas) {
        synchronized (this.entityMetaDatas) {
            this.entityMetaDatas.clear();
            this.entityMetaDatas.addAll(entityMetaDatas);
            for (EntityMetaData emd : entityMetaDatas) {
                ((EntityMetaDataImpl) emd).setModelMetaData(this);
            }
            reset();
        }
    }

    public void setAssociationMetaDatas(Set<AssociationMetaData> associationMetaDatas) {
        for (AssociationMetaData amd : associationMetaDatas) {
            this.associationMetaDatas.put(((AssociationMetaDataImpl) amd).getFullName(), amd);
        }
    }

    public void addEntityMetaData(@NotNull EntityMetaData emd) {
        synchronized (entityMetaDatas) {
            entityMetaDatas.add(emd);
            ((EntityMetaDataImpl) emd).setModelMetaData(this);
            reset();
        }
    }

    void reset() {
        if (LOG_RESET) {
            logger.info("ModelMetaDataImpl#reset() invoked in thread " + Thread.currentThread(), new Throwable());
        }
        synchronized (entityMetaDatas) {
            typeToEntityMetaDatas = null;
        }
    }

    @NotNull
    public Map<String, EntityMetaData> prepare() {
        Map<String, EntityMetaData> result = typeToEntityMetaDatas;
        if (result != null) {
            return result;
        }

        synchronized (entityMetaDatas) {
            result = typeToEntityMetaDatas;
            if (result != null) {
                return result;
            }
            result = new HashMap<>();

            for (final EntityMetaData emd : entityMetaDatas) {
                ((EntityMetaDataImpl) emd).reset();

                final String type = emd.getType();
                if (result.get(type) != null) {
                    throw new IllegalArgumentException("Duplicate entity [" + type + ']');
                }
                result.put(type, emd);
            }

            this.typeToEntityMetaDatas = result;

            for (EntityMetaData emd : entityMetaDatas) {
                final EntityMetaDataImpl impl = (EntityMetaDataImpl) emd;
                final Set<AssociationEndMetaData> ends = impl.getExternalAssociationEnds();
                if (ends != null) {
                    for (AssociationEndMetaData aemd : ends) {
                        final AssociationEndMetaDataImpl endImpl = (AssociationEndMetaDataImpl) aemd;
                        endImpl.resolve(this, associationMetaDatas.get(endImpl.getAssociationMetaDataName()));
                    }
                }
            }

            for (final EntityMetaData emd : entityMetaDatas) {
                Set<AssociationEndMetaData> ends = ((EntityMetaDataImpl) emd).getExternalAssociationEnds();
                final boolean wasNull = ends == null;
                String superType = emd.getSuperType();
                while (superType != null) {
                    EntityMetaData parent = result.get(superType);
                    Set<AssociationEndMetaData> parentEnds = ((EntityMetaDataImpl) parent).getExternalAssociationEnds();
                    if (parentEnds != null) {
                        if (ends == null) {
                            ends = new HashSet<>(parentEnds);
                        } else {
                            ends.addAll(parentEnds);
                        }
                    }
                    superType = parent.getSuperType();
                }
                if (wasNull && ends != null) {
                    // non-null ends are mutated in-place
                    ((EntityMetaDataImpl) emd).setAssociationEnds(ends);
                }
            }

            for (final EntityMetaData emd : entityMetaDatas) {
                // add subtype
                final String superType = emd.getSuperType();
                if (superType != null) {
                    addSubTypeToMetaData(result, emd, superType);
                }
                // add interface types
                for (String iFaceType : emd.getInterfaceTypes()) {
                    addSubTypeToMetaData(result, emd, iFaceType);
                }

                // set supertypes
                List<String> thisAndSuperTypes = new ArrayList<>();
                EntityMetaData data = emd;
                String t = data.getType();
                do {
                    thisAndSuperTypes.add(t);
                    thisAndSuperTypes.addAll(data.getInterfaceTypes());
                    data = result.get(t);
                    t = data.getSuperType();
                } while (t != null);
                ((EntityMetaDataImpl) emd).setThisAndSuperTypes(thisAndSuperTypes);
            }
            if (!buildingModel) {
                onPrepared(result.values());
            }
            return result;
        }
    }

    /*
    * Synchronized
    * */
    protected void onPrepared(@NotNull Collection<EntityMetaData> entitiesMetaData) {

    }

    private void addSubTypeToMetaData(Map<String, EntityMetaData> typeToEntityMetaDatas, EntityMetaData emd, String superType) {
        final EntityMetaData superEmd = typeToEntityMetaDatas.get(superType);
        if (superEmd == null) {
            throw new IllegalArgumentException("No entity metadata for super type [" + superType + "]");
        }
        ((EntityMetaDataImpl) superEmd).addSubType(emd.getType());
    }

    @Override
    @Nullable
    public EntityMetaData getEntityMetaData(@NotNull String entityType) {
        return prepare().get(entityType);
    }

    @Override
    @NotNull
    public Iterable<EntityMetaData> getEntitiesMetaData() {
        return prepare().values();
    }

    public boolean hasAssociation(String sourceEntityName, String targetEntityName, String sourceName) {
        return associationMetaDatas.containsKey(getUniqueAssociationName(sourceEntityName, targetEntityName, sourceName));
    }

    @Override
    public AssociationMetaData addAssociation(String sourceEntityName, String targetEntityName,
                                              AssociationType type,
                                              String sourceName, AssociationEndCardinality sourceCardinality,
                                              boolean sourceCascadeDelete, boolean sourceClearOnDelete, boolean sourceTargetCascadeDelete, boolean sourceTargetClearOnDelete,
                                              String targetName, AssociationEndCardinality targetCardinality,
                                              boolean targetCascadeDelete, boolean targetClearOnDelete, boolean targetTargetCascadeDelete, boolean targetTargetClearOnDelete
    ) {

        EntityMetaDataImpl source = (EntityMetaDataImpl) getEntityMetaData(sourceEntityName);
        if (source == null) throw new IllegalArgumentException("Can't find entity " + sourceEntityName);

        EntityMetaDataImpl target = (EntityMetaDataImpl) getEntityMetaData(targetEntityName);
        if (target == null) throw new IllegalArgumentException("Can't find entity " + targetEntityName);


        AssociationEndType sourceType = null;
        AssociationEndType targetType = null;

        AssociationMetaDataImpl amd = new AssociationMetaDataImpl();
        amd.setType(type);
        String fullName = getUniqueAssociationName(sourceEntityName, targetEntityName, sourceName);
        amd.setFullName(fullName);
        associationMetaDatas.put(fullName, amd);

        switch (type) {
            case Directed:
                sourceType = AssociationEndType.DirectedAssociationEnd;
                break;

            case Undirected:
                sourceType = AssociationEndType.UndirectedAssociationEnd;
                targetType = AssociationEndType.UndirectedAssociationEnd;
                break;

            case Aggregation:
                sourceType = AssociationEndType.ParentEnd;
                targetType = AssociationEndType.ChildEnd;
                break;
        }

        AssociationEndMetaDataImpl sourceEnd = new AssociationEndMetaDataImpl(
            amd, sourceName, target, sourceCardinality, sourceType,
            sourceCascadeDelete, sourceClearOnDelete, sourceTargetCascadeDelete, sourceTargetClearOnDelete);
        addAssociationEndMetaDataToEntityTypeSubtree(prepare(), source, sourceEnd);
        dispatchAddAssociation(source, sourceEnd);

        if (type != AssociationType.Directed) {
            AssociationEndMetaDataImpl targetEnd = new AssociationEndMetaDataImpl(
                amd, targetName, source, targetCardinality, targetType,
                targetCascadeDelete, targetClearOnDelete, targetTargetCascadeDelete, targetTargetClearOnDelete);
            addAssociationEndMetaDataToEntityTypeSubtree(prepare(), target, targetEnd);
            dispatchAddAssociation(target, targetEnd);
        }

        return amd;
    }

    /**
     * Routes one added association end to its schema application: suppressed altogether inside
     * {@link #buildModel(Runnable)} (whose exit pass applies the whole model), deferred to the end of
     * an open {@link #batchAssociations(Runnable)} scope, or applied immediately.
     */
    private void dispatchAddAssociation(@NotNull EntityMetaData entityMetaData,
                                       @NotNull AssociationEndMetaData association) {
        if (buildingModel) {
            return;
        }
        final List<AddedAssociation> batch = associationBatch.get();
        if (batch != null) {
            batch.add(new AddedAssociation(entityMetaData, association));
        } else {
            onAddAssociation(entityMetaData, association);
        }
    }

    protected void onAddAssociation(@NotNull EntityMetaData entityMetaData, @NotNull AssociationEndMetaData association) {

    }

    /**
     * Applies a whole {@link #batchAssociations(Runnable)} delta at once. Never called with an empty
     * list. The default implementation just replays the individual {@link #onAddAssociation}
     * callbacks, so an implementation that gains nothing from batching needs no change; an
     * implementation that maps the callback onto database DDL should override this to apply the
     * delta in a single schema transaction.
     */
    protected void onAddAssociations(@NotNull List<AddedAssociation> associations) {
        for (final AddedAssociation added : associations) {
            onAddAssociation(added.entityMetaData(), added.association());
        }
    }

    private void addAssociationEndMetaDataToEntityTypeSubtree(Map<String, EntityMetaData> typeToEntityMetaDatas,
                                                              EntityMetaDataImpl emdi, AssociationEndMetaData aemd) {
        emdi.addAssociationEndMetaData(aemd);
        for (String subType : emdi.getAllSubTypes()) {
            ((EntityMetaDataImpl) typeToEntityMetaDatas.get(subType)).addAssociationEndMetaData(aemd);
        }
    }

    @Override
    public AssociationMetaData removeAssociation(String entityName, String associationName) {
        final Map<String, EntityMetaData> typeToEntityMetaDatas = prepare();

        // remove from source
        EntityMetaDataImpl source = (EntityMetaDataImpl) getEntityMetaData(entityName);
        AssociationEndMetaData aemd = removeAssociationEndMetaDataFromEntityTypeSubtree(typeToEntityMetaDatas, source, associationName);
        AssociationMetaData amd = aemd.getAssociationMetaData();
        EntityMetaDataImpl target = (EntityMetaDataImpl) aemd.getOppositeEntityMetaData();
        onRemoveAssociation(source.getType(), target.getType(), associationName);

        // remove from target
        if (amd.getType() != AssociationType.Directed) {
            String oppositeAssociationName = amd.getOppositeEnd(aemd).getName();
            removeAssociationEndMetaDataFromEntityTypeSubtree(typeToEntityMetaDatas, target, oppositeAssociationName);
            onRemoveAssociation(target.getType(), source.getType(), oppositeAssociationName);
        }

        associationMetaDatas.remove(getUniqueAssociationName(entityName, target.getType(), associationName));
        return amd;
    }

    protected void onRemoveAssociation(@NotNull String sourceTypeName, @NotNull String targetTypeName, @NotNull String associationName) {

    }

    private AssociationEndMetaData removeAssociationEndMetaDataFromEntityTypeSubtree(
        Map<String, EntityMetaData> typeToEntityMetaDatas, EntityMetaDataImpl emdi, String associationName) {
        AssociationEndMetaData removedEndMetaData = emdi.removeAssociationEndMetaData(associationName);
        for (String subType : emdi.getAllSubTypes()) {
            ((EntityMetaDataImpl) typeToEntityMetaDatas.get(subType)).removeAssociationEndMetaData(associationName);
        }
        return removedEndMetaData;
    }

    private static String getUniqueAssociationName(String sourceEntityName, String targetEntityName, String sourceName) {
        return sourceEntityName + '.' + sourceName + '-' + targetEntityName;
    }

}
