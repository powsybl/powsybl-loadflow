/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa.extensions;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.google.auto.service.AutoService;
import com.powsybl.commons.extensions.ExtensionJsonSerializer;
import com.powsybl.commons.json.JsonUtil;
import com.powsybl.contingency.Contingency;

import java.io.IOException;

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
        return "contingency";
    }

    @Override
    public Class<? super ContingencyLoadFlowParameters> getExtensionClass() {
        return ContingencyLoadFlowParameters.class;
    }

    /**
     * Specifies serialization for our extension: ignore name and extendable
     */
    private interface SerializationSpec {

        @JsonIgnore
        String getName();

        @JsonIgnore
        Contingency getExtendable();
    }

    private static ObjectMapper createMapper() {
        return JsonUtil.createObjectMapper()
                .addMixIn(ContingencyLoadFlowParameters.class, SerializationSpec.class);
    }

    @Override
    public void serialize(ContingencyLoadFlowParameters extension, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
        createMapper().writeValue(jsonGenerator, extension);
    }

    @Override
    public ContingencyLoadFlowParameters deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
        return createMapper().readValue(jsonParser, ContingencyLoadFlowParameters.class);
    }
}
