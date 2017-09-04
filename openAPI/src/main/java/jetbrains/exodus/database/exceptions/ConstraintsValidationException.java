package jetbrains.exodus.database.exceptions;

import jetbrains.exodus.core.dataStructures.hash.HashSet;
import jetbrains.exodus.database.TransientEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Set;

public class ConstraintsValidationException extends DataIntegrityViolationException {

    private Set<DataIntegrityViolationException> causes;

    public ConstraintsValidationException(Set<DataIntegrityViolationException> causes) {
        super("Constrains validation exception. Causes: \n" + ConstraintsValidationException.getCausesMessages(causes));
        this.causes = causes;
    }

    public ConstraintsValidationException(DataIntegrityViolationException cause) {
        this(new HashSet<DataIntegrityViolationException>(Arrays.asList(cause)));
    }

    private static String getCausesMessages(Set<DataIntegrityViolationException> causes) {
        final StringBuilder sb = new StringBuilder();

        int i = 1;
        for (DataIntegrityViolationException e : causes) {
            sb.append("  ").append(i++).append(": ").append(e.getMessage()).append("\n");
        }

        return sb.toString();
    }

    public Iterable<DataIntegrityViolationException> getCauses() {
        return causes;
    }

    public boolean relatesTo(@NotNull TransientEntity entity, @Nullable Object fieldIdent) {
        for (DataIntegrityViolationException e : getCauses()) {
            if (e.relatesTo(entity, fieldIdent)) {
                return true;
            }
        }

        return false;
    }

    public EntityFieldHandler getEntityFieldHandler() {
        return null;
    }

}
