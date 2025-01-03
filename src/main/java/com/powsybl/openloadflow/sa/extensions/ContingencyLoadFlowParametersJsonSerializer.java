/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa.extensions;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.google.auto.service.AutoService;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.extensions.ExtensionJsonSerializer;
import com.powsybl.contingency.Contingency;
import com.powsybl.loadflow.LoadFlowParameters;

import java.io.IOException;
import java.util.Optional;

/**
 * @author Valentin Mouradian {@literal <valentin.mouradian at artelys.com>}
 */
@AutoService(ExtensionJsonSerializer.class)
public class ContingencyLoadFlowParametersJsonSerializer implements ExtensionJsonSerializer<Contingency, ContingencyLoadFlowParameters> {

    @Override
    public String getExtensionName() {
        return "contingency-load-flow-parameters";
    }

    @Override
    public String getCategoryName() {
        return "security-analysis";
    }

    @Override
    public Class<? super ContingencyLoadFlowParameters> getExtensionClass() {
        return ContingencyLoadFlowParameters.class;
    }

    @Override
    public void serialize(ContingencyLoadFlowParameters extension, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
        Optional<Boolean> distributedSlack = extension.isDistributedSlack();
        Optional<Boolean> areaInterchangeControl = extension.isAreaInterchangeControl();
        Optional<LoadFlowParameters.BalanceType> balanceType = extension.getBalanceType();

        jsonGenerator.writeStartObject();
        if (distributedSlack.isPresent()) {
            jsonGenerator.writeBooleanField("distributedSlack", distributedSlack.get());
        }
        if (areaInterchangeControl.isPresent()) {
            jsonGenerator.writeBooleanField("areaInterchangeControl", areaInterchangeControl.get());
        }
        if (balanceType.isPresent()) {
            jsonGenerator.writeStringField("balanceType", balanceType.get().name());
        }
        jsonGenerator.writeEndObject();
    }

    @Override
    public ContingencyLoadFlowParameters deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
        ContingencyLoadFlowParameters contingencyLoadFlowParameters = new ContingencyLoadFlowParameters();
        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
            if (jsonParser.currentName().equals("distributedSlack")) {
                jsonParser.nextToken();
                boolean distributedSlack = jsonParser.readValueAs(Boolean.class);
                contingencyLoadFlowParameters.setDistributedSlack(distributedSlack);
            } else if (jsonParser.currentName().equals("areaInterchangeControl")) {
                jsonParser.nextToken();
                boolean areaInterchangeControl = jsonParser.readValueAs(Boolean.class);
                contingencyLoadFlowParameters.setAreaInterchangeControl(areaInterchangeControl);
            } else if (jsonParser.currentName().equals("balanceType")) {
                jsonParser.nextToken();
                LoadFlowParameters.BalanceType balanceType = LoadFlowParameters.BalanceType.valueOf(jsonParser.readValueAs(String.class));
                contingencyLoadFlowParameters.setBalanceType(balanceType);
            } else {
                throw new PowsyblException("Unexpected field: " + jsonParser.currentName());
            }
        }
        return contingencyLoadFlowParameters;
    }
}
