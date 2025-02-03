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
import com.powsybl.contingency.contingency.list.DefaultContingencyList;
import com.powsybl.contingency.json.ContingencyJsonModule;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import org.junit.jupiter.api.BeforeEach;
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

    Contingency contingency;
    ContingencyLoadFlowParameters contingencyLoadFlowParameters;

    @BeforeEach
    void setUpContingency() {
        contingency = new Contingency("L2", new BranchContingency("L2"));
        contingencyLoadFlowParameters = new ContingencyLoadFlowParameters()
                .setAreaInterchangeControl(true)
                .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        contingency.addExtension(ContingencyLoadFlowParameters.class, contingencyLoadFlowParameters);
    }

    @Test
    void testContingencyLoadFlowParametersExtension() {
        assertEquals(contingencyLoadFlowParameters, contingency.getExtensionByName("contingency-load-flow-parameters"));

        // test base getters
        assertFalse(contingencyLoadFlowParameters.isDistributedSlack().isPresent());
        assertTrue(contingencyLoadFlowParameters.isAreaInterchangeControl().isPresent());
        assertTrue(contingencyLoadFlowParameters.getBalanceType().isPresent());

        assertTrue(contingencyLoadFlowParameters.isAreaInterchangeControl().get());
        assertEquals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD, contingencyLoadFlowParameters.getBalanceType().get());
    }

    @Test
    void testContingencyLoadFlowParametersExtensionDefaults() {
        LoadFlowParameters loadFlowParameters = new LoadFlowParameters()
                .setDistributedSlack(true)
                .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_REMAINING_MARGIN);

        OpenLoadFlowParameters openLoadFlowParameters = OpenLoadFlowParameters.create(loadFlowParameters)
                .setAreaInterchangeControl(false);

        assertTrue(contingencyLoadFlowParameters.isDistributedSlack(loadFlowParameters));
        assertTrue(contingencyLoadFlowParameters.isAreaInterchangeControl(openLoadFlowParameters));
        assertEquals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD, contingencyLoadFlowParameters.getBalanceType(loadFlowParameters));

        //switch between overriden and default values

        contingencyLoadFlowParameters.setDistributedSlack(true);
        contingencyLoadFlowParameters.setAreaInterchangeControl(null);
        contingencyLoadFlowParameters.setBalanceType(null);

        assertFalse(contingencyLoadFlowParameters.isAreaInterchangeControl(openLoadFlowParameters));
        assertEquals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_REMAINING_MARGIN, contingencyLoadFlowParameters.getBalanceType(loadFlowParameters));
    }

    @Test
    void testContingencyLoadFlowParametersExtensionJson() throws IOException {
        contingencyLoadFlowParameters.setDistributedSlack(false);
        contingency.addExtension(ContingencyLoadFlowParameters.class, contingencyLoadFlowParameters);
        assertEquals(ContingencyLoadFlowParameters.class, new ContingencyLoadFlowParametersJsonSerializer().getExtensionClass());

        ContingencyLoadFlowParameters contingencyLoadFlowParameters2 = new ContingencyLoadFlowParameters();
        Contingency contingency2 = new Contingency("L5", new BranchContingency("L5"));
        contingency2.addExtension(ContingencyLoadFlowParameters.class, contingencyLoadFlowParameters2);

        DefaultContingencyList contingencyList = new DefaultContingencyList(contingency, contingency2);

        roundTripTest(contingencyList, OpenSecurityAnalysisExtensionsTest::writeContingency, OpenSecurityAnalysisExtensionsTest::readContingencyList, "/contingencies.json");
    }

    public static DefaultContingencyList readContingencyList(Path jsonFile) {
        Objects.requireNonNull(jsonFile);

        try (InputStream is = Files.newInputStream(jsonFile)) {
            ObjectMapper objectMapper = JsonUtil.createObjectMapper();
            ContingencyJsonModule module = new ContingencyJsonModule();
            objectMapper.registerModule(module);

            return objectMapper.readValue(is, DefaultContingencyList.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void writeContingency(DefaultContingencyList contingencyList, Path jsonFile) {
        Objects.requireNonNull(contingencyList);
        Objects.requireNonNull(jsonFile);

        try (OutputStream os = Files.newOutputStream(jsonFile)) {
            ObjectMapper mapper = JsonUtil.createObjectMapper();
            ContingencyJsonModule module = new ContingencyJsonModule();
            mapper.registerModule(module);

            ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
            writer.writeValue(os, contingencyList);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
