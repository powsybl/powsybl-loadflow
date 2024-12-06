/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.commons.config.InMemoryPlatformConfig;
import com.powsybl.commons.config.MapModuleConfig;
import com.powsybl.commons.test.AbstractSerDeTest;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.util.JsonSerializer;
import com.powsybl.openloadflow.util.PowsyblOpenLoadFlowVersion;
import com.powsybl.openloadflow.util.ProviderConstants;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.json.JsonSecurityAnalysisParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class OpenSecurityAnalysisProviderTest extends AbstractSerDeTest {

    private OpenSecurityAnalysisProvider provider;

    @BeforeEach
    public void setUp() throws IOException {
        super.setUp();
        provider = new OpenSecurityAnalysisProvider();
    }

    @Test
    void basicTest() {
        assertEquals(ProviderConstants.NAME, provider.getName());
        assertEquals(new PowsyblOpenLoadFlowVersion().toString(), provider.getVersion());
        assertEquals(ProviderConstants.NAME, provider.getLoadFlowProviderName().orElseThrow());
    }

    @Test
    void specificParametersNamesTest() {
        assertEquals(List.of("createResultExtension", "contingencyPropagation", "threadCount", "dcFastMode"), provider.getSpecificParametersNames());
    }

    @Test
    void specificParametersFromDefaultPlatformConfigTest() {
        InMemoryPlatformConfig platformConfig = new InMemoryPlatformConfig(fileSystem);
        OpenSecurityAnalysisParameters parametersExt = (OpenSecurityAnalysisParameters) provider.loadSpecificParameters(platformConfig).orElseThrow();
        assertEquals("open-security-analysis-parameters", parametersExt.getName());
        assertFalse(parametersExt.isCreateResultExtension());
        parametersExt.setCreateResultExtension(true);
        assertTrue(parametersExt.isCreateResultExtension());
        assertTrue(parametersExt.isContingencyPropagation());
        parametersExt.setContingencyPropagation(false);
        assertFalse(parametersExt.isContingencyPropagation());
        assertFalse(parametersExt.isDcFastMode());
        parametersExt.setDcFastMode(true);
        assertTrue(parametersExt.isDcFastMode());
    }

    @Test
    void specificParametersFromPlatformConfigTest() {
        InMemoryPlatformConfig platformConfig = new InMemoryPlatformConfig(fileSystem);
        MapModuleConfig moduleConfig = platformConfig.createModuleConfig("open-security-analysis-default-parameters");
        moduleConfig.setStringProperty("createResultExtension", "true");
        moduleConfig.setStringProperty("contingencyPropagation", "false");
        moduleConfig.setStringProperty("dcFastMode", "true");
        OpenSecurityAnalysisParameters parametersExt = (OpenSecurityAnalysisParameters) provider.loadSpecificParameters(platformConfig).orElseThrow();
        assertTrue(parametersExt.isCreateResultExtension());
        assertFalse(parametersExt.isContingencyPropagation());
        assertTrue(parametersExt.isDcFastMode());
    }

    @Test
    void specificParametersFromEmptyPropertiesTest() {
        OpenSecurityAnalysisParameters parametersExt = (OpenSecurityAnalysisParameters) provider.loadSpecificParameters(Collections.emptyMap()).orElseThrow();
        assertFalse(parametersExt.isCreateResultExtension());
        assertTrue(parametersExt.isContingencyPropagation());
        assertFalse(parametersExt.isDcFastMode());
    }

    @Test
    void specificParametersFromPropertiesTest() {
        Map<String, String> properties = Map.of("createResultExtension", "true", "contingencyPropagation", "false", "dcFastMode", "true");
        OpenSecurityAnalysisParameters parametersExt = (OpenSecurityAnalysisParameters) provider.loadSpecificParameters(properties).orElseThrow();
        assertTrue(parametersExt.isCreateResultExtension());
        assertFalse(parametersExt.isContingencyPropagation());
        assertTrue(parametersExt.isDcFastMode());
    }

    @Test
    void jsonTest() throws IOException {
        SecurityAnalysisParameters parameters = new SecurityAnalysisParameters();
        OpenSecurityAnalysisParameters parametersExt = new OpenSecurityAnalysisParameters()
                .setCreateResultExtension(true)
                .setContingencyPropagation(false)
                .setDcFastMode(true);
        parameters.addExtension(OpenSecurityAnalysisParameters.class, parametersExt);
        roundTripTest(parameters, JsonSecurityAnalysisParameters::write, JsonSecurityAnalysisParameters::read, "/sa-params.json");
    }

    @Test
    void testContingencyParametersExtension() {
        Contingency contingency = new Contingency("L2", new BranchContingency("L2"));
        contingency.addExtension(ContingencyParameters.class, new ContingencyParameters(false, true, LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD));

        ContingencyParameters contingencyParameters = contingency.getExtension(ContingencyParameters.class);

        assertEquals(contingencyParameters, contingency.getExtensionByName("contingency-parameters"));
        assertFalse(contingencyParameters.isDistributedSlack());
        assertTrue(contingencyParameters.isAreaInterchangeControl());
        assertEquals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD, contingencyParameters.getBalanceType());
    }

    @Test
    void testContingencyParametersExtensionJson() throws IOException {
        Contingency contingency = new Contingency("L2", new BranchContingency("L2"));
        contingency.addExtension(ContingencyParameters.class, new ContingencyParameters(false, true, LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD));
        roundTripTest(contingency, JsonSerializer::write, JsonSerializer::readContingency, "/contingencies.json");
    }
}
