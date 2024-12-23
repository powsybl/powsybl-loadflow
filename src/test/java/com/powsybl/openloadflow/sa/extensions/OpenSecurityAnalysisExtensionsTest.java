/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.powsybl.commons.json.JsonUtil;
import com.powsybl.commons.test.AbstractSerDeTest;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.json.ContingencyJsonModule;
import com.powsybl.loadflow.LoadFlowParameters;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Valentin Mouradian {@literal <valentin.mouradian at artelys.com>}
 */
class OpenSecurityAnalysisExtensionsTest extends AbstractSerDeTest {

    @Test
    void testContingencyLoadFlowParametersExtension() {
        Contingency contingency = new Contingency("L2", new BranchContingency("L2"));
        contingency.addExtension(ContingencyLoadFlowParameters.class, new ContingencyLoadFlowParameters(false, true, LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD));

        ContingencyLoadFlowParameters contingencyLoadFlowParameters = contingency.getExtension(ContingencyLoadFlowParameters.class);

        assertEquals(contingencyLoadFlowParameters, contingency.getExtensionByName("contingency-load-flow-parameters"));
        assertFalse(contingencyLoadFlowParameters.isDistributedSlack());
        assertTrue(contingencyLoadFlowParameters.isAreaInterchangeControl());
        assertEquals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD, contingencyLoadFlowParameters.getBalanceType());
    }

    @Test
    void testContingencyLoadFlowParametersExtensionJson() throws IOException {
        Contingency contingency = new Contingency("L2", new BranchContingency("L2"));
        contingency.addExtension(ContingencyLoadFlowParameters.class, new ContingencyLoadFlowParameters(false, true, LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD));
        assertEquals(ContingencyLoadFlowParameters.class, new ContingencyLoadFlowParametersJsonSerializer().getExtensionClass());
        roundTripTest(contingency, OpenSecurityAnalysisExtensionsTest::writeContingency, OpenSecurityAnalysisExtensionsTest::readContingency, "/contingencies.json");
    }

    public static Contingency readContingency(Path jsonFile) {
        Objects.requireNonNull(jsonFile);

        try (InputStream is = Files.newInputStream(jsonFile)) {
            ObjectMapper objectMapper = JsonUtil.createObjectMapper();
            ContingencyJsonModule module = new ContingencyJsonModule();
            objectMapper.registerModule(module);

            return objectMapper.readValue(is, Contingency.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void writeContingency(Contingency contingency, Path jsonFile) {
        Objects.requireNonNull(contingency);
        Objects.requireNonNull(jsonFile);

        try (OutputStream os = Files.newOutputStream(jsonFile)) {
            ObjectMapper mapper = JsonUtil.createObjectMapper();
            ContingencyJsonModule module = new ContingencyJsonModule();
            mapper.registerModule(module);

            ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
            writer.writeValue(os, contingency);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
