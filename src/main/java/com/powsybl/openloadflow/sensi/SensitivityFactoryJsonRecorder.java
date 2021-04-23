/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.commons.json.JsonUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class SensitivityFactoryJsonRecorder implements SensitivityFactorReader {

    private final SensitivityFactorReader delegate;

    private final Path jsonFile;

    public SensitivityFactoryJsonRecorder(SensitivityFactorReader delegate, Path jsonFile) {
        this.delegate = Objects.requireNonNull(delegate);
        this.jsonFile = Objects.requireNonNull(jsonFile);
    }

    @Override
    public void read(Handler handler) {
        Objects.requireNonNull(handler);
        JsonUtil.writeJson(jsonFile, jsonGenerator -> {
            try {
                jsonGenerator.writeStartArray();

                delegate.read(new Handler() {
                    @Override
                    public void onSimpleFactor(Object factorContext, SensitivityFunctionType functionType, String functionId, SensitivityVariableType variableType, String variableId, ContingencyContext contingencyContext) {
                        SimpleSensitivityFactor.writeJson(jsonGenerator, functionType, functionId, variableType, variableId, contingencyContext);
                        handler.onSimpleFactor(factorContext, functionType, functionId, variableType, variableId, contingencyContext);
                    }

                    @Override
                    public void onMultipleVariablesFactor(Object factorContext, SensitivityFunctionType functionType, String functionId, SensitivityVariableType variableType, String variableId, List<WeightedSensitivityVariable> variables, ContingencyContext contingencyContext) {
                        MultipleVariablesSensitivityFactor.writeJson(jsonGenerator, functionType, functionId, variableType, variableId, variables, contingencyContext);
                        handler.onMultipleVariablesFactor(factorContext, functionType, functionId, variableType, variableId, variables, contingencyContext);
                    }
                });

                jsonGenerator.writeEndArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
