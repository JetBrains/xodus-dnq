package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.decorators.HashMapDecorator;
import com.jetbrains.teamsys.core.dataStructures.decorators.HashSetDecorator;
import com.jetbrains.teamsys.core.dataStructures.hash.HashMap;
import com.jetbrains.teamsys.core.dataStructures.hash.HashSet;
import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class EntityMetaDataImpl implements EntityMetaData {

    // original fields

    private ModelMetaData modelMetaData = null;
    private String type = null;
    private String superType = null;
    private Set<String> interfaces = new HashSetDecorator<String>();
    private Runnable initializer = null;
    private boolean removeOrphan = true;
    private Set<String> subTypes = new HashSetDecorator<String>();
    private List<String> thisAndSuperTypes = Collections.emptyList();
    private Set<AssociationEndMetaData> externalAssociationEnds = null;
    private Map<String, PropertyMetaData> properties = new HashMapDecorator<String, PropertyMetaData>();
    private Set<Index> ownIndexes = Collections.emptySet();
    private Set<String> requiredProperties = Collections.emptySet();
    private Set<String> requiredIfProperties = Collections.emptySet();
    private Set<String> historyIgnoredFields = Collections.emptySet();
    private Set<String> versionMismatchIgnored = Collections.emptySet();
    private boolean versionMismatchIgnoredForWholeClass = false;

    // calculated
    private Map<String, Set<Index>> fieldToIndexes = null;
    private Set<Index> indexes = null;
    private Set<String> aggregationChildEnds = null;
    private List<String> allSubTypes = null;
    private Map<String, Set<String>> incomingAssociations = null;
    private Map<String, AssociationEndMetaData> associationEnds = null;

    void reset() {
        synchronized (this) {
            allSubTypes = null;
            associationEnds = null;
            incomingAssociations = null;
            indexes = null;
            fieldToIndexes = null;
            aggregationChildEnds = null;
        }
    }

    void resetSelfAndSubtypes() {
        reset();

        for (String st: getSubTypes()) {
            ((EntityMetaDataImpl)getEntityMetaData(st)).reset();
        }
    }

    Set<AssociationEndMetaData> getExternalAssociationEnds() {
        return externalAssociationEnds;
    }

    public ModelMetaData getModelMetaData() {
        return modelMetaData;
    }

    public void setModelMetaData(ModelMetaData modelMetaData) {
        for (Index index : this.ownIndexes) {
            index.setModelMetaData(modelMetaData);
        }

        this.modelMetaData = modelMetaData;
    }

    public void setType(String type) {
        if (type != null) {
            type = type.intern();
        }
        this.type = type;
    }

    public void setSuperType(String superType) {
        if (superType != null) {
            superType = superType.intern();
        }
        this.superType = superType;

        resetSelfAndSubtypes();
    }

    public Iterable<String> getThisAndSuperTypes() {
        return thisAndSuperTypes;
    }

    // called by ModelMetadata.update after reset, so do not make reset itself
    void setThisAndSuperTypes(List<String> thisAndSuperTypes) {
        this.thisAndSuperTypes = thisAndSuperTypes;
    }

    public boolean hasSubTypes() {
        return !subTypes.isEmpty();
    }

    public Collection<String> getSubTypes() {
        return subTypes;
    }

    public Collection<String> getAllSubTypes() {
        if (!hasSubTypes()) return Collections.emptyList();

        updateAllSubTypes();

        return allSubTypes;
    }

    private void updateAllSubTypes() {
        if (allSubTypes == null) {
            synchronized (this) {
                if (allSubTypes == null) {
                    List<String> _allSubTypes = new ArrayList<String>();
                    collectSubTypes(this, _allSubTypes);
                    allSubTypes = _allSubTypes;
                }
            }
        }
    }

    private void collectSubTypes(EntityMetaDataImpl emd, List<String> result) {
        final Set<String> subTypes = emd.subTypes;
        result.addAll(subTypes);
        for (final String subType : subTypes) {
            collectSubTypes((EntityMetaDataImpl) modelMetaData.getEntityMetaData(subType), result);
        }
    }

    // called by ModelMetadata.update after reset, so do not make reset itself
    void addSubType(@NotNull String type) {
        subTypes.add(type);
    }

    public void setInitializer(Runnable initializer) {
        this.initializer = initializer;
    }

    public Runnable getInitializer() {
        return initializer;
    }

    public void setHistoryIgnoredFields(Set<String> historyIgnoredFields) {
        this.historyIgnoredFields = historyIgnoredFields;
    }

    public boolean changesReflectHistory(TransientEntity e, TransientChangesTracker tracker) {
        Map<String, PropertyChange> changedProperties = tracker.getChangedPropertiesDetailed(e);
        if (changedProperties != null) {
            for (String field : changedProperties.keySet()) {
                if (!historyIgnoredFields.contains(field)) {
                    return true;
                }
            }
        }

        Map<String, LinkChange> changedLinks = tracker.getChangedLinksDetailed(e);
        if (changedLinks != null) {
            for (String field : changedLinks.keySet()) {
                if (!historyIgnoredFields.contains(field)) {
                    return true;
                }
            }
        }

        return false;
    }

    public void setRemoveOrphan(boolean removeOrphan) {
        this.removeOrphan = removeOrphan;
    }

    public void setAssociationEndsMetaData(@NotNull Collection<AssociationEndMetaData> ends) {
        externalAssociationEnds = new HashSet<AssociationEndMetaData>();
        externalAssociationEnds.addAll(ends);
    }

    /**
     * For backward compatibility
     * @param ends
     */
    public void setAssociationEnds(@NotNull Collection<AssociationEndMetaData> ends) {
        externalAssociationEnds = new HashSet<AssociationEndMetaData>();
        externalAssociationEnds.addAll(ends);
    }

    public Collection<String> getInterfaceTypes() {
        return interfaces;
    }

    public void setInterfaces(List<String> interfaces) {
        this.interfaces.addAll(interfaces);
    }

    void addAssociationEndMetaData(AssociationEndMetaData end) {
        synchronized (this) {
            if (externalAssociationEnds == null) {
                externalAssociationEnds = new HashSet<AssociationEndMetaData>();
            }
            AssociationEndMetaData a = findAssociationEndMetaData(end.getName());

            if (a != null) {
                throw new IllegalArgumentException("Association already exists [" + end.getName() + "]");
            }

            resetSelfAndSubtypes();
            externalAssociationEnds.add(end);
        }
    }

    AssociationEndMetaData removeAssociationEndMetaData(String name) {
        synchronized (this) {
            AssociationEndMetaData a = findAssociationEndMetaData(name);

            if (a == null) {
                throw new IllegalArgumentException("Can't find association end with name [" + name + "]");
            }

            resetSelfAndSubtypes();
            externalAssociationEnds.remove(a);

            return a;
        }
    }

    private AssociationEndMetaData findAssociationEndMetaData(String name) {
        if (externalAssociationEnds != null) {
            for (AssociationEndMetaData a: externalAssociationEnds) {
                if (a.getName().equals(name)) {
                    return a;
                }
            }
        }
        return null;
    }

    @NotNull
    public String getType() {
        return type;
    }

    @Nullable
    public String getSuperType() {
        return superType;
    }

    public AssociationEndMetaData getAssociationEndMetaData(@NotNull String name) {
        updateAssociationEnds();
        return associationEnds.get(name);
    }

    @NotNull
    public Collection<AssociationEndMetaData> getAssociationEndsMetaData() {
        updateAssociationEnds();
        return associationEnds.values();
    }

    public PropertyMetaData getPropertyMetaData(String name) {
        return properties.get(name);
    }

    @NotNull
    public Iterable<PropertyMetaData> getPropertiesMetaData() {
        return properties.values();
    }

    public void setPropertiesMetaData(Iterable<PropertyMetaData> properties) {
        if (properties == null) return;
        for (PropertyMetaData p: properties) {
            this.properties.put(p.getName(), p);
        }
    }

    public boolean getRemoveOrphan() {
        return removeOrphan;
    }

    public boolean hasAggregationChildEnds() {
        updateAssociationEnds();
        return !aggregationChildEnds.isEmpty();
    }

    public Set<String> getAggregationChildEnds() {
        updateAssociationEnds();
        return aggregationChildEnds;
    }

    @NotNull
    public Map<String, Set<String>> getIncomingAssociations(final ModelMetaData mmd) {
        updateIncommingAssociations(mmd);
        return incomingAssociations;
    }

    private void updateIncommingAssociations(ModelMetaData mmd) {
        if (incomingAssociations == null) {
            synchronized (this) {
                if (incomingAssociations == null) {
                    incomingAssociations = new HashMapDecorator<String, Set<String>>();
                    for (final EntityMetaData emd : mmd.getEntitiesMetaData()) {
                        for (final AssociationEndMetaData aemd : emd.getAssociationEndsMetaData()) {
                            if (type.equals(aemd.getOppositeEntityMetaData().getType())) {
                                collectLink(emd, aemd);
                            } else {
                                // if there are references to super type
                                Collection<String> associationEndSubtypes = aemd.getOppositeEntityMetaData().getAllSubTypes();
                                if (associationEndSubtypes.contains(type)) {
                                    collectLink(emd, aemd);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void collectLink(EntityMetaData emd, AssociationEndMetaData aemd) {
        final String associationName = aemd.getName();
        addIncomingAssociation(emd.getType(), associationName);
        //seems like we'll add them after in any case
//        for (final String subtype : emd.getSubTypes()) {
//            addIncomingAssociation(subtype, associationName);
//        }
    }

    private void addIncomingAssociation(@NotNull final String type, @NotNull final String associationName) {
        Set<String> links = incomingAssociations.get(type);
        if (links == null) {
            links = new HashSet<String>();
            incomingAssociations.put(type, links);
        }
        links.add(associationName);
    }

    @NotNull
    public Set<Index> getOwnIndexes() {
        return ownIndexes;
    }

    @NotNull
    public Set<Index> getIndexes() {
        updateIndexes();

        return indexes;
    }

    @NotNull
    public Set<Index> getIndexes(String field) {
        updateIndexes();

        Set<Index> res = fieldToIndexes.get(field);
        return res == null ? Collections.<Index>emptySet() : res;
    }

    private void updateIndexes() {
        if (indexes == null) {

            synchronized (this) {
              if (indexes == null) {
                indexes = new HashSet<Index>();

                // add indexes of super types
                for (String t : getThisAndSuperTypes()) {
                    for (Index index : getEntityMetaData(t).getOwnIndexes()) {
                        for (IndexField f : index.getFields()) {
                            for (String st : getEntityMetaData(f.getOwnerEnityType()).getThisAndSuperTypes()) {
                                indexes.addAll(getEntityMetaData(st).getOwnIndexes());
                            }
                        }
                    }
                }
              }
            }
        }

        if (fieldToIndexes == null) {
            synchronized (this) {
                if (fieldToIndexes == null) {
                    fieldToIndexes = new HashMap<String, Set<Index>>();
                    // build prop to ownIndexes map
                    for (Index index : getIndexes()) {
                        for (IndexField f : index.getFields()) {
                            Set<Index> fieldIndexes = fieldToIndexes.get(f.getName());
                            if (fieldIndexes == null) {
                                fieldIndexes = new HashSet<Index>();
                                fieldToIndexes.put(f.getName(), fieldIndexes);
                            }
                            fieldIndexes.add(index);
                        }
                    }
                }
            }
        }
    }

    private EntityMetaData getEntityMetaData(String type) {
        return modelMetaData.getEntityMetaData(type);
    }

    @NotNull
    public void setOwnIndexes(Set<Index> ownIndexes) {
        this.ownIndexes = ownIndexes;
    }

    @NotNull
    public Set<String> getRequiredProperties() {
        return requiredProperties;
    }

    @NotNull
    public Set<String> getRequiredIfProperties(Entity e) {
        Set<String> result = new HashSetDecorator<String>();
        for (String property : requiredIfProperties) {
            if (getInstance(e).isPropertyRequired(property, e)) {
                result.add(property);
            }
        }
        return result;
    }

    public boolean isVersionMismatchIgnoredForWholeClass() {
        return versionMismatchIgnoredForWholeClass;
    }

    public void setVersionMismatchIgnoredForWholeClass(boolean versionMismatchIgnoredForWholeClass) {
        this.versionMismatchIgnoredForWholeClass = versionMismatchIgnoredForWholeClass;
    }

    public void setRequiredProperties(@NotNull Set<String> requiredProperties) {
        this.requiredProperties = requiredProperties;
    }

    public void setRequiredIfProperties(@NotNull Set<String> requiredIfProperties) {
        this.requiredIfProperties = requiredIfProperties;
    }

    public boolean isVersionMismatchIgnored(@NotNull String propertyName) {
        return versionMismatchIgnored.contains(propertyName);
    }

    public void setVersionMismatchIgnored(@NotNull Set<String> versionMismatchIgnored) {
        this.versionMismatchIgnored = versionMismatchIgnored;
    }

    public boolean hasParent(@NotNull TransientEntity e, @NotNull TransientChangesTracker tracker) {
        if (e.isNewOrTemporary() || parentChanged(tracker.getChangedLinksDetailed(e))) {
            updateAssociationEnds();
            for (String childEnd : aggregationChildEnds) {
                if (AssociationSemantics.getToOne(e, childEnd) != null) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    public BasePersistentClass getInstance(Entity entity) {
        return EntityInstanceRegistry.getEntityInstance(entity, type);
    }

    private boolean parentChanged(Map<String, LinkChange> changedLinks) {
        if (changedLinks == null) {
            return false;
        }
        updateAssociationEnds();
        for (String childEnd : aggregationChildEnds) {
            if (changedLinks.containsKey(childEnd)) {
                return true;
            }
        }
        return false;
    }

    private void updateAssociationEnds() {
        if (associationEnds == null) {
            synchronized (this) {
                if (associationEnds == null) {
                    if (externalAssociationEnds == null) {
                        associationEnds = Collections.emptyMap();
                        aggregationChildEnds = Collections.emptySet();
                    } else {
                        associationEnds = new HashMap<String, AssociationEndMetaData>(externalAssociationEnds.size());
                        aggregationChildEnds = new HashSetDecorator<String>();
                        for (final AssociationEndMetaData aemd : externalAssociationEnds) {
                            associationEnds.put(aemd.getName(), aemd);
                            if (AssociationEndType.ChildEnd.equals(aemd.getAssociationEndType())) {
                                aggregationChildEnds.add(aemd.getName());
                            }
                        }
                    }
                }
            }
        }
    }

    @Deprecated
    public void setUniqueProperties(@NotNull Set<String> uniqueProperties) {
        //throw new UnsupportedOperationException("Regenerate your persistent models.");
    }

    @Override
    public String toString() {
        return getType();
    }
}
