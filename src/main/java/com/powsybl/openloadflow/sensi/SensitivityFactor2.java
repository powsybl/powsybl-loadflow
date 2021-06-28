/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sensi;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.common.base.Stopwatch;
import com.powsybl.commons.json.JsonUtil;
import com.powsybl.contingency.ContingencyContext;
import com.powsybl.contingency.ContingencyContextType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class SensitivityFactor2 {

    private static final Logger LOGGER = LoggerFactory.getLogger(SensitivityFactor2.class);

    private final SensitivityFunctionType functionType;

    private final String functionId;

    private final SensitivityVariableType variableType;

    private final String variableId;

    private final boolean variableSet;

    private final ContingencyContext contingencyContext;

    public SensitivityFactor2(SensitivityFunctionType functionType, String functionId, SensitivityVariableType variableType,
                              String variableId, boolean variableSet, ContingencyContext contingencyContext) {
        this.functionType = Objects.requireNonNull(functionType);
        this.functionId = Objects.requireNonNull(functionId);
        this.variableType = Objects.requireNonNull(variableType);
        this.variableId = Objects.requireNonNull(variableId);
        this.variableSet = variableSet;
        this.contingencyContext = Objects.requireNonNull(contingencyContext);
    }

    public SensitivityFunctionType getFunctionType() {
        return functionType;
    }

    public String getFunctionId() {
        return functionId;
    }

    public SensitivityVariableType getVariableType() {
        return variableType;
    }

    public String getVariableId() {
        return variableId;
    }

    public boolean isVariableSet() {
        return variableSet;
    }

    public ContingencyContext getContingencyContext() {
        return contingencyContext;
    }

    @Override
    public String toString() {
        return "SensitivityFactor(" +
                "functionType=" + functionType +
                ", functionId='" + functionId + '\'' +
                ", variableType=" + variableType +
                ", variableId='" + variableId + '\'' +
                ", variableSet=" + variableSet +
                ", contingencyContext=" + contingencyContext +
                ')';
    }

    static void writeJson(JsonGenerator jsonGenerator, SensitivityFunctionType functionType, String functionId, SensitivityVariableType variableType,
                          String variableId, boolean variableSet, ContingencyContext contingencyContext) {
        try {
            jsonGenerator.writeStartObject();

            jsonGenerator.writeStringField("functionType", functionType.name());
            jsonGenerator.writeStringField("functionId", functionId);
            jsonGenerator.writeStringField("variableType", variableType.name());
            jsonGenerator.writeStringField("variableId", variableId);
            jsonGenerator.writeBooleanField("variableSet", variableSet);
            jsonGenerator.writeStringField("contingencyContextType", contingencyContext.getContextType().name());
            if (contingencyContext.getContingencyId() != null) {
                jsonGenerator.writeStringField("contingencyId", contingencyContext.getContingencyId());
            }

            jsonGenerator.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<SensitivityFactor2> parseJson(Path jsonFile) {
        return JsonUtil.parseJson(jsonFile, SensitivityFactor2::parseJson);
    }

    static final class ParsingContext {
        private SensitivityFunctionType functionType;
        private String functionId;
        private SensitivityVariableType variableType;
        private String variableId;
        private Boolean variableSet;
        private ContingencyContextType contingencyContextType;
        private String contingencyId;

        private void reset() {
            functionType = null;
            functionId = null;
            variableType = null;
            variableId = null;
            variableSet = null;
            contingencyContextType = null;
            contingencyId = null;
        }
    }

    static List<SensitivityFactor2> parseJson(JsonParser parser) {
        Objects.requireNonNull(parser);

        Stopwatch stopwatch = Stopwatch.createStarted();

        List<SensitivityFactor2> factors = new ArrayList<>();
        try {
            ParsingContext context = new ParsingContext();
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.FIELD_NAME) {
                    String fieldName = parser.getCurrentName();
                    switch (fieldName) {
                        case "functionType":
                            context.functionType = SensitivityFunctionType.valueOf(parser.nextTextValue());
                            break;
                        case "functionId":
                            context.functionId = parser.nextTextValue();
                            break;
                        case "variableType":
                            context.variableType = SensitivityVariableType.valueOf(parser.nextTextValue());
                            break;
                        case "variableId":
                            context.variableId = parser.nextTextValue();
                            break;
                        case "variableSet":
                            context.variableSet = parser.nextBooleanValue();
                            break;
                        case "contingencyContextType":
                            context.contingencyContextType = ContingencyContextType.valueOf(parser.nextTextValue());
                            break;
                        case "contingencyId":
                            context.contingencyId = parser.nextTextValue();
                            break;
                        default:
                            break;
                    }
                } else if (token == JsonToken.END_OBJECT) {
                    factors.add(new SensitivityFactor2(context.functionType, context.functionId, context.variableType, context.variableId, context.variableSet,
                            new ContingencyContext(context.contingencyId, context.contingencyContextType)));
                    context.reset();
                } else if (token == JsonToken.END_ARRAY) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        stopwatch.stop();
        LOGGER.info("{} factors read in {} ms", factors.size(), stopwatch.elapsed(TimeUnit.MILLISECONDS));

        return factors;
    }

    public static List<SensitivityFactor2> createMatrix(SensitivityFunctionType functionType, List<String> functionIds,
                                                        SensitivityVariableType variableType, List<String> variableIds,
                                                        boolean variableSet, ContingencyContext contingencyContext) {
        List<SensitivityFactor2> factors = new ArrayList<>();
        for (String functionId : functionIds) {
            for (String variableId : variableIds) {
                factors.add(new SensitivityFactor2(functionType, functionId, variableType, variableId, variableSet, contingencyContext));
            }
        }
        return factors;
    }
}
