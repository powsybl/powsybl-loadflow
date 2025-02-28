package com.powsybl.openloadflow;

import com.powsybl.loadflow.AbstractLoadFlowDefaultParametersLoader;

public class OLFDefaultParametersLoaderMock extends AbstractLoadFlowDefaultParametersLoader {
    private static final String RESOURCE_FILE = "/OLFParametersUpdate.json";

    OLFDefaultParametersLoaderMock(String name) {
        super(name, RESOURCE_FILE);
    }
}
