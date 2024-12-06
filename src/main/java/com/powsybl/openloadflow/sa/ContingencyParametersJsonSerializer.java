package com.powsybl.openloadflow.sa;

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

@AutoService(ExtensionJsonSerializer.class)
public class ContingencyParametersJsonSerializer implements ExtensionJsonSerializer<Contingency, ContingencyParameters> {

    @Override
    public String getExtensionName() {
        return "contingency-parameters";
    }

    @Override
    public String getCategoryName() {
        return "contingency";
    }

    @Override
    public Class<? super ContingencyParameters> getExtensionClass() {
        return ContingencyParameters.class;
    }

    /**
     * Specifies serialization for our extension: ignore name et extendable
     */
    private interface SerializationSpec {

        @JsonIgnore
        String getName();

        @JsonIgnore
        Contingency getExtendable();
    }

    private static ObjectMapper createMapper() {
        return JsonUtil.createObjectMapper()
                .addMixIn(ContingencyParameters.class, SerializationSpec.class);
    }

    @Override
    public void serialize(ContingencyParameters extension, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
        createMapper().writeValue(jsonGenerator, extension);
    }

    @Override
    public ContingencyParameters deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
        return createMapper().readValue(jsonParser, ContingencyParameters.class);
    }
}
