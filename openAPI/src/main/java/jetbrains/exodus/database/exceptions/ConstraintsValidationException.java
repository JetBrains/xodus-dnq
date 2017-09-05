/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
