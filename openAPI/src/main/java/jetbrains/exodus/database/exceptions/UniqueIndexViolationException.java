package jetbrains.exodus.database.exceptions;

import jetbrains.exodus.query.metadata.Index;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.query.metadata.IndexField;

import java.util.List;

public class UniqueIndexViolationException extends SimplePropertyValidationException {

    public UniqueIndexViolationException(TransientEntity entity, Index index) {
        super("Index [" + index + "]" + " must be unique. Conflicting value: [" + formatConflict(entity, index) + "]",
                "Value should be unique", entity, index.getFields().get(0).getName());
    }

    private static String formatConflict(TransientEntity entity, Index index){
        StringBuilder conflict = new StringBuilder();
        List<IndexField> fields = index.getFields();
        for(IndexField field: fields){
            if(field.isProperty()) {
                conflict.append(entity.getProperty(field.getName()));
            } else {
                conflict.append(entity.getLink(field.getName()));
            }
            conflict.append(", ");
        }
        return conflict.length() > 0 ? conflict.substring(0, conflict.length() - 2) : "No accessible value";
    }
}