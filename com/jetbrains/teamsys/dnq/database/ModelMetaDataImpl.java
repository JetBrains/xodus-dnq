package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.hash.HashMap;
import com.jetbrains.teamsys.database.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class ModelMetaDataImpl implements ModelMetaData {

    private Set<EntityMetaData> entityMetaDatas = new HashSet<EntityMetaData>();
    private Map<String, AssociationMetaData> associationMetaDatas = new HashMap<String, AssociationMetaData>();
    private Map<String, EntityMetaData> typeToEntityMetaDatas = null;

    public void init() {
        reset();
        update();
    }

    public void setEntityMetaDatas(@NotNull Set<EntityMetaData> entityMetaDatas) {
        this.entityMetaDatas = entityMetaDatas;
        for (EntityMetaData emd : entityMetaDatas) {
            emd.setModelMetaData(this);
        }
        // init();
    }

    public void setAssociationMetaDatas(Set<AssociationMetaData> associationMetaDatas) {
        for (AssociationMetaData amd : associationMetaDatas) {
            this.associationMetaDatas.put(((AssociationMetaDataImpl) amd).getFullName(), amd);
        }
    }

    public void addEntityMetaData(@NotNull EntityMetaData emd) {
        entityMetaDatas.add(emd);
        emd.setModelMetaData(this);
        reset();
    }

    private void reset() {
        typeToEntityMetaDatas = null;
    }

    private void update() {
        if (typeToEntityMetaDatas != null) {
            return;
        }

        synchronized (this) {
            if (typeToEntityMetaDatas != null) {
                return;
            }
            typeToEntityMetaDatas = new HashMap<String, EntityMetaData>();

            for (final EntityMetaData emd : entityMetaDatas) {
                ((EntityMetaDataImpl)emd).reset();

                final String type = emd.getType();
                if (typeToEntityMetaDatas.get(type) != null) {
                    throw new IllegalArgumentException("Duplicate entity [" + type + "]");
                }
                typeToEntityMetaDatas.put(type, emd);
            }

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

            for (final EntityMetaData emd: entityMetaDatas) {
                Set<AssociationEndMetaData> ends = ((EntityMetaDataImpl) emd).getExternalAssociationEnds();
                final boolean wasNull = ends == null;
                String superType = emd.getSuperType();
                while (superType != null) {
                    EntityMetaData parent = typeToEntityMetaDatas.get(superType);
                    Set<AssociationEndMetaData> parentEnds = ((EntityMetaDataImpl) parent).getExternalAssociationEnds();
                    if (parentEnds != null) {
                        if (ends == null) {
                            ends = new HashSet<AssociationEndMetaData>(parentEnds);
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
                    addSubTypeToMetaData(emd, superType);
                }
                // add interface types
                for (String iFaceType : emd.getInterfaceTypes()) {
                    addSubTypeToMetaData(emd, iFaceType);
                }

                // set supertypes
                List<String> thisAndSuperTypes = new ArrayList<String>();
                EntityMetaData data = emd;
                String t = data.getType();
                do {
                    thisAndSuperTypes.add(t);
                    thisAndSuperTypes.addAll(data.getInterfaceTypes());
                    data = typeToEntityMetaDatas.get(t);
                    t = data.getSuperType();
                } while (t != null);
                ((EntityMetaDataImpl) emd).setThisAndSuperTypes(thisAndSuperTypes);
            }
        }
    }

    private void addSubTypeToMetaData(EntityMetaData emd, String superType) {
        final EntityMetaData superEmd = typeToEntityMetaDatas.get(superType);
        if (superEmd == null) {
            throw new IllegalArgumentException("No entity metadata for super type [" + superType + "]");
        }
        ((EntityMetaDataImpl) superEmd).addSubType(emd.getType());
    }

    @Nullable
    public EntityMetaData getEntityMetaData(@NotNull String typeName) {
        update();
        return typeToEntityMetaDatas.get(typeName);
    }

    @NotNull
    public Iterable<EntityMetaData> getEntitiesMetaData() {
        update();
        return typeToEntityMetaDatas.values();
    }

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
        source.addAssociationEndMetaData(sourceEnd);

        if (type != AssociationType.Directed) {
            AssociationEndMetaDataImpl targetEnd = new AssociationEndMetaDataImpl(
                    amd, targetName, source, targetCardinality, targetType,
                    targetCascadeDelete, targetClearOnDelete, targetTargetCascadeDelete, targetTargetClearOnDelete);
            target.addAssociationEndMetaData(targetEnd);
        }

        return amd;
    }

    public AssociationMetaData removeAssociation(String entityName, String associationName) {
        EntityMetaDataImpl source = (EntityMetaDataImpl) getEntityMetaData(entityName);

        // remove from source
        AssociationEndMetaData aemd = source.removeAssociationEndMetaData(associationName);
        AssociationMetaData amd = aemd.getAssociationMetaData();

        // remove from target
        if (amd.getType() != AssociationType.Directed) {
            ((EntityMetaDataImpl) aemd.getOppositeEntityMetaData()).removeAssociationEndMetaData(amd.getOppositeEnd(aemd).getName());
        }

        return amd;
    }

}
