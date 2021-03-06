/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.analyze.relations;

import io.crate.analyze.symbol.Field;
import io.crate.exceptions.AmbiguousColumnException;
import io.crate.exceptions.ColumnUnknownException;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.table.Operation;
import io.crate.sql.tree.QualifiedName;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves QualifiedNames to Fields considering multiple AnalyzedRelations.
 *
 * The Resolver also takes full qualified names so the name may contain table
 * and / or schema.
 */
public class FullQualifedNameFieldProvider implements FieldProvider<Field> {

    private Map<QualifiedName, AnalyzedRelation> sources;

    public FullQualifedNameFieldProvider(Map<QualifiedName, AnalyzedRelation> sources) {
        assert !sources.isEmpty() : "Must have at least one source";
        this.sources = sources;
    }

    public Field resolveField(QualifiedName qualifiedName, Operation operation) {
        return resolveField(qualifiedName, null, operation);
    }

    public Field resolveField(QualifiedName qualifiedName, @Nullable List<String> path, Operation operation) {
        List<String> parts = qualifiedName.getParts();
        String columnSchema = null;
        String columnTableName = null;
        ColumnIdent columnIdent = new ColumnIdent(parts.get(parts.size() - 1), path);
        switch (parts.size()) {
            case 1:
                break;
            case 2:
                columnTableName = parts.get(0).toLowerCase(Locale.ENGLISH);
                break;
            case 3:
                columnSchema = parts.get(0).toLowerCase(Locale.ENGLISH);
                columnTableName = parts.get(1).toLowerCase(Locale.ENGLISH);
                break;
            default:
                throw new IllegalArgumentException("Column reference \"%s\" has too many parts. " +
                        "A column reference can have at most 3 parts and must have one of the following formats:  " +
                        "\"<column>\", \"<table>.<column>\" or \"<schema>.<table>.<column>\"");
        }

        boolean schemaMatched = false;
        boolean tableNameMatched = false;
        Field lastField = null;

        for (Map.Entry<QualifiedName, AnalyzedRelation> entry : sources.entrySet()) {
            List<String> sourceParts = entry.getKey().getParts();
            String sourceSchema = null;
            String sourceTableOrAlias;

            if (sourceParts.size() == 1) {
                sourceTableOrAlias = sourceParts.get(0);
            } else if (sourceParts.size() == 2) {
                sourceSchema = sourceParts.get(0);
                sourceTableOrAlias = sourceParts.get(1);
            } else {
                throw new UnsupportedOperationException(String.format(Locale.ENGLISH,
                        "sources key (QualifiedName) must have 1 or 2 parts, not %d", sourceParts.size()));
            }
            AnalyzedRelation sourceRelation = entry.getValue();

            if (columnSchema != null && sourceSchema != null && !columnSchema.equals(sourceSchema)) {
                continue;
            }
            schemaMatched = true;
            if (columnTableName != null && !sourceTableOrAlias.equals(columnTableName)) {
                continue;
            }
            tableNameMatched = true;

            Field newField;
            newField = sourceRelation.getField(columnIdent, operation);
            if (newField != null) {
                if (lastField != null) {
                    throw new AmbiguousColumnException(columnIdent);
                }
                lastField = newField;
            }
        }
        if (lastField == null) {
            if (!schemaMatched) {
                throw new IllegalArgumentException(String.format(Locale.ENGLISH,
                        "Cannot resolve relation '%s.%s'", columnSchema, columnTableName));
            }
            if (!tableNameMatched) {
                throw new IllegalArgumentException(String.format(Locale.ENGLISH, "Cannot resolve relation '%s'", columnTableName));
            }
            throw new ColumnUnknownException(columnIdent.sqlFqn());
        }
        return lastField;
    }
}
